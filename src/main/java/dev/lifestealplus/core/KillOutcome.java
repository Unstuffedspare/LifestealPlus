package dev.lifestealplus.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything a PvP kill did.
 *
 * @param repeat the killer already took a heart from this victim recently, so no hearts moved
 * @param vengeance the victim had taken the killer's heart within the vengeance window,
 *     so the killer took it back plus the bonus
 * @param marked this kill marked the killer: the victim can now take vengeance
 * @param bounty what the killer collected from bounties on the victim, or null if there were none
 * @param shielded one of them still had a newbie shield, so nothing moved and no bounty paid
 * @param linked the two accounts have logged in from the same address; the kill was flagged for admins
 * @param killerStreak the killer's streak after this kill (0 when streaks are off)
 * @param endedStreak the victim's streak this death ended, if it was long enough to be news; otherwise 0
 * @param shutdown ending that streak took extra hearts from the victim
 */
public record KillOutcome(HeartChange victim, HeartChange killer, boolean repeat, boolean vengeance, boolean marked,
                          BountyClaim bounty, boolean shielded, boolean linked, int killerStreak, int endedStreak,
                          boolean shutdown) {

    public List<HeartChange> changes() {
        List<HeartChange> changes = new ArrayList<>(List.of(victim, killer));
        if (bounty != null) changes.add(bounty.change());
        return changes;
    }
}
