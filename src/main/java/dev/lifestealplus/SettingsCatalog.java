package dev.lifestealplus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Every setting in config.yml, worked out from the shipped file itself (its keys, the type of
 * each default, and the comment above it), so the in-game settings menu and /lifesteal set
 * never fall out of step with the file. Changes are checked by loading the settings with the
 * new value: anything PluginSettings would have to replace with a default is refused.
 */
final class SettingsCatalog {

    enum Kind { FLAG, WHOLE, NUMBER, TEXT, LIST }

    /**
     * @param description the comment above the setting in config.yml, one entry per line
     * @param shipped the value config.yml ships with
     * @param needsRestart the comment marks it (restart)
     */
    record Setting(String key, Kind kind, Object shipped, List<String> description, boolean needsRestart) {

        String section() {
            int dot = key.indexOf('.');
            return dot < 0 ? key : key.substring(0, dot);
        }

        /** "newbie-shield.hours" reads as "Hours"; "vengeance.tracker.round-to-blocks" as "Tracker: round to blocks". */
        String label() {
            String rest = key.substring(key.indexOf('.') + 1);
            int dot = rest.lastIndexOf('.');
            return dot < 0 ? words(rest) : words(rest.substring(0, dot)) + ": " + words(rest.substring(dot + 1)).toLowerCase(Locale.ROOT);
        }

        boolean isSwitch() {
            return kind == Kind.FLAG && key.equals(section() + ".enabled");
        }
    }

    private final List<Setting> settings;
    private final Map<String, List<String>> sectionDescriptions = new LinkedHashMap<>();

    SettingsCatalog(Configuration shipped) {
        List<Setting> found = new ArrayList<>();
        for (String key : shipped.getKeys(true)) {
            if (shipped.isConfigurationSection(key)) continue;
            List<String> comment = comment(shipped, key);
            boolean restart = comment.stream().anyMatch(line -> line.contains("(restart)"));
            found.add(new Setting(key, kindOf(shipped.get(key)), shipped.get(key), comment.stream().map(line -> line.replace(" (restart)", "")).toList(), restart));
        }
        settings = List.copyOf(found);
        for (Setting s : settings) {
            List<String> sectionComment = comment(shipped, s.section());
            if (sectionComment.isEmpty() && s.isSwitch()) sectionComment = s.description();
            sectionDescriptions.putIfAbsent(s.section(), sectionComment);
            if (s.isSwitch()) sectionDescriptions.put(s.section(), sectionComment);
        }
    }

    List<Setting> all() {
        return settings;
    }

    /** Top-level sections in file order. */
    List<String> sections() {
        return List.copyOf(sectionDescriptions.keySet());
    }

    List<Setting> in(String section) {
        return settings.stream().filter(s -> s.section().equals(section)).toList();
    }

    List<String> describe(String section) {
        return sectionDescriptions.getOrDefault(section, List.of());
    }

    Setting find(String key) {
        return settings.stream().filter(s -> s.key().equals(key)).findFirst().orElse(null);
    }

    /** "newbie-shield" reads as "Newbie shield". */
    static String words(String key) {
        String spaced = key.replace('-', ' ');
        return spaced.isEmpty() ? spaced : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** What a player typed, as the value to store. Throws IllegalArgumentException if it can't be one. */
    static Object parse(Setting setting, String input) {
        String text = input.strip();
        return switch (setting.kind()) {
            case FLAG -> switch (text.toLowerCase(Locale.ROOT)) {
                case "true", "on", "yes", "enable", "enabled" -> true;
                case "false", "off", "no", "disable", "disabled" -> false;
                default -> throw new IllegalArgumentException("on or off");
            };
            case WHOLE, NUMBER -> {
                try {
                    BigDecimal number = new BigDecimal(text);
                    yield setting.kind() == Kind.WHOLE && number.stripTrailingZeros().scale() <= 0
                            ? (Object) number.intValueExact() : (Object) number.doubleValue();
                } catch (NumberFormatException | ArithmeticException e) {
                    throw new IllegalArgumentException("a number");
                }
            }
            case LIST -> {
                String inside = text.replace("[", "").replace("]", "").strip();
                List<Integer> values = new ArrayList<>();
                if (inside.isEmpty() || inside.equalsIgnoreCase("none")) yield values;
                for (String part : inside.split("[,\\s]+")) {
                    try {
                        values.add(Integer.parseInt(part));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("whole numbers like 15, 20, 25 (or none)");
                    }
                }
                yield values;
            }
            case TEXT -> {
                if (text.isEmpty()) throw new IllegalArgumentException("some text");
                yield text;
            }
        };
    }

    /** One click up or down: 1 for whole numbers, otherwise 0.1 (0.01 for settings that ship below 1). */
    static Object step(Setting setting, Object current, int direction) {
        if (setting.kind() == Kind.WHOLE) {
            return Math.max(0, (current instanceof Number n ? n.intValue() : 0) + direction);
        }
        double value = current instanceof Number n ? n.doubleValue() : 0;
        double size = setting.shipped() instanceof Number n && n.doubleValue() < 1 ? 0.01 : 0.1;
        return Math.max(0, BigDecimal.valueOf(value + direction * size).setScale(4, RoundingMode.HALF_UP).doubleValue());
    }

    /** A value the way players read it: on/off, "none" for an empty list, no stray ".0". */
    static String show(Object value) {
        if (value instanceof Boolean b) return b ? "on" : "off";
        if (value instanceof List<?> list) return list.isEmpty() ? "none" : list.toString();
        if (value instanceof Double d) return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        return String.valueOf(value);
    }

    /**
     * Why {@code value} can't be used for {@code key}, or null if it can. Loads the settings
     * with the change made and refuses it if that leaves any new warning.
     */
    static String problem(FileConfiguration config, String key, Object value) {
        YamlConfiguration changed = new YamlConfiguration();
        try {
            changed.loadFromString(config.saveToString());
        } catch (InvalidConfigurationException e) {
            return "config.yml couldn't be read: " + e.getMessage();
        }
        changed.set(key, value);
        List<String> before = PluginSettings.load(config).warnings();
        // Warnings end with what the plugin would use instead ("; using 20."), which doesn't apply to a refusal.
        return PluginSettings.load(changed).warnings().stream()
                .filter(w -> !before.contains(w))
                .map(w -> w.lastIndexOf("; ") > 0 ? w.substring(0, w.lastIndexOf("; ")) + "." : w)
                .findFirst().orElse(null);
    }

    private static Kind kindOf(Object value) {
        if (value instanceof Boolean) return Kind.FLAG;
        if (value instanceof Integer || value instanceof Long) return Kind.WHOLE;
        if (value instanceof Number) return Kind.NUMBER;
        if (value instanceof List<?>) return Kind.LIST;
        return Kind.TEXT;
    }

    private static List<String> comment(ConfigurationSection config, String key) {
        return config.getComments(key).stream().filter(Objects::nonNull).map(String::strip).filter(line -> !line.isEmpty()).toList();
    }
}
