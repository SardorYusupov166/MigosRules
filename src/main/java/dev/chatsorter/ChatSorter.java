package dev.chatsorter;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ChatSorter implements ClientModInitializer {
    public enum Tab { NEARBY("Общий чат"), DM("ЛС"), SPY("Spy"), CONSOLE("Консоль"), ALL("Все");
        public final String title; Tab(String title) { this.title = title; }
    }
    public static Tab selected = Tab.ALL;
    private static boolean replaying = false;
    private static String searchQuery = "";
    public static String searchQuery() { return searchQuery; }
    public static void setSearchQuery(String query) {
        String next = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (next.equals(searchQuery)) return;
        searchQuery = next;
        refreshChat();
    }
    public static boolean isSearching() { return !searchQuery.isEmpty(); }
    private static void refreshChat() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        ChatHud hud = mc.inGameHud.getChatHud();
        replaying = true;
        try {
            hud.clear(false);
            List<Text> source = isSearching() ? history[Tab.ALL.ordinal()] : history[selected.ordinal()];
            for (Text msg : source) {
                if (!isSearching() || msg.getString().toLowerCase(Locale.ROOT).contains(searchQuery)) hud.addMessage(msg);
            }
        } finally { replaying = false; }
    }
    public static final int[] unread = new int[Tab.values().length];
    // The server may place the arrow either inside or outside the nickname bracket.
    // Chat Heads can insert a nested "[nickname head]" decoration before the nickname.
    private static final java.util.regex.Pattern PRIVATE_MESSAGE = java.util.regex.Pattern.compile(
        "^\\[(?:\\[~?[^\\]\\r\\n]+\\s+head\\])?~?[^\\]\\r\\n]{1,80}\\s*(?:->|→|<-|←)\\s*[^\\]\\r\\n]{1,80}\\]\\s*.+$");
    private static final java.util.regex.Pattern PRIVATE_MESSAGE_OUTSIDE = java.util.regex.Pattern.compile(
        "^\\[(?:\\[~?[^\\]\\r\\n]+\\s+head\\])?~?[^\\]\\r\\n]{1,80}\\]\\s*(?:->|→|<-|←)\\s*(?:я|мне|me|[A-Za-z0-9_]{3,16})\\s+.+$",
        java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
    public static String timestamp(Text message) { return java.time.LocalTime.now().withNano(0).toString(); }
    private static final java.util.IdentityHashMap<Text,String> times = new java.util.IdentityHashMap<>();
    private static final java.util.Map<String,String> latestTimes = new java.util.HashMap<>();
    public static String copyRenderedLine(String line) {
        return "[" + latestTimes.getOrDefault(line, java.time.LocalTime.now().withNano(0).toString()) + "] " + line;
    }
    public static String copyText(Text text) { return "[" + times.getOrDefault(text, java.time.LocalTime.now().withNano(0).toString()) + "] " + text.getString(); }

    private static final List<Text>[] history = new List[]{new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),new ArrayList<>()};
    @Override public void onInitializeClient() {
        ChatPosition.load();
        KeywordAlerts.load();
        ChatTheme.load();
        AlertHotkey.load();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            AlertHotkey.tick(client);
            if (client.player != null && client.currentScreen == null && AlertHotkey.needsSetup()) AlertHotkey.prompt();
        });
        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            int unreadDm = unread[Tab.DM.ordinal()];
            if (unreadDm <= 0) return;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null || mc.options.hudHidden) return;
            String label = "ЛС: " + unreadDm + " непрочитанных";
            int textWidth = mc.textRenderer.getWidth(label);
            int right = context.getScaledWindowWidth() - 6;
            int top = mc.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen ? 29 : 7;
            context.fill(right - textWidth - 14, top, right, top + 18, 0xDD292D32);
            context.fill(right - textWidth - 14, top, right - textWidth - 11, top + 18, ChatTheme.bright());
            context.drawTextWithShadow(mc.textRenderer, label, right - textWidth - 7, top + 5, 0xFFFFFFFF);
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // Start a fresh per-server history, then deliver the greeting locally.
            reset();
            String serverName = client.getCurrentServerEntry() == null ? "singleplayer_or_unknown" : client.getCurrentServerEntry().address;
            ChatSorterDiagnostics.startSession(serverName);
            client.execute(() -> {
                if (client.player == null) return;
                client.inGameHud.getChatHud().addMessage(Text.literal("[ChatSorter] Мод успешно загружен!").styled(style -> style.withColor(0x55FF55)));
                client.inGameHud.getChatHud().addMessage(Text.literal("Автор: ").styled(style -> style.withColor(0xFFCC55))
                    .append(Text.literal("mlpgod").styled(style -> style.withColor(0xFFFFFF))));
                client.inGameHud.getChatHud().addMessage(Text.literal("Сервер: ").styled(style -> style.withColor(0xFFCC55))
                    .append(Text.literal("MigosMC").styled(style -> style.withColor(0xFFFFFF))));
                client.inGameHud.getChatHud().addMessage(Text.literal("ChatSorter 1.3.5 | mlpgod - MigosMC").styled(style -> style.withColor(0xAAAAAA)));
            });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { ChatSorterDiagnostics.endSession(); reset(); });
    }

    public static boolean capture(Text message) {
        if (replaying) return true;
        Tab tab = message.getString().startsWith("[ChatSorter]") || message.getString().startsWith("Автор: ") || message.getString().startsWith("Сервер: ") || message.getString().startsWith("ChatSorter 1.3.5 |")
            ? Tab.NEARBY : classify(message.getString());
        KeywordAlerts.notifyIfMatched(message.getString());
        ChatSorterDiagnostics.record(message, tab.name(), selected.name(), tab == selected || selected == Tab.ALL);
        List<Text> messages = history[tab.ordinal()];
        Text stored = message.copy();
        messages.add(stored);
        times.put(stored, timestamp(message));
        latestTimes.put(message.getString(), times.get(stored));
        List<Text> allMessages = history[Tab.ALL.ordinal()];
        Text allStored = message.copy();
        allMessages.add(allStored);
        times.put(allStored, times.get(stored));
        if (allMessages.size() > 300) times.remove(allMessages.remove(0));
        if (messages.size() > 300) times.remove(messages.remove(0));
        if (selected != tab && selected != Tab.ALL) { unread[tab.ordinal()]++; unread[Tab.ALL.ordinal()]++; }
        if (isSearching()) {
            if (message.getString().toLowerCase(Locale.ROOT).contains(searchQuery)) return true;
            return false;
        }
        return tab == selected || selected == Tab.ALL;
    }
    // Server prefixes may contain formatting, non-breaking spaces or icon glyphs.
    private static final java.util.regex.Pattern GAME_TAG = java.util.regex.Pattern.compile(
        "\\[\\s*([GLSɢʟꜱ])\\s*\\]", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    private static Tab classify(String original) {
        String s = original.replace('\u00a0', ' ').replace('\u200b', ' ').replace('\u200c', ' ')
            .replace('\u200d', ' ').replaceAll("§[0-9a-fk-orA-FK-OR]", "").strip();
        String lower = s.toLowerCase(Locale.ROOT);
        // Explicit Console/Spy prefixes take priority over embedded chat markers.
        // MigosMC uses Unicode small-cap glyphs ɢ (U+0262) and ʟ (U+029F).
        if (!lower.startsWith("[console]") && !lower.startsWith("[консоль]")
                && (PRIVATE_MESSAGE.matcher(s).matches() || PRIVATE_MESSAGE_OUTSIDE.matcher(s).matches())) return Tab.DM;
        // Server announcements are public chat, not console diagnostics.
        if (lower.matches("^\\s*\\[\\s*объявлени[ея]\\s*\\].*")) return Tab.NEARBY;
        if (lower.startsWith("[console]") || lower.startsWith("[консоль]")) return Tab.CONSOLE;
        if (lower.startsWith("[spy]") || lower.startsWith("[socialspy]") || lower.startsWith("[commandspy]")) return Tab.SPY;
        java.util.regex.Matcher game = GAME_TAG.matcher(s);
        if (game.find() && game.start() < 100) return Tab.NEARBY;
        if (lower.contains("socialspy") || lower.contains("commandspy") || lower.contains("/msg ") || lower.contains("/tell ")) return Tab.SPY;
        if (lower.contains("[игрок]") || lower.contains("[рядом]") || lower.contains("[локальный]")) return Tab.NEARBY;
        if (lower.startsWith("<") || lower.startsWith("[чат]") || lower.startsWith("[chat]")) return Tab.NEARBY;
        return Tab.CONSOLE;
    }
    public static void switchTo(Tab tab) {
        if (selected == tab && !isSearching()) return;
        selected = tab;
        unread[tab.ordinal()] = 0;
        if (tab == Tab.ALL) java.util.Arrays.fill(unread, 0);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        refreshChat();
    }
    public static void reset() {
        for (List<Text> messages : history) messages.clear();
        times.clear();
        latestTimes.clear();
        java.util.Arrays.fill(unread, 0);
        selected = Tab.ALL;
        searchQuery = "";
    }
}
