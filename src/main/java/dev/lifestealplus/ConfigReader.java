package dev.lifestealplus;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Reads settings without ever failing: a missing, mistyped or out-of-range value falls back
 * to its default and leaves a warning naming the exact setting, so a typo in config.yml
 * can't take the plugin down.
 */
final class ConfigReader {

    private final ConfigurationSection config;
    private final List<String> warnings = new ArrayList<>();

    ConfigReader(ConfigurationSection config) {
        this.config = config;
    }

    List<String> warnings() {
        return warnings;
    }

    void warn(String message) {
        warnings.add(message);
    }

    boolean flag(String key, boolean fallback) {
        if (!config.isSet(key)) return fallback;
        if (config.isBoolean(key)) return config.getBoolean(key);
        warnings.add(key + " should be true or false (it's \"" + config.get(key) + "\"); using " + fallback + ".");
        return fallback;
    }

    int whole(String key, int fallback, int min, int max) {
        if (!config.isSet(key)) return fallback;
        Object raw = config.get(key);
        if (!(raw instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
            warnings.add(key + " should be a whole number (it's \"" + raw + "\"); using " + fallback + ".");
            return fallback;
        }
        return inRange(key, n.intValue(), fallback, min, max);
    }

    double number(String key, double fallback, double min, double max) {
        if (!config.isSet(key)) return fallback;
        Object raw = config.get(key);
        if (!(raw instanceof Number n)) {
            warnings.add(key + " should be a number (it's \"" + raw + "\"); using " + fallback + ".");
            return fallback;
        }
        double value = n.doubleValue();
        if (value < min || value > max) {
            warnings.add(key + " is " + value + ", but it has to be between " + trim(min) + " and " + trim(max)
                    + "; using " + trim(fallback) + ".");
            return fallback;
        }
        return value;
    }

    String text(String key, String fallback) {
        String value = config.getString(key);
        if (value == null || value.isBlank()) return fallback;
        return value;
    }

    /** Like {@link #wholes(String)}, with a fallback for when the setting isn't there at all. */
    List<Integer> wholes(String key, List<Integer> fallback) {
        return config.isSet(key) ? wholes(key) : fallback;
    }

    /** A list of whole numbers; anything in it that isn't one is skipped with a warning. */
    List<Integer> wholes(String key) {
        List<Integer> values = new ArrayList<>();
        if (!config.isSet(key)) return values;
        if (!config.isList(key)) {
            warnings.add(key + " should be a list like [15, 20, 25]; ignoring it.");
            return values;
        }
        for (Object item : config.getList(key)) {
            if (item instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) values.add(n.intValue());
            else warnings.add(key + " contains \"" + item + "\", which isn't a whole number; skipping it.");
        }
        return values;
    }

    private int inRange(String key, int value, int fallback, int min, int max) {
        if (value >= min && value <= max) return value;
        warnings.add(key + " is " + value + ", but it has to be between " + min + " and "
                + (max == Integer.MAX_VALUE ? "anything above" : String.valueOf(max)) + "; using " + fallback + ".");
        return fallback;
    }

    private static String trim(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }
}
