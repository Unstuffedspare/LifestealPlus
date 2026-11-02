package dev.lifestealplus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings for the features that let players trade, rescue and bind themselves to each other:
 * withdrawing and decaying hearts, the rising cap, rifts, revival pledges, Blood Pacts and ghosts.
 *
 * @param capSchedule the cap for each week of the season in turn; empty means the fixed hearts.max
 * @param decayDays days until a Heart item has faded to nothing, losing an equal share each day; 0 means never
 */
public record SocialSettings(List<Integer> capSchedule, int decayDays, boolean withdrawEnabled,
                             boolean riftsEnabled, int riftCost,
                             boolean pledgesEnabled, int pledgeHeartsNeeded, int pledgeMinVoters,
                             boolean pactsEnabled, Duration pactBreakCooldown,
                             boolean ghostsEnabled, boolean whispersEnabled, Duration whisperCooldown,
                             boolean limitGhostChat) {

    static SocialSettings from(ConfigReader in, int startingHearts, int reviveHearts) {
        List<Integer> schedule = new ArrayList<>();
        for (int cap : in.wholes("hearts.max-by-week")) {
            if (cap < Math.max(startingHearts, reviveHearts)) {
                in.warn("hearts.max-by-week has " + cap + ", which is below hearts.starting or hearts.revive; skipping it.");
            } else {
                schedule.add(cap);
            }
        }
        return new SocialSettings(
                List.copyOf(schedule),
                in.flag("heart-items.decay.enabled", true) ? in.whole("heart-items.decay.days", 4, 1, 365) : 0,
                in.flag("heart-items.withdraw.enabled", true),
                in.flag("rifts.enabled", true),
                in.whole("rifts.cost", 6, 1, 1000),
                in.flag("revival-votes.enabled", true),
                in.whole("revival-votes.hearts-needed", 6, 1, 1000),
                in.whole("revival-votes.min-players", 3, 1, 1000),
                in.flag("blood-pacts.enabled", true),
                PluginSettings.hours(in.number("blood-pacts.break-cooldown-hours", 24, 0, 24 * 365)),
                in.flag("ghosts.enabled", true),
                in.flag("ghosts.whispers.enabled", true),
                PluginSettings.minutes(in.number("ghosts.whispers.every-minutes", 60, 0, 60 * 24 * 7)),
                in.flag("ghosts.limit-chat", true));
    }
}
