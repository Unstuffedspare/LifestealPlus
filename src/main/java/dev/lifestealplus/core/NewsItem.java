package dev.lifestealplus.core;

import java.time.Instant;

/**
 * Something that happened on the server, kept so players who were offline can catch up.
 * {@code kind} names the message (news.&lt;kind&gt; in messages.yml); the rest fill it in and
 * may be null or zero.
 */
public record NewsItem(Instant at, String kind, String player, String other, int amount) {

    public static final String ELIMINATED = "eliminated";
    public static final String ELIMINATED_BY = "eliminated-by";
    public static final String REVIVED = "revived";
    public static final String STREAK = "streak";
    public static final String STREAK_ENDED = "streak-ended";
    public static final String BOUNTY = "bounty";
    public static final String BOUNTY_CUT = "bounty-cut";
    public static final String BOUNTY_WITHDRAWN = "bounty-withdrawn";
    public static final String BLOOD_MOON = "blood-moon";
    public static final String AIRDROP = "airdrop";
    public static final String FINALE = "finale";
    public static final String FINALE_WINNER = "finale-winner";
}
