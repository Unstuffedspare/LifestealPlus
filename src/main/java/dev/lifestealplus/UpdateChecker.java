package dev.lifestealplus;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Looks for a newer release on GitHub when the server starts and twice a day after, and tells
 * admins when they join. Only the public release list is fetched; nothing about the server is sent.
 */
final class UpdateChecker implements Listener {

    static final String REPOSITORY = "Unstuffedspare/LifestealPlus";
    private static final long TWELVE_HOURS = 20L * 60 * 60 * 12;

    /** A newer release: its version, where to get it, and the first lines of what changed. */
    record Release(String version, String url, String notes) {
    }

    private final LifestealPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile Release newer;

    UpdateChecker(LifestealPlugin plugin) {
        this.plugin = plugin;
        plugin.tasks().asyncTimer(this::check, 20 * 30, TWELVE_HOURS);
    }

    private void check() {
        if (!plugin.settings().extras().updateCheck()) return;
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + REPOSITORY + "/releases/latest"))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "LifestealPlus/" + plugin.getPluginMeta().getVersion())
                .GET().build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
            // 404: no releases published yet. Anything else unusual: try again next time, quietly.
            if (response.statusCode() != 200) return;
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            String tag = json.get("tag_name").getAsString();
            if (!isNewer(tag, plugin.getPluginMeta().getVersion())) return;
            String body = json.has("body") && !json.get("body").isJsonNull() ? json.get("body").getAsString() : "";
            String notes = String.join(" / ", Arrays.stream(body.split("\\R")).map(String::strip)
                    .filter(line -> !line.isEmpty()).limit(3).toList());
            Release release = new Release(tag.replaceFirst("^[vV]", ""), json.get("html_url").getAsString(), notes);
            if (newer == null || !newer.version().equals(release.version())) {
                plugin.getLogger().info("LifestealPlus " + release.version() + " is out (this is "
                        + plugin.getPluginMeta().getVersion() + "): " + release.url()
                        + (notes.isEmpty() ? "" : " - " + notes));
            }
            newer = release;
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.FINE, "Couldn't check for updates", error);
            return null;
        });
    }

    @EventHandler
    void onJoin(PlayerJoinEvent event) {
        Release release = newer;
        Player player = event.getPlayer();
        if (release == null || !plugin.settings().extras().updateCheck() || !player.hasPermission(AdminPermissions.SETTINGS)) return;
        Msg.send(player, "update.available", Msg.t("version", release.version()),
                Msg.t("current", plugin.getPluginMeta().getVersion()), Msg.t("url", release.url()));
    }

    /** "v1.2.0" against "1.1.9": compared number by number; a suffix like -beta counts as older. */
    static boolean isNewer(String candidate, String current) {
        int[] a = numbers(candidate), b = numbers(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return !candidate.contains("-") && current.contains("-");
    }

    private static int[] numbers(String version) {
        String plain = version.replaceFirst("^[vV]", "").split("-")[0];
        return Arrays.stream(plain.split("\\.")).mapToInt(part -> {
            try {
                return Integer.parseInt(part.replaceAll("\\D", ""));
            } catch (NumberFormatException e) {
                return 0;
            }
        }).toArray();
    }
}
