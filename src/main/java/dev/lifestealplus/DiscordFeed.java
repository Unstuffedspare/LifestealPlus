package dev.lifestealplus;

import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.NewsItem;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.stream.Collectors;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Posts the server's news (everything /news shows) to a Discord channel through a webhook, a
 * few seconds after it happens. Several items share a message, so Discord's rate limit is never
 * an issue, and anything that couldn't be posted (Discord down, a rate limit) is tried again.
 */
final class DiscordFeed {

    private static final int BATCH = 10;
    private static final Duration WARN_EVERY = Duration.ofMinutes(10);

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    /** The newest news row already posted (or skipped); -1 until the starting point is known. */
    private volatile long posted = -1;
    private volatile boolean busy;
    private volatile long lastWarned;

    DiscordFeed(LifestealPlugin plugin, HeartService hearts) {
        this.plugin = plugin;
        this.hearts = hearts;
        // Only news from now on: nothing that happened before the server started.
        hearts.lastNewsId().thenAccept(id -> posted = id);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 100, 100);
    }

    private void tick() {
        if (posted < 0 || busy) return;
        String url = plugin.settings().discordWebhook();
        busy = true;
        hearts.newsAfter(posted, BATCH).thenCompose(items -> {
            if (items.isEmpty()) return CompletableFuture.completedFuture(null);
            long newest = items.getLast().getKey();
            // Switched off: keep up with the news without posting it, so switching on doesn't post a backlog.
            if (url == null) {
                posted = newest;
                return CompletableFuture.completedFuture(null);
            }
            return post(url, items).thenAccept(sent -> {
                if (sent) posted = newest;
            });
        }).whenComplete((done, error) -> {
            busy = false;
            if (error != null) warn("Couldn't post news to Discord: " + error.getMessage());
        });
    }

    /** True once Discord has the message, or it refused it for good (so it isn't tried forever). */
    private CompletableFuture<Boolean> post(String url, List<Map.Entry<Long, NewsItem>> items) {
        String text = items.stream()
                .map(item -> PlainTextComponentSerializer.plainText().serialize(NewsFeed.line(item.getValue())))
                .collect(Collectors.joining("\n"));
        if (text.length() > 1990) text = text.substring(0, 1990);
        String body = "{\"content\":" + json(text) + ",\"allowed_mentions\":{\"parse\":[]}}";
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        } catch (IllegalArgumentException e) {
            warn("discord.webhook-url isn't a link Discord can use: " + e.getMessage());
            return CompletableFuture.completedFuture(true);
        }
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            int status = response.statusCode();
            if (status / 100 == 2) return true;
            // Rate limits and Discord's own trouble pass; anything else won't get better by retrying.
            boolean retry = status == 429 || status >= 500;
            warn("Discord refused the news post (HTTP " + status + ")" + (retry ? "; trying again shortly." : "; skipping it.")
                    + (status == 401 || status == 404 ? " Check discord.webhook-url." : ""));
            return !retry;
        });
    }

    private void warn(String message) {
        long now = System.currentTimeMillis();
        if (now - lastWarned < WARN_EVERY.toMillis()) return;
        lastWarned = now;
        plugin.getLogger().log(Level.WARNING, message);
    }

    static String json(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
