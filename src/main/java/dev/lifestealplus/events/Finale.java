package dev.lifestealplus.events;

import dev.lifestealplus.LifestealPlugin;
import dev.lifestealplus.Msg;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.NewsItem;
import dev.lifestealplus.event.PlayerEliminatedEvent;
import java.time.Duration;
import java.util.logging.Level;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;

/**
 * The end of a season: the overworld border closes in on spawn, nobody can hide in the
 * Nether or End, Purgatory stops sending anyone back, and the last player with hearts wins.
 * The shrinking border is saved by the game itself; the rest is saved here, so a restart
 * mid-finale carries on.
 */
public final class Finale implements Listener {

    private static final String ACTIVE = "finale.previous_size";
    private static final Duration EVACUATION_WARNING = Duration.ofSeconds(60);

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    /** The border size before the finale, to put back if it's stopped; null when no finale is running. */
    private Double previousSize;
    private boolean crowned;

    public Finale(LifestealPlugin plugin, HeartService hearts) {
        this.plugin = plugin;
        this.hearts = hearts;
        hearts.state(ACTIVE).whenComplete((saved, error) -> plugin.tasks().now(() -> {
            if (error != null) plugin.getLogger().log(Level.SEVERE, "Couldn't load finale state", error);
            else saved.ifPresent(size -> previousSize = Double.parseDouble(size));
        }));
    }

    public boolean isActive() {
        return previousSize != null;
    }

    /** False if one is already running. */
    public boolean start(Duration over, double finalSize) {
        if (isActive()) return false;
        World world = overworld();
        WorldBorder border = world.getWorldBorder();
        previousSize = border.getSize();
        crowned = false;
        hearts.putState(ACTIVE, String.valueOf(previousSize));

        Location spawn = world.getSpawnLocation();
        // Start from something the players can see shrinking, not the default 60-million-block border.
        double start = Math.min(border.getSize(), farthestPlayer(spawn) * 2 + 64);
        // The border belongs to the global region on Folia (on Paper this is the main thread either way).
        plugin.tasks().now(() -> {
            border.setCenter(spawn.getX(), spawn.getZ());
            border.setDamageBuffer(0);
            border.setDamageAmount(plugin.settings().events().finaleDamagePerBlock());
            border.setWarningDistance(16);
            border.setSize(Math.max(start, finalSize));
            border.changeSize(finalSize, Math.max(1, over.toSeconds() * 20));
        });

        for (Player p : plugin.getServer().getOnlinePlayers()) {
            p.showTitle(Title.title(Msg.get("finale.title"), Msg.get("finale.subtitle")));
            p.playSound(p, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 0.8f);
        }
        Msg.broadcast("finale.started", Msg.t("time", Msg.duration(over)), Msg.t("size", (int) finalSize));
        hearts.addNews(NewsItem.FINALE, null, null, 0);
        evacuateOtherDimensions();
        return true;
    }

    /** Puts the border back the way it was. False if no finale was running. */
    public boolean stop() {
        if (!isActive()) return false;
        WorldBorder border = overworld().getWorldBorder();
        double size = previousSize;
        plugin.tasks().now(() -> border.setSize(size));
        previousSize = null;
        hearts.clearState(ACTIVE);
        Msg.broadcast("finale.stopped");
        return true;
    }

    /** Players in the Nether or End get a minute to come back on their own, then are brought to spawn. */
    private void evacuateOtherDimensions() {
        String purgatory = plugin.settings().purgatory().world();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (outside(p, purgatory)) {
                Msg.send(p, "finale.come-back", Msg.t("time", Msg.duration(EVACUATION_WARNING)));
            }
        }
        plugin.tasks().later(() -> {
            if (!isActive()) return;
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                plugin.tasks().forEntity(p, () -> {
                    if (!outside(p, purgatory)) return;
                    p.teleportAsync(overworld().getSpawnLocation());
                    Msg.send(p, "finale.brought-back");
                });
            }
        }, EVACUATION_WARNING.toSeconds() * 20);
    }

    private boolean outside(Player p, String purgatoryWorld) {
        World w = p.getWorld();
        return !w.equals(overworld()) && !w.getName().equals(purgatoryWorld);
    }

    @EventHandler(ignoreCancelled = true)
    void onPortal(PlayerPortalEvent event) {
        if (!isActive() || event.getTo().getWorld() == null || event.getTo().getWorld().equals(overworld())) return;
        event.setCancelled(true);
        Msg.send(event.getPlayer(), "finale.portals-closed");
    }

    /** Every elimination might be the last one. */
    @EventHandler
    void onEliminated(PlayerEliminatedEvent event) {
        if (!isActive() || crowned) return;
        hearts.lastStanding().whenComplete((last, error) -> plugin.tasks().now(() -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Couldn't check for a finale winner", error);
                return;
            }
            if (last.isEmpty() || crowned) return;
            crowned = true;
            String name = last.get().name();
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                p.showTitle(Title.title(Msg.get("finale.winner-title", Msg.t("player", name)),
                        Msg.get("finale.winner-subtitle", Msg.t("player", name)),
                        Title.Times.times(Duration.ofSeconds(1), Duration.ofSeconds(8), Duration.ofSeconds(2))));
                p.playSound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
            Msg.broadcast("finale.winner", Msg.t("player", name));
            hearts.addNews(NewsItem.FINALE_WINNER, name, null, 0);
        }));
    }

    private double farthestPlayer(Location spawn) {
        double farthest = 0;
        for (Player p : spawn.getWorld().getPlayers()) {
            farthest = Math.max(farthest, Math.max(Math.abs(p.getX() - spawn.getX()), Math.abs(p.getZ() - spawn.getZ())));
        }
        return farthest;
    }

    private World overworld() {
        return plugin.getServer().getWorlds().getFirst();
    }

}
