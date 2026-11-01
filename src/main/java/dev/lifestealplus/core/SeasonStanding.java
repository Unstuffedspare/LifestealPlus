package dev.lifestealplus.core;

import java.util.UUID;

/**
 * One line of an archived season's final leaderboard. {@code kills} and {@code deaths} only
 * count the ones that moved hearts; {@code eliminations} is how often they hit zero.
 */
public record SeasonStanding(int rank, UUID uuid, String name, int hearts, int kills, int deaths, int eliminations) {
}
