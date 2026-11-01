package dev.lifestealplus.purgatory;

import java.util.List;
import org.bukkit.entity.Husk;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Stray;
import org.bukkit.entity.Vindicator;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.entity.Zombie;

/**
 * What each trial throws at players, in order. Counts are for one player and grow
 * with more participants and with the configured difficulty. The last wave is led by
 * a single named boss.
 */
final class Waves {

    record Group(Class<? extends Mob> type, int count) {
    }

    private static final List<List<Group>> WAVES = List.of(
            List.of(new Group(Zombie.class, 4)),
            List.of(new Group(Zombie.class, 3), new Group(Skeleton.class, 2)),
            List.of(new Group(Spider.class, 3), new Group(Husk.class, 2), new Group(Skeleton.class, 2)),
            List.of(new Group(Vindicator.class, 2), new Group(Stray.class, 2), new Group(Zombie.class, 2)),
            List.of(new Group(WitherSkeleton.class, 1), new Group(Skeleton.class, 2)));

    static final int COUNT = WAVES.size();

    /** The boss is the single wither skeleton in the final wave. */
    static final String BOSS_NAME = "The Gatekeeper";
    static final double BOSS_HEALTH = 60;

    private Waves() {
    }

    /** 1-based. */
    static List<Group> wave(int number) {
        return WAVES.get(number - 1);
    }

    static boolean isFinal(int number) {
        return number == COUNT;
    }

    /** More players, more enemies: +50% per extra participant, then scaled by difficulty. The boss stays single. */
    static int scaledCount(Group group, int players, double difficulty) {
        if (group.type() == WitherSkeleton.class) return 1;
        return Math.max(1, (int) Math.round(group.count() * (1 + 0.5 * (players - 1)) * difficulty));
    }

    static double bossHealth(int players, double difficulty) {
        return BOSS_HEALTH * (1 + 0.5 * (players - 1)) * difficulty;
    }
}
