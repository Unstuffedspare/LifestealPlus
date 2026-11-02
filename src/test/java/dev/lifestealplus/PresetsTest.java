package dev.lifestealplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Presets change a batch of settings at once, always to the same result. */
class PresetsTest {

    private static YamlConfiguration resource(String name) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                PresetsTest.class.getResourceAsStream("/" + name), StandardCharsets.UTF_8));
    }

    private final YamlConfiguration shipped = resource("config.yml");
    private final Presets presets = new Presets(resource("presets.yml"), shipped);

    private void apply(YamlConfiguration config, String preset) {
        presets.find(preset).changes(config).forEach(config::set);
    }

    @Test
    void everySettingAPresetNamesIsARealSetting() {
        YamlConfiguration file = resource("presets.yml");
        for (String name : file.getKeys(false)) {
            var settings = file.getConfigurationSection(name + ".settings");
            if (settings == null) continue;
            for (String key : settings.getKeys(true)) {
                if (settings.isConfigurationSection(key)) continue;
                assertTrue(shipped.isSet(key), name + " sets " + key + ", which config.yml doesn't have");
            }
        }
        assertEquals(List.of("casual", "standard", "hardcore"), presets.all().stream().map(Presets.Preset::name).toList());
    }

    @Test
    void everyPresetIsSomethingThePluginCanUse() {
        for (Presets.Preset preset : presets.all()) {
            assertNull(SettingsCatalog.problem(shipped, preset.changes(shipped)), preset.name());
            assertFalse(preset.description().isBlank(), preset.name());
        }
    }

    @Test
    void theShippedSettingsAreTheStandardPreset() {
        assertTrue(presets.find("standard").inUse(shipped));
        assertFalse(presets.find("casual").inUse(shipped));
        assertEquals(0, presets.find("casual").changes(shipped).get("hearts.natural-death-loss"));
    }

    @Test
    void aPresetGivesTheSameResultWhateverCameBefore() {
        YamlConfiguration config = resource("config.yml");
        apply(config, "hardcore");
        assertEquals(false, config.get("last-stand.enabled"));
        apply(config, "casual");
        assertTrue(presets.find("casual").inUse(config));
        assertEquals(true, config.get("last-stand.enabled"), "casual doesn't list it, so it goes back to the default");
        apply(config, "standard");
        assertTrue(presets.find("standard").inUse(config));
    }

    @Test
    void settingsNoPresetMentionsAreLeftAlone() {
        YamlConfiguration config = resource("config.yml");
        config.set("airdrops.radius", 500);
        config.set("worlds.excluded", List.of("creative"));
        apply(config, "hardcore");
        assertEquals(500, config.get("airdrops.radius"));
        assertTrue(presets.find("hardcore").inUse(config));
    }

    @Test
    void wholeAndDecimalNumbersMatch() {
        assertTrue(Presets.same(1, 1.0));
        assertFalse(Presets.same(1, 2));
        assertTrue(Presets.same(List.of(), List.of()));
    }
}
