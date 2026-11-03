package dev.lifestealplus;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Where code runs. On Paper everything below is the main thread, as it always was. On Folia,
 * which has no main thread, server-wide work goes to the global region, and anything that
 * changes a player, an entity or a block goes to the thread that owns it.
 */
public final class Tasks {

    /** A task that can be stopped. */
    public interface Handle {
        void cancel();
    }

    private static final boolean FOLIA = classExists("io.papermc.paper.threadedregions.RegionizedServer");

    private final Plugin plugin;
    private final Executor global;

    public Tasks(Plugin plugin) {
        this.plugin = plugin;
        this.global = task -> {
            if (plugin.isEnabled()) Bukkit.getGlobalRegionScheduler().execute(plugin, task);
        };
    }

    public static boolean folia() {
        return FOLIA;
    }

    /** For CompletableFuture callbacks that do server-wide things (messages, events, state). */
    public Executor global() {
        return global;
    }

    /** Server-wide, as soon as possible. */
    public void now(Runnable task) {
        global.execute(task);
    }

    public Handle later(Runnable task, long delayTicks) {
        ScheduledTask scheduled = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), Math.max(1, delayTicks));
        return scheduled::cancel;
    }

    public Handle timer(Runnable task, long delayTicks, long periodTicks) {
        ScheduledTask scheduled = Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(plugin, t -> task.run(), Math.max(1, delayTicks), Math.max(1, periodTicks));
        return scheduled::cancel;
    }

    /** Off the game's threads entirely, repeating (network calls). */
    public Handle asyncTimer(Runnable task, long delayTicks, long periodTicks) {
        ScheduledTask scheduled = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> task.run(),
                Math.max(1, delayTicks) * 50, Math.max(1, periodTicks) * 50, TimeUnit.MILLISECONDS);
        return scheduled::cancel;
    }

    /** On the thread that owns the entity (a player): now if this is it, else as soon as possible. Skipped if it's gone. */
    public void forEntity(Entity entity, Runnable task) {
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            task.run();
        } else if (plugin.isEnabled()) {
            entity.getScheduler().run(plugin, t -> task.run(), null);
        }
    }

    public Handle forEntityLater(Entity entity, Runnable task, long delayTicks) {
        ScheduledTask scheduled = entity.getScheduler().runDelayed(plugin, t -> task.run(), null, Math.max(1, delayTicks));
        return scheduled == null ? () -> { } : scheduled::cancel;
    }

    /** On the thread that owns that block. */
    public void at(Location where, Runnable task) {
        if (Bukkit.isOwnedByCurrentRegion(where)) {
            task.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getRegionScheduler().execute(plugin, where, task);
        }
    }

    public Handle atLater(Location where, Runnable task, long delayTicks) {
        ScheduledTask scheduled = Bukkit.getRegionScheduler().runDelayed(plugin, where, t -> task.run(), Math.max(1, delayTicks));
        return scheduled::cancel;
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
