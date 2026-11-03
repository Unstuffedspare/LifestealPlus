package dev.lifestealplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** hooks.yml: fill-ins, and a file that's mistyped is warned about rather than half-used. */
class CommandHooksTest {

    @Test
    void fillInsAreSwappedAndTheSlashDropped() {
        assertEquals("eco give Alice 500 (streak 5)",
                CommandHooks.fill(" /eco give {player} 500 (streak {streak})", Map.of("player", "Alice", "streak", 5)));
        assertEquals("say {nothing} stays", CommandHooks.fill("say {nothing} stays", Map.of("player", "Alice")));
    }

    @Test
    void namesCantBreakOutOfACommand() {
        assertEquals("Alice_2", CommandHooks.name("Alice_2"));
        assertEquals(".BedrockSteve", CommandHooks.name(".Bedrock Steve"));
        assertEquals("evilopme", CommandHooks.name("evil;\nop me"));
    }

    @Test
    void theShippedFileHasEveryHookAndNoProblems() {
        YamlConfiguration shipped = YamlConfiguration.loadConfiguration(new InputStreamReader(
                CommandHooksTest.class.getResourceAsStream("/hooks.yml"), StandardCharsets.UTF_8));
        assertEquals(List.of(), CommandHooks.problems(shipped));
        assertEquals(CommandHooks.HOOKS, shipped.getKeys(false));
    }

    @Test
    void mistakesAreNamed() throws Exception {
        YamlConfiguration file = new YamlConfiguration();
        file.loadFromString("""
                on-kill: [say hi]
                kill: say hi
                streak:
                  five: [say hi]
                  5: say hi
                  10: ["eco give {player} 10"]
                """);
        List<String> problems = CommandHooks.problems(file);
        assertEquals(4, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("\"on-kill\", which isn't a hook"));
        assertTrue(problems.get(1).contains("kill should be a list"));
        assertTrue(problems.get(2).contains("streak.five should be a streak number"));
        assertTrue(problems.get(3).contains("streak.5 should be a list"));
        assertEquals(List.of("eco give {player} 10"), file.getStringList("streak.10"));
    }

    @Test
    void eventHooksAreListedByEvent() throws Exception {
        YamlConfiguration file = new YamlConfiguration();
        file.loadFromString("""
                event-start:
                  blood-moon: ["say up"]
                  bloodmoon: ["say typo"]
                event-end: ["say any"]
                """);
        List<String> problems = CommandHooks.problems(file);
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("event-start.bloodmoon isn't an event"));
        assertTrue(problems.get(1).contains("event-end should list events"));
        assertEquals(List.of("say up"), file.getStringList("event-start.blood-moon"));
    }
}
