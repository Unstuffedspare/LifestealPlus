package dev.lifestealplus;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Location;

/**
 * WorldGuard region flags for lifesteal. Only touched when WorldGuard is installed: this is
 * the one class that refers to it. Flags have to be registered while plugins load, before
 * WorldGuard reads its regions.
 *
 * <pre>
 * /rg flag spawn lifesteal deny               nothing lifesteal happens in spawn
 * /rg flag parkour lifesteal-natural-loss deny  falling here costs no hearts
 * /rg flag market lifesteal-combat-tag deny     hitting someone here doesn't tag them
 * </pre>
 */
final class WorldGuardRules implements Zones.RegionRules {

    private static final Map<Zones.Rule, String> NAMES = Map.of(
            Zones.Rule.LIFESTEAL, "lifesteal",
            Zones.Rule.NATURAL_LOSS, "lifesteal-natural-loss",
            Zones.Rule.COMBAT_TAG, "lifesteal-combat-tag");

    private final Map<Zones.Rule, StateFlag> flags;

    private WorldGuardRules(Map<Zones.Rule, StateFlag> flags) {
        this.flags = flags;
    }

    /** Registers the flags (or reuses ones a reload left behind); null if WorldGuard refused. */
    static WorldGuardRules register(Logger log) {
        FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
        Map<Zones.Rule, StateFlag> flags = new EnumMap<>(Zones.Rule.class);
        for (Map.Entry<Zones.Rule, String> entry : NAMES.entrySet()) {
            StateFlag flag = new StateFlag(entry.getValue(), true);
            try {
                registry.register(flag);
            } catch (FlagConflictException | IllegalStateException e) {
                Flag<?> existing = registry.get(entry.getValue());
                if (!(existing instanceof StateFlag state)) {
                    log.warning("Couldn't add the WorldGuard flag " + entry.getValue() + " (" + e.getMessage()
                            + "); regions won't affect lifesteal.");
                    return null;
                }
                flag = state;
            }
            flags.put(entry.getKey(), flag);
        }
        return new WorldGuardRules(flags);
    }

    @Override
    public boolean allows(Location where, Zones.Rule rule) {
        StateFlag.State state = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
                .queryState(BukkitAdapter.adapt(where), (RegionAssociable) null, flags.get(rule));
        return state != StateFlag.State.DENY;
    }
}
