package dev.migosrules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.MinecraftClient;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/** Loads public rules only. No login, cookies, or server commands. */
public final class RulesRepository {
    public record Topic(String title, String url, List<String> paragraphs) {}
    public record Snapshot(String updated, List<Topic> topics) {}
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String BASE = "https://forum.migosmc.net";
    private static final Path CACHE = MinecraftClient.getInstance().runDirectory.toPath()
        .resolve("config/chatsorter/migos-rules.json");
    public static volatile Snapshot snapshot = new Snapshot("", List.of());
    public static volatile String status = "Нажмите «Обновить», чтобы загрузить правила с форума";
    public static volatile boolean busy;
    private static boolean loaded;
    private static final Pattern POINT = Pattern.compile("^\\d+\\.\\d+(?:\\.\\d+)*(?:[.)]|\\s).*", Pattern.DOTALL);
    private RulesRepository() {}
    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        if (!Files.exists(CACHE)) return;
        try {
            Snapshot data = JSON.fromJson(Files.readString(CACHE), Snapshot.class);
            validate(data);
            snapshot = data;
            status = "Сохранённая копия • " + data.updated();
        } catch (Exception e) { status = "Не удалось прочитать копию. Нажмите «Обновить»"; }
    }
    public static synchronized void update() {
        if (busy) return;
        busy = true;
        status = "Поиск разделов правил…";
        CompletableFuture.runAsync(() -> {
            try {
                LinkedHashSet<String> forums = new LinkedHashSet<>();
                forums.add(BASE + "/forums/35/");
                Document home = fetch(BASE + "/");
                for (Element a : home.select("a[href*=/forums/]")) {
                    String label = a.text().toLowerCase(Locale.ROOT);
                    if (label.contains("правила") && !label.contains("форум")) add(forums, a.absUrl("href"), "/forums/");
                }
                LinkedHashMap<String, String> threads = new LinkedHashMap<>();
                Set<String> visited = new HashSet<>();
                while (visited.size() < 80) {
                    String url = forums.stream().filter(u -> !visited.contains(u)).findFirst().orElse(null);
                    if (url == null) break;
                    visited.add(url);
                    Document page = fetch(url);
                    for (Element a : page.select(".node-title a[href*=/forums/], a.pageNav-jump--next"))
                        add(forums, a.absUrl("href"), "/forums/");
                    for (Element a : page.select(".structItem-title a[href*=/threads/]")) {
                        String target = canonical(a.absUrl("href"), "/threads/");
                        if (target != null) threads.putIfAbsent(target, a.text());
                    }
                }
                if (forums.size() > visited.size()) throw new IllegalStateException("Слишком много страниц разделов");
                if (threads.isEmpty() || threads.size() > 150) throw new IllegalStateException("Не удалось определить список тем правил");
                List<Topic> topics = new ArrayList<>();
                Set<String> resolved = new HashSet<>();
                for (var entry : threads.entrySet()) {
                    status = "Загрузка: " + (topics.size() + 1) + " / " + threads.size();
                    Document page = fetch(entry.getKey());
                    String source = page.location();
                    if (!resolved.add(source)) continue;
                    Element body = page.selectFirst(".message--post .message-body .bbWrapper");
                    if (body == null) throw new IllegalStateException("Форум не вернул текст: " + entry.getValue());
                    String title = page.select("h1.p-title-value").text();
                    if (title.isBlank()) title = entry.getValue();
                    List<String> contents = new ArrayList<>(paragraphs(body));
                    Element firstPost = page.selectFirst(".message--post");
                    String author = firstPost == null ? "" : firstPost.attr("data-author");
                    appendOtherPosts(page, author, contents, true);
                    Set<String> pages = new HashSet<>();
                    pages.add(source);
                    for (int n = 0; n < 30; n++) {
                        Element link = page.selectFirst("a.pageNav-jump--next[href*=/threads/]");
                        if (link == null) break;
                        String nextPage = canonical(link.absUrl("href"), "/threads/");
                        if (nextPage == null || !pages.add(nextPage)) throw new IllegalStateException("Некорректная навигация темы");
                        page = fetch(nextPage);
                        appendOtherPosts(page, author, contents, false);
                        if (n == 29 && page.selectFirst("a.pageNav-jump--next") != null)
                            throw new IllegalStateException("Слишком много страниц в теме");
                    }
                    topics.add(new Topic(title, source, List.copyOf(contents)));
                }
                Snapshot next = new Snapshot(Instant.now().toString(), List.copyOf(topics));
                validate(next);
                Files.createDirectories(CACHE.getParent());
                Path temp = Files.createTempFile(CACHE.getParent(), "rules-", ".tmp");
                try {
                    Files.writeString(temp, JSON.toJson(next), StandardCharsets.UTF_8);
                    try { Files.move(temp, CACHE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                    catch (AtomicMoveNotSupportedException e) { Files.move(temp, CACHE, StandardCopyOption.REPLACE_EXISTING); }
                } finally { Files.deleteIfExists(temp); }
                snapshot = next;
                status = "Обновлено: " + topics.size() + " разделов • " + next.updated();
            } catch (Exception e) {
                status = "Обновление не удалось; локальная копия не изменена. " + e.getClass().getSimpleName();
                org.slf4j.LoggerFactory.getLogger("MigosRules").warn("Rules update failed", e);
            } finally { busy = false; }
        });
    }
    private static void appendOtherPosts(Document page, String author, List<String> result, boolean skipFirst) {
        int index = 0;
        for (Element post : page.select(".message--post")) {
            if (index++ == 0 && skipFirst) continue;
            if (author.isBlank() || !author.equals(post.attr("data-author"))) continue;
            Element body = post.selectFirst(".message-body .bbWrapper");
            if (body != null) result.addAll(paragraphs(body));
        }
    }
    static void validate(Snapshot data) {
        if (data == null || data.updated() == null || data.topics() == null || data.topics().isEmpty())
            throw new IllegalArgumentException("Empty snapshot");
        for (Topic t : data.topics()) {
            if (t == null || t.title() == null || canonical(t.url(), "/threads/") == null
                || t.paragraphs() == null || t.paragraphs().isEmpty()
                || t.paragraphs().stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Invalid topic");
        }
    }
    private static Document fetch(String url) throws Exception {
        if (!URI.create(url).getHost().equals("forum.migosmc.net")) throw new IllegalArgumentException("Unexpected host");
        var response = Jsoup.connect(url).userAgent("MigosRules/1.0 (ChatSorter; public rules reader)")
            .timeout(15000).maxBodySize(4 * 1024 * 1024).followRedirects(true).execute();
        if (!response.url().getHost().equals("forum.migosmc.net")) throw new IllegalArgumentException("Unexpected redirect");
        return response.parse();
    }
    private static void add(Set<String> result, String url, String kind) {
        String next = canonical(url, kind);
        if (next != null) result.add(next);
    }
    static String canonical(String url, String kind) {
        try {
            URI u = URI.create(url);
            if (!"https".equals(u.getScheme()) || !"forum.migosmc.net".equals(u.getHost()) || !u.getPath().startsWith(kind)) return null;
            String path = u.getRawPath().replaceAll("/(latest|unread|post-\\d+)/?$", "/");
            return BASE + path;
        } catch (Exception e) { return null; }
    }
    static List<String> paragraphs(Element original) {
        Element body = original.clone();
        body.select("script,style,.bbCodeBlock-expandLink").remove();
        for (Element a : body.select("a[href]")) {
            String href = a.absUrl("href");
            if (!href.isBlank() && !a.text().equals(href)) a.appendText(" (" + href + ")");
        }
        for (Element image : body.select("img[src]")) {
            String url = image.absUrl("src");
            if (!url.isBlank()) image.after(" [Изображение: " + url + "] ");
        }
        body.select("br").append("\n");
        body.select("p,div,li,h1,h2,h3,h4,blockquote,tr").prepend("\n").append("\n");
        String raw = body.wholeText().replace('\u00a0', ' ').replace("\u200b", "");
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : raw.split("\\R")) {
            line = line.strip();
            if (line.isBlank()) continue;
            boolean heading = line.matches("(?iu)^(пункт|раздел|глава)\\s+.*");
            if ((POINT.matcher(line).matches() || heading) && !current.isEmpty()) {
                result.add(current.toString().strip()); current.setLength(0);
            }
            if (!current.isEmpty()) current.append('\n');
            current.append(line);
        }
        if (!current.isEmpty()) result.add(current.toString().strip());
        return List.copyOf(result);
    }
}
