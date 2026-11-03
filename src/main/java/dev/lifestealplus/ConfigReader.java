package dev.lifestealplus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
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

    /** A value exactly as written (a password may be blank, or look like a number). */
    Object raw(String key, Object fallback) {
        Object value = config.get(key);
        return value == null ? fallback : value;
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

    /**
     * Which names are items. Only a running server knows the full list, so the plugin sets this
     * on start; until then any well-formed name passes.
     */
    static volatile Predicate<String> itemNames = name -> name.matches("[a-z0-9_]+");

    /** An item, by its name ("diamond_block"), lower-cased; anything that isn't one falls back with a warning. */
    String item(String key, String fallback) {
        String name = config.getString(key);
        if (name == null || name.isBlank()) return fallback;
        String item = name.strip().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (item.startsWith("minecraft:")) item = item.substring("minecraft:".length());
        if (itemNames.test(item)) return item;
        warnings.add(key + " is \"" + name + "\", which isn't an item name like diamond_block; using " + fallback + ".");
        return fallback;
    }

    /** The keys directly under a section, in file order; none if it isn't a section. */
    List<String> keys(String section) {
        ConfigurationSection inside = config.getConfigurationSection(section);
        return inside == null ? List.of() : List.copyOf(inside.getKeys(false));
    }

    /** A list of names, like world names; an empty list if it isn't set. */
    List<String> texts(String key) {
        if (!config.isSet(key)) return List.of();
        if (!config.isList(key)) {
            warnings.add(key + " should be a list like [creative, events]; ignoring it.");
            return List.of();
        }
        return config.getList(key).stream().filter(item -> item != null && !String.valueOf(item).isBlank())
                .map(item -> String.valueOf(item).strip()).toList();
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
