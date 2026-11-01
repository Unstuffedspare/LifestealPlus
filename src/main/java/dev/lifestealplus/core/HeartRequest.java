package dev.lifestealplus.core;

import java.util.UUID;

/**
 * One requested change to one player's hearts. {@code otherUuid}/{@code otherName}
 * name the other party (the killer, the victim); {@code actor} names whoever issued
 * it (an admin, or "server").
 */
public record HeartRequest(
        UUID player,
        Op op,
        int amount,
        HeartCause cause,
        UUID otherUuid,
        String otherName,
        String actor,
        String note) {

    public enum Op { ADD, SET }

    public static HeartRequest add(UUID player, int amount, HeartCause cause, String actor) {
        return new HeartRequest(player, Op.ADD, amount, cause, null, null, actor, null);
    }

    public static HeartRequest set(UUID player, int hearts, HeartCause cause, String actor) {
        return new HeartRequest(player, Op.SET, hearts, cause, null, null, actor, null);
    }

    public HeartRequest withOther(UUID uuid, String name) {
        return new HeartRequest(player, op, amount, cause, uuid, name, actor, note);
    }

    public HeartRequest withNote(String note) {
        return new HeartRequest(player, op, amount, cause, otherUuid, otherName, actor, note);
    }
}
