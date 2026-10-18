package dev.migosrules;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import java.net.URI;
import java.util.*;

public final class RulesScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget search;
    private RulesRepository.Snapshot shown;
    private int section = -1, scroll, sectionScroll;
    private String query = "", selected = "", source = "", feedback = "";
    private List<Block> blocks = List.of();
    private record Block(String text, String url, String title, List<OrderedText> lines) {}
    public RulesScreen(Screen parent) { super(Text.literal("Правила MigosMC")); this.parent = parent; }
    private int split() { return Math.max(115, Math.min(185, width / 3)); }
    @Override protected void init() {
        search = new TextFieldWidget(textRenderer, 8, 27, Math.max(80, width - 115), 20, Text.literal("Поиск правил"));
        search.setMaxLength(180);
        search.setPlaceholder(Text.literal("Номер пункта или текст…"));
        search.setText(query);
        search.setChangedListener(value -> { query = value; scroll = 0; rebuild(); });
        addDrawableChild(search);
        addDrawableChild(ButtonWidget.builder(Text.literal("Обновить"), b -> RulesRepository.update()).dimensions(width - 100, 27, 92, 20).build());
        int bw = Math.max(50, Math.min(100, (width - 32) / 3));
        addDrawableChild(ButtonWidget.builder(Text.literal("Назад"), b -> close()).dimensions(8, height - 28, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Копировать"), b -> {
            if (!selected.isEmpty()) { client.keyboard.setClipboard(selected); feedback = "Пункт скопирован"; }
        }).dimensions(16 + bw, height - 28, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Источник"), b -> {
            if (!source.isEmpty()) Screen.handleOpenUri(client, this, URI.create(source));
        }).dimensions(24 + bw * 2, height - 28, bw, 20).build());
        shown = RulesRepository.snapshot;
        rebuild();
    }
    private void rebuild() {
        var topics = RulesRepository.snapshot.topics();
        if (section >= topics.size()) section = -1;
        String needle = query.strip().toLowerCase(Locale.ROOT);
        List<Block> next = new ArrayList<>();
        for (int i = 0; i < topics.size(); i++) {
            if (section >= 0 && section != i) continue;
            var topic = topics.get(i);
            for (String text : topic.paragraphs()) {
                if (!needle.isEmpty() && !text.toLowerCase(Locale.ROOT).contains(needle)
                    && !topic.title().toLowerCase(Locale.ROOT).contains(needle)) continue;
                next.add(new Block(text, topic.url(), topic.title(), textRenderer.wrapLines(Text.literal(text), Math.max(60, width - split() - 28))));
            }
        }
        blocks = List.copyOf(next);
        selected = ""; source = ""; feedback = "";
        clamp();
    }
    private int totalHeight() { return blocks.stream().mapToInt(b -> 24 + b.lines().size() * 11).sum(); }
    private void clamp() {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, totalHeight() - Math.max(0, height - 109))));
        sectionScroll = Math.max(0, Math.min(sectionScroll, Math.max(0, (RulesRepository.snapshot.topics().size() + 1) * 26 - Math.max(0, height - 109))));
    }
    @Override public void render(DrawContext c, int mx, int my, float delta) {
        if (shown != RulesRepository.snapshot) { shown = RulesRepository.snapshot; rebuild(); }
        c.fill(0, 0, width, height, 0xF2161B20);
        c.drawTextWithShadow(textRenderer, "Правила MigosMC • mlpgod", 8, 9, 0xFFFFD278);
        c.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(RulesRepository.status, width - 16), 8, 52, 0xFFAAAAAA);
        int bottom = height - 42;
        String tooltip = null;
        c.enableScissor(6, 67, split() - 3, bottom);
        var topics = shown.topics();
        for (int i = -1; i < topics.size(); i++) {
            int y = 69 + (i + 1) * 26 - sectionScroll;
            if (y + 24 < 67 || y > bottom) continue;
            c.fill(7, y, split() - 5, y + 24, section == i ? 0xFF684E31 : 0xFF262D35);
            String label = i == -1 ? "Все разделы" : topics.get(i).title();
            c.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(label, split() - 22), 12, y + 8, 0xFFFFFFFF);
            if (mx >= 7 && mx < split() - 5 && my >= y && my < y + 24 && my >= 67 && my < bottom) tooltip = label;
        }
        c.disableScissor();
        c.enableScissor(split(), 67, width - 6, bottom);
        int y = 69 - scroll;
        for (Block b : blocks) {
            int bh = 20 + b.lines().size() * 11;
            if (y + bh >= 67 && y < bottom) {
                boolean active = selected.equals(b.text()) && source.equals(b.url());
                c.fill(split() + 2, y, width - 8, y + bh, active ? 0xFF4A4030 : 0xFF222A32);
                c.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(b.title(), width - split() - 26), split() + 8, y + 5, 0xFFFFD278);
                int ly = y + 17;
                for (OrderedText line : b.lines()) { c.drawTextWithShadow(textRenderer, line, split() + 8, ly, 0xFFEEEEEE); ly += 11; }
            }
            y += bh + 4;
        }
        if (blocks.isEmpty()) c.drawTextWithShadow(textRenderer, "Нет правил или совпадений", split() + 8, 78, 0xFFBBBBBB);
        c.disableScissor();
        c.drawTextWithShadow(textRenderer, feedback.isEmpty() ? "Выберите пункт • колесо — прокрутка" : feedback, 8, height - 38, 0xFFAAAAAA);
        super.render(c, mx, my, delta);
        if (tooltip != null) c.drawTooltip(textRenderer, Text.literal(tooltip), mx, my);
    }
    @Override public boolean mouseClicked(Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) return true;
        if (click.button() != 0 || click.y() < 69 || click.y() >= height - 42) return false;
        if (click.x() >= 7 && click.x() < split() - 5) {
            int index = ((int)click.y() - 69 + sectionScroll) / 26 - 1;
            if (index >= -1 && index < shown.topics().size()) { section = index; scroll = 0; rebuild(); }
            return true;
        }
        if (click.x() >= split() && click.x() < width - 6) {
            int y = 69 - scroll;
            for (Block b : blocks) {
                int bh = 20 + b.lines().size() * 11;
                if (click.y() >= y && click.y() < y + bh) { selected = b.text(); source = b.url(); feedback = "Пункт выбран • «Копировать» или «Источник»"; return true; }
                y += bh + 4;
            }
        }
        return false;
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (y >= 67 && y < height - 42) {
            if (x < split()) sectionScroll -= (int)Math.round(vertical * 30);
            else scroll -= (int)Math.round(vertical * 33);
            clamp(); return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override public boolean shouldPause() { return false; }
    @Override public void close() { client.setScreen(parent); }
}
