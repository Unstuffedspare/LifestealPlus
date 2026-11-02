package dev.lifestealplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** config.yml is read forgivingly: bad values fall back to defaults with a warning naming the setting. */
class SettingsTest {

    private static YamlConfiguration shipped() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                SettingsTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }

    private static PluginSettings.Loaded load(String yamlOverrides) throws Exception {
        YamlConfiguration config = shipped();
        YamlConfiguration overrides = new YamlConfiguration();
        overrides.loadFromString(yamlOverrides);
        for (String key : overrides.getKeys(true)) {
            if (!overrides.isConfigurationSection(key)) config.set(key, overrides.get(key));
        }
        return PluginSettings.load(config);
    }

    @Test
    void theShippedConfigLoadsWithoutWarnings() {
        PluginSettings.Loaded loaded = PluginSettings.load(shipped());
        assertEquals(List.of(), loaded.warnings());
        PluginSettings s = loaded.settings();
        assertEquals(20, s.rules().maxHearts());
        assertEquals(Duration.ofHours(6), s.rules().repeatKillWindow());
        assertTrue(s.extras().vengeanceTracker());
        assertEquals(4, s.social().decayDays());
    }

    @Test
    void anEmptyConfigUsesEveryDefault() {
        PluginSettings.Loaded loaded = PluginSettings.load(new YamlConfiguration());
        assertEquals(List.of(), loaded.warnings());
        assertEquals(10, loaded.settings().rules().startingHearts());
        assertTrue(loaded.settings().purgatory().enabled());
    }

    @Test
    void aTypoFallsBackAndSaysWhere() throws Exception {
        PluginSettings.Loaded loaded = load("hearts:\n  max: twenty\n");
        assertEquals(20, loaded.settings().rules().maxHearts());
        assertEquals(1, loaded.warnings().size());
        assertTrue(loaded.warnings().getFirst().startsWith("hearts.max should be a whole number"));
    }

    @Test
    void outOfRangeValuesFallBack() throws Exception {
        PluginSettings.Loaded loaded = load("purgatory:\n  difficulty: 50\nblood-moon:\n  fragment-chance: 3\n");
        assertEquals(1.0, loaded.settings().purgatory().difficulty());
        assertEquals(0.02, loaded.settings().events().bloodMoon().fragmentChance());
        assertEquals(2, loaded.warnings().size());
    }

    @Test
    void startingAboveTheCapIsPulledDown() throws Exception {
        PluginSettings.Loaded loaded = load("hearts:\n  max: 8\n");
        assertEquals(8, loaded.settings().rules().startingHearts());
        assertTrue(loaded.warnings().getFirst().contains("hearts.starting"));
    }

    @Test
    void aBadSwitchFallsBackToOn() throws Exception {
        PluginSettings.Loaded loaded = load("bounties:\n  enabled: maybe\n");
        assertTrue(loaded.settings().bountiesEnabled());
        assertTrue(loaded.warnings().getFirst().startsWith("bounties.enabled should be true or false"));
    }

    @Test
    void switchingFeaturesOffMakesThemDoNothing() throws Exception {
        PluginSettings s = load("""
                repeat-kills: {enabled: false}
                vengeance: {enabled: false}
                newbie-shield: {enabled: false}
                combat-log: {enabled: false}
                last-stand: {enabled: false}
                most-wanted: {enabled: false}
                blood-moon: {enabled: false}
                airdrops: {enabled: false}
                heart-items: {decay: {enabled: false}, withdraw: {enabled: false}}
                rifts: {enabled: false}
                revival-votes: {enabled: false}
                blood-pacts: {enabled: false}
                ghosts: {enabled: false, whispers: {enabled: false}}
                bounties: {enabled: false}
                tab-list: {enabled: false}
                alt-detection: {enabled: false}
                """).settings();
        assertTrue(s.rules().repeatKillWindow().isZero());
        assertTrue(s.rules().vengeanceWindow().isZero());
        assertFalse(s.extras().vengeanceTracker());
        assertTrue(s.rules().newbieShield().isZero());
        assertFalse(s.extras().shieldsEnabled());
        assertTrue(s.combat().tag().isZero());
        assertEquals(0, s.lastStandHearts());
        assertTrue(s.mostWanted().interval().isZero());
        assertTrue(s.events().bloodMoon().every().isZero());
        assertTrue(s.events().airdrops().every().isZero());
        assertEquals(0, s.social().decayDays());
        assertFalse(s.social().withdrawEnabled());
        assertFalse(s.social().riftsEnabled());
        assertFalse(s.social().pledgesEnabled());
        assertFalse(s.social().pactsEnabled());
        assertFalse(s.social().ghostsEnabled());
        assertFalse(s.social().whispersEnabled());
        assertFalse(s.bountiesEnabled());
        assertFalse(s.extras().tabList());
        assertFalse(s.extras().altDetection());
    }

    @Test
    void badCapScheduleEntriesAreSkipped() throws Exception {
        PluginSettings.Loaded loaded = load("hearts:\n  max-by-week: [15, abc, 5, 25]\n");
        assertEquals(List.of(15, 25), loaded.settings().social().capSchedule());
        assertEquals(2, loaded.warnings().size());
    }
}
