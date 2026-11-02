package dev.lifestealplus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Ready-made mixes of settings from presets.yml: casual, standard and hardcore. Every preset
 * covers the same settings (any that one of them changes), using the shipped default for
 * whatever it doesn't list, so applying one gives the same result whatever was set before.
 */
final class Presets {

    /** @param values every covered setting and the value this preset gives it, in config.yml order */
    record Preset(String name, String description, Map<String, Object> values) {

        /** The settings this preset would change from {@code current}, with their new values. */
        Map<String, Object> changes(Configuration current) {
            Map<String, Object> changes = new LinkedHashMap<>();
            values.forEach((key, value) -> {
                if (!same(current.get(key), value)) changes.put(key, value);
            });
            return changes;
        }

        boolean inUse(Configuration current) {
            return changes(current).isEmpty();
        }
    }

    private final List<Preset> presets;

    /** Settings presets.yml names that config.yml doesn't have are skipped. */
    Presets(Configuration file, Configuration shippedConfig) {
        List<String> covered = new ArrayList<>();
        for (String key : shippedConfig.getKeys(true)) {
            if (shippedConfig.isConfigurationSection(key)) continue;
            for (String name : file.getKeys(false)) {
                if (file.isSet(name + ".settings." + key)) {
                    covered.add(key);
                    break;
                }
            }
        }
        List<Preset> found = new ArrayList<>();
        for (String name : file.getKeys(false)) {
            ConfigurationSection settings = file.getConfigurationSection(name + ".settings");
            Map<String, Object> values = new LinkedHashMap<>();
            for (String key : covered) {
                values.put(key, settings != null && settings.isSet(key) ? settings.get(key) : shippedConfig.get(key));
            }
            found.add(new Preset(name, file.getString(name + ".description", ""), values));
        }
        presets = List.copyOf(found);
    }

    List<Preset> all() {
        return presets;
    }

    Preset find(String name) {
        return presets.stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    /** 1 and 1.0 are the same setting value. */
    static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
        return Objects.equals(a, b);
    }
}
