package dev.lifestealplus.core;

import java.util.Map;

/**
 * The heart economy at a glance: who's in, how many hearts exist, and where hearts came from
 * and went over a recent window.
 *
 * @param heartsInPlay hearts held by players who are still in
 * @param inBounties hearts held in open bounties (taken from their placers, not yet paid out)
 * @param gained hearts gained over the window, by cause, biggest first
 * @param lost hearts lost over the window, by cause, biggest first
 */
public record EconomyReport(int alive, int eliminated, int heartsInPlay, int inBounties,
                            Map<HeartCause, Integer> gained, Map<HeartCause, Integer> lost) {

    /** Hearts that came into being over the window, minus those that left it: above 0 means inflation. */
    public int net() {
        return gained.values().stream().mapToInt(Integer::intValue).sum() - lost.values().stream().mapToInt(Integer::intValue).sum();
    }
}
