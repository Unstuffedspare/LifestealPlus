package dev.lifestealplus;

import java.time.Duration;
import java.util.List;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Settings for the features that let players trade, rescue and bind themselves to each other:
 * withdrawing and decaying hearts, the rising cap, rifts, revival pledges, Blood Pacts and ghosts.
 *
 * @param capSchedule the cap for each week of the season in turn; empty means the fixed hearts.max
 * @param decayDays days until a Heart item has faded to nothing, losing an equal share each day; 0 means never
 */
public record SocialSettings(List<Integer> capSchedule, int decayDays, int riftCost, int pledgeHeartsNeeded,
                             int pledgeMinVoters, Duration pactBreakCooldown, Duration whisperCooldown,
                             boolean limitGhostChat) {

    static SocialSettings from(FileConfiguration config, int startingHearts, int reviveHearts) {
        List<Integer> schedule = config.getIntegerList("hearts.max-by-week");
        for (int cap : schedule) {
            if (cap < Math.max(startingHearts, reviveHearts)) {
                throw new IllegalArgumentException("hearts.max-by-week values can't be below hearts.starting or hearts.revive");
            }
        }
        int decay = config.getInt("heart-items.decay-days", 4);
        if (decay < 0) throw new IllegalArgumentException("heart-items.decay-days can't be negative");
        int riftCost = config.getInt("purgatory.rift-cost", 6);
        if (riftCost < 1) throw new IllegalArgumentException("purgatory.rift-cost must be at least 1");
        double cooldownHours = config.getDouble("blood-pact.break-cooldown-hours", 24);
        double whisperMinutes = config.getDouble("ghosts.whisper-minutes", 60);
        if (cooldownHours < 0 || whisperMinutes < 0) throw new IllegalArgumentException("cooldowns can't be negative");
        return new SocialSettings(
                List.copyOf(schedule),
                decay,
                riftCost,
                Math.max(1, config.getInt("revival-vote.hearts-needed", 6)),
                Math.max(1, config.getInt("revival-vote.min-players", 3)),
                Duration.ofMinutes(Math.round(cooldownHours * 60)),
                Duration.ofSeconds(Math.round(whisperMinutes * 60)),
                config.getBoolean("ghosts.limit-chat", true));
    }
}
