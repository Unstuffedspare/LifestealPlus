package dev.lifestealplus;

import org.bukkit.Location;

/**
 * Where lifesteal applies: everywhere except the worlds in worlds.excluded and, with
 * WorldGuard installed, regions whose flags say otherwise.
 */
public final class Zones {

    /** What a place can switch off. */
    public enum Rule {
        /** Hearts move at all here (the WorldGuard flag "lifesteal"). Off also switches off the other two. */
        LIFESTEAL,
        /** Deaths to anything but a player cost hearts here ("lifesteal-natural-loss"). */
        NATURAL_LOSS,
        /** Fighting here puts players in combat ("lifesteal-combat-tag"). */
        COMBAT_TAG
    }

    /** Answers for WorldGuard regions; absent without WorldGuard. */
    interface RegionRules {
        boolean allows(Location where, Rule rule);
    }

    private final LifestealPlugin plugin;
    private final RegionRules regions;

    Zones(LifestealPlugin plugin, RegionRules regions) {
        this.plugin = plugin;
        this.regions = regions;
    }

    public boolean allows(Location where, Rule rule) {
        if (where.getWorld() == null || !plugin.settings().lifestealIn(where.getWorld())) return false;
        if (regions == null) return true;
        if (!regions.allows(where, Rule.LIFESTEAL)) return false;
        return rule == Rule.LIFESTEAL || regions.allows(where, rule);
    }

    /** Whether WorldGuard regions are being checked. */
    public boolean usesRegions() {
        return regions != null;
    }
}
