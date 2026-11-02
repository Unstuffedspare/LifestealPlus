package dev.lifestealplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Every message key the code asks for exists in the shipped messages.yml, as text that parses. */
class MessagesTest {

    private static YamlConfiguration shipped() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                MessagesTest.class.getResourceAsStream("/messages.yml"), StandardCharsets.UTF_8));
    }

    /** String literals in the plugin's source that look like message keys ("section.name"). */
    private static Set<String> keysUsedInCode(Set<String> sections) throws IOException {
        Pattern literal = Pattern.compile("\"([a-z0-9-]+\\.[a-z0-9.-]+)\"");
        Set<String> used = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = literal.matcher(Files.readString(file));
                while (m.find()) {
                    String key = m.group(1);
                    if (sections.contains(key.substring(0, key.indexOf('.')))) used.add(key);
                }
            }
        }
        return used;
    }

    @Test
    void everyMessageTheCodeUsesExists() throws IOException {
        YamlConfiguration messages = shipped();
        Set<String> sections = messages.getKeys(false);
        List<String> missing = new ArrayList<>();
        for (String key : keysUsedInCode(sections)) {
            // config.yml keys share some section names (purgatory.world, hearts.max...); those aren't messages.
            if (messages.isConfigurationSection(key.substring(0, key.indexOf('.'))) && !messages.isString(key)
                    && !isConfigKey(key)) {
                missing.add(key);
            }
        }
        assertEquals(List.of(), missing);
    }

    private static boolean isConfigKey(String key) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(
                MessagesTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        return config.isSet(key) || key.startsWith("season.") || key.startsWith("alts.salt");
    }

    @Test
    void everyMessageIsTextThatParses() {
        YamlConfiguration messages = shipped();
        int count = 0;
        for (String key : messages.getKeys(true)) {
            if (messages.isConfigurationSection(key)) continue;
            assertTrue(messages.isString(key), key + " should be text");
            MiniMessage.miniMessage().deserialize(messages.getString(key));
            count++;
        }
        assertTrue(count > 200, "expected the full set of messages, found " + count);
    }
}
