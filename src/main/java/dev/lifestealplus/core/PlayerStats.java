package dev.lifestealplus.core;

/**
 * A player's record for one season, worked out from the heart ledger.
 *
 * @param heartsTaken hearts won from kills (vengeance bonuses included)
 * @param heartsLost hearts lost to deaths of any kind
 * @param bountyHearts hearts collected from bounties
 * @param comebacks times they came back after being eliminated
 * @param bestStreak longest kill streak, or -1 where it wasn't kept (seasons archived before streaks existed)
 */
public record PlayerStats(String name, String season, int kills, int deaths, int heartsTaken, int heartsLost,
                          int bountyHearts, int eliminations, int comebacks, int bestStreak) {
}
