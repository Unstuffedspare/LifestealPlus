package dev.lifestealplus;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Every message players see, read from messages.yml. Admins can reword or recolour any of
 * them (MiniMessage format), or set one to "" to stop it being sent. Keys missing from an
 * older messages.yml are filled in from the defaults shipped in the jar.
 */
public final class Msg {

    private static volatile YamlConfiguration messages = new YamlConfiguration();
    private static volatile YamlConfiguration defaults = new YamlConfiguration();

    private Msg() {
    }

    /** Loads messages.yml (writing it out the first time). Returns the keys it had to add. */
    static List<String> load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) plugin.saveResource("messages.yml", false);
        YamlConfiguration shipped = new YamlConfiguration();
        try (var in = plugin.getResource("messages.yml")) {
            if (in != null) shipped = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't read the default messages: " + e.getMessage());
        }
        YamlConfiguration current = YamlConfiguration.loadConfiguration(file);
        List<String> added = new ArrayList<>();
        for (String key : shipped.getKeys(true)) {
            if (shipped.isConfigurationSection(key) || current.isSet(key)) continue;
            current.set(key, shipped.get(key));
            current.setComments(key, shipped.getComments(key));
            added.add(key);
        }
        if (!added.isEmpty()) {
            try {
                current.save(file);
            } catch (IOException e) {
                plugin.getLogger().warning("Couldn't add new messages to messages.yml: " + e.getMessage());
            }
        }
        defaults = shipped;
        messages = current;
        return added;
    }

    /** The raw MiniMessage text for a key; falls back to the shipped default, then to the key itself. */
    public static String raw(String key) {
        String text = messages.getString(key);
        if (text == null) text = defaults.getString(key);
        return text == null ? "<red>[missing message: " + key + "]" : text;
    }

    /** True when an admin has blanked the message to turn it off. */
    public static boolean isOff(String key) {
        return raw(key).isEmpty();
    }

    public static Component get(String key, TagResolver... tags) {
        return MiniMessage.miniMessage().deserialize(raw(key), tags);
    }

    public static void send(Audience to, String key, TagResolver... tags) {
        if (to != null && !isOff(key)) to.sendMessage(get(key, tags));
    }

    public static void broadcast(String key, TagResolver... tags) {
        if (!isOff(key)) Bukkit.getServer().broadcast(get(key, tags));
    }

    public static void actionBar(Audience to, String key, TagResolver... tags) {
        if (to != null && !isOff(key)) to.sendActionBar(get(key, tags));
    }

    /**
     * A length of time in words, using the time.* messages: "10 seconds", "1 minute",
     * "45 minutes", "2 hours", "2h 15m". Rounded up to the minute from a minute upward.
     */
    public static String duration(java.time.Duration d) {
        if (d.toSeconds() < 60) return raw("time.seconds").replace("<n>", String.valueOf(Math.max(1, d.toSeconds())));
        long minutes = Math.max(1, (d.toSeconds() + 59) / 60);
        if (minutes < 60) return raw(minutes == 1 ? "time.minute" : "time.minutes").replace("<n>", String.valueOf(minutes));
        long hours = minutes / 60, rest = minutes % 60;
        if (rest == 0) return raw(hours == 1 ? "time.hour" : "time.hours").replace("<n>", String.valueOf(hours));
        return raw("time.hours-minutes").replace("<h>", String.valueOf(hours)).replace("<m>", String.valueOf(rest));
    }

    /** A placeholder whose value is shown exactly as given (player names can't inject formatting). */
    public static TagResolver t(String name, Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    /** A placeholder whose value is itself formatted text. */
    public static TagResolver c(String name, Component value) {
        return Placeholder.component(name, value);
    }
}
