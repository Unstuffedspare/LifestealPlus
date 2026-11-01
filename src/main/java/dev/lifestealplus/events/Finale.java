package dev.lifestealplus.events;

import dev.lifestealplus.LifestealPlugin;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.event.PlayerEliminatedEvent;
import java.time.Duration;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
        hearts.state(ACTIVE).whenComplete((saved, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
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
        border.setCenter(spawn.getX(), spawn.getZ());
        border.setDamageBuffer(0);
        border.setDamageAmount(plugin.settings().events().finaleDamagePerBlock());
        border.setWarningDistance(16);
        // Start from something the players can see shrinking, not the default 60-million-block border.
        double start = Math.min(border.getSize(), farthestPlayer(spawn) * 2 + 64);
        border.setSize(Math.max(start, finalSize));
        border.changeSize(finalSize, Math.max(1, over.toSeconds() * 20));

        for (Player p : plugin.getServer().getOnlinePlayers()) {
            p.showTitle(Title.title(Component.text("The Finale", NamedTextColor.DARK_RED),
                    Component.text("The border is closing. Last one standing wins.", NamedTextColor.RED)));
            p.playSound(p, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 0.8f);
        }
        broadcast("<dark_red><bold>The Finale has begun.</bold></dark_red> <red>Over the next <minutes> minutes the border "
                        + "closes to <size> blocks around spawn. Purgatory is sealed: nobody comes back now.",
                Placeholder.unparsed("minutes", String.valueOf(over.toMinutes())),
                Placeholder.unparsed("size", String.valueOf((int) finalSize)));
        evacuateOtherDimensions();
        return true;
    }

    /** Puts the border back the way it was. False if no finale was running. */
    public boolean stop() {
        if (!isActive()) return false;
        WorldBorder border = overworld().getWorldBorder();
        border.setSize(previousSize);
        previousSize = null;
        hearts.clearState(ACTIVE);
        broadcast("<gray>The finale has been called off. The border is back and Purgatory is open again.");
        return true;
    }

    /** Players in the Nether or End get a minute to come back on their own, then are brought to spawn. */
    private void evacuateOtherDimensions() {
        String purgatory = plugin.settings().purgatory().world();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (outside(p, purgatory)) {
                p.sendRichMessage("<red>The finale is in the overworld. You have 60 seconds to come back before you're pulled to spawn.");
            }
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!isActive()) return;
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (outside(p, purgatory)) {
                    p.teleport(overworld().getSpawnLocation());
                    p.sendRichMessage("<red>You've been brought back for the finale.");
                }
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
        event.getPlayer().sendRichMessage("<red>The portals are closed for the finale.");
    }

    /** Every elimination might be the last one. */
    @EventHandler
    void onEliminated(PlayerEliminatedEvent event) {
        if (!isActive() || crowned) return;
        hearts.lastStanding().whenComplete((last, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Couldn't check for a finale winner", error);
                return;
            }
            if (last.isEmpty() || crowned) return;
            crowned = true;
            String name = last.get().name();
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                p.showTitle(Title.title(Component.text(name, NamedTextColor.GOLD),
                        Component.text("wins the season", NamedTextColor.YELLOW),
                        Title.Times.times(Duration.ofSeconds(1), Duration.ofSeconds(8), Duration.ofSeconds(2))));
                p.playSound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
            broadcast("<gold><bold>👑 <player> is the last one standing and wins the season!",
                    Placeholder.unparsed("player", name));
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

    private void broadcast(String message, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... tags) {
        plugin.getServer().broadcast(MiniMessage.miniMessage().deserialize(message, tags));
    }
}
