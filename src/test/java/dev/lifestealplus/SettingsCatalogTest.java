package dev.lifestealplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.lifestealplus.SettingsCatalog.Kind;
import dev.lifestealplus.SettingsCatalog.Setting;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The in-game settings menu is built from config.yml itself and refuses values the plugin couldn't use. */
class SettingsCatalogTest {

    private static YamlConfiguration shipped() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                SettingsCatalogTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    private final SettingsCatalog catalog = new SettingsCatalog(shipped());

    @Test
    void everySettingInTheFileIsListedWithItsComment() {
        Setting hours = catalog.find("newbie-shield.hours");
        assertEquals(Kind.WHOLE, hours.kind());
        Setting expiry = catalog.find("newbie-shield.expires-after-days");
        assertTrue(String.join(" ", expiry.description()).contains("first joins"));
        assertEquals(Kind.NUMBER, catalog.find("purgatory.difficulty").kind());
        assertEquals(Kind.LIST, catalog.find("hearts.max-by-week").kind());
        assertEquals(Kind.TEXT, catalog.find("database.file").kind());
        assertTrue(catalog.find("purgatory.world").needsRestart());
        assertFalse(catalog.find("hearts.max").needsRestart());
        assertTrue(catalog.sections().contains("newbie-shield"));
        assertEquals("hearts", catalog.sections().getFirst());
        assertEquals(Kind.LIST, catalog.find("worlds.excluded").kind());
    }

    @Test
    void namesReadAsWords() {
        assertEquals("Newbie shield", SettingsCatalog.words("newbie-shield"));
        assertEquals("Tracker: round to blocks", catalog.find("vengeance.tracker.round-to-blocks").label());
        assertEquals("Expires after days", catalog.find("newbie-shield.expires-after-days").label());
        assertTrue(catalog.find("newbie-shield.enabled").isSwitch());
        assertFalse(catalog.find("vengeance.tracker.enabled").isSwitch());
    }

    @Test
    void typedValuesAreUnderstood() {
        assertEquals(false, SettingsCatalog.parse(catalog.find("newbie-shield.enabled"), "off"));
        assertEquals(true, SettingsCatalog.parse(catalog.find("newbie-shield.enabled"), "Yes"));
        assertEquals(5, SettingsCatalog.parse(catalog.find("hearts.max"), " 5 "));
        assertEquals(1.5, SettingsCatalog.parse(catalog.find("hearts.max"), "1.5"), "the check, not the parse, refuses it");
        assertEquals(List.of(15, 20, 25), SettingsCatalog.parse(catalog.find("hearts.max-by-week"), "[15, 20 25]"));
        assertEquals(List.of(), SettingsCatalog.parse(catalog.find("hearts.max-by-week"), "none"));
        assertEquals(List.of("creative", "events"), SettingsCatalog.parse(catalog.find("worlds.excluded"), "creative, events"));
        assertThrows(IllegalArgumentException.class, () -> SettingsCatalog.parse(catalog.find("hearts.max"), "twenty"));
        assertThrows(IllegalArgumentException.class, () -> SettingsCatalog.parse(catalog.find("newbie-shield.enabled"), "maybe"));
    }

    @Test
    void clicksStepBySensibleAmounts() {
        assertEquals(4, SettingsCatalog.step(catalog.find("newbie-shield.hours"), 3, 1));
        assertEquals(0, SettingsCatalog.step(catalog.find("newbie-shield.hours"), 0, -1));
        assertEquals(1.1, SettingsCatalog.step(catalog.find("purgatory.difficulty"), 1.0, 1));
        assertEquals(0.03, SettingsCatalog.step(catalog.find("blood-moon.fragment-chance"), 0.02, 1));
        assertEquals("on", SettingsCatalog.show(true));
        assertEquals("1.1", SettingsCatalog.show(1.1));
        assertEquals("none", SettingsCatalog.show(List.of()));
    }

    @Test
    void valuesThePluginCouldntUseAreRefused() {
        YamlConfiguration config = shipped();
        assertNull(SettingsCatalog.problem(config, "hearts.max", 30));
        assertNull(SettingsCatalog.problem(config, "newbie-shield.enabled", false));
        assertEquals("hearts.max should be a whole number (it's \"1.5\").", SettingsCatalog.problem(config, "hearts.max", 1.5));
        assertTrue(SettingsCatalog.problem(config, "hearts.max", 0).startsWith("hearts.max is 0, but it has to be between 1 and 1000"));
        assertTrue(SettingsCatalog.problem(config, "hearts.max", 5).startsWith("hearts.starting (10) is above hearts.max (5)"));
        assertNull(SettingsCatalog.problem(config, "worlds.excluded", List.of("creative", "events")));
        assertEquals("hearts.max-by-week contains \"soon\", which isn't a whole number.",
                SettingsCatalog.problem(config, "hearts.max-by-week", List.of("soon")));
    }
}
