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
 */
public record KillOutcome(HeartChange victim, HeartChange killer, boolean repeat, boolean vengeance, boolean marked,
                          BountyClaim bounty, boolean shielded) {

    public List<HeartChange> changes() {
        List<HeartChange> changes = new ArrayList<>(List.of(victim, killer));
        if (bounty != null) changes.add(bounty.change());
        return changes;
    }
}
