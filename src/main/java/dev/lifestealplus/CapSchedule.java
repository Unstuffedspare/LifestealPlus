package dev.lifestealplus;

import dev.lifestealplus.core.HeartRules;
import dev.lifestealplus.core.HeartService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.Sound;

/**
 * The rising heart cap: once a minute, works out which week of the season it is (from when
 * the season started, saved in the database) and sets that week's cap. Announces each rise.
 */
public final class CapSchedule {

    public static final String SEASON_STARTED = "season.started";

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private Integer announced;

    CapSchedule(LifestealPlugin plugin, HeartService hearts) {
        this.plugin = plugin;
        this.hearts = hearts;
        plugin.tasks().timer(this::refresh, 20, 20 * 60);
    }

    /** Also called after a reload or a season reset, so a changed schedule applies straight away. */
    void refresh() {
        List<Integer> schedule = plugin.settings().social().capSchedule();
        if (schedule.isEmpty()) {
            hearts.setCapOverride(null);
            announced = null;
            return;
        }
        hearts.state(SEASON_STARTED).thenCompose(saved -> {
            if (saved.isPresent()) return java.util.concurrent.CompletableFuture.completedFuture(Instant.ofEpochMilli(Long.parseLong(saved.get())));
            Instant now = Instant.now();
            return hearts.putState(SEASON_STARTED, String.valueOf(now.toEpochMilli())).thenApply(ignored -> now);
        }).whenComplete((started, error) -> plugin.tasks().now(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Couldn't work out the season's start for the heart cap", error);
                return;
            }
            long week = Duration.between(started, Instant.now()).toDays() / 7;
            int cap = HeartRules.capForWeek(schedule, plugin.settings().rules().maxHearts(), week);
            hearts.setCapOverride(cap);
            if (announced != null && cap > announced) {
                Msg.broadcast("hearts.cap-risen", Msg.t("cap", cap), Msg.t("week", week + 1));
                plugin.getServer().getOnlinePlayers().forEach(p -> p.playSound(p, Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.2f));
            }
            announced = cap;
        }));
    }

    /** The new season counts weeks from its start, which the season reset saved along with everything else. */
    void seasonStarted() {
        announced = null;
        refresh();
    }
}
