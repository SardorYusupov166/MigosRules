package dev.migosrules;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Reads the selected ChatSorter palette without linking this addon to its classes. */
final class RulesTheme {
    private static final Path PATH = Path.of("config", "chatsorter", "theme.properties");
    private static final int[] ACCENTS = {0xE048604C, 0xE06B283C, 0xE02B537C, 0xE0593979, 0xE044484E};
    private static final int[] BRIGHTS = {0xFF91C79B, 0xFFCE7892, 0xFF82B7E9, 0xFFBE9AE8, 0xFFB5BCC3};
    private static int index;

    static void load() {
        index = 0;
        if (!Files.isRegularFile(PATH)) return;
        try (InputStream in = Files.newInputStream(PATH)) {
            Properties properties = new Properties();
            properties.load(in);
            index = Math.floorMod(Integer.parseInt(properties.getProperty("theme", "0")), ACCENTS.length);
        } catch (Exception ignored) {
            index = 0;
        }
    }

    static int accent() { return ACCENTS[index]; }
    static int bright() { return BRIGHTS[index]; }
    static int text() { return bright(); }

    private RulesTheme() {}
}
