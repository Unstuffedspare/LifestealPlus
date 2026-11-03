package dev.lifestealplus;

import dev.lifestealplus.core.HeartCause;
import dev.lifestealplus.core.HeartRequest;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.event.PlayerRevivedEvent;
import io.papermc.paper.ban.BanListType;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.BanEntry;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;

/**
 * eliminations.ban: eliminated players are banned for a while instead of going to Purgatory or
 * spectating, and come back with hearts.revive hearts once the ban ends. Wraps the usual
 * handler and hands everything to it while the setting is off, so switching it needs no restart.
 */
final class BanElimination implements EliminationHandler, Listener {

    /** The ban list's "source" for bans this plugin made, so it only ever lifts its own. */
    static final String SOURCE = "LifestealPlus";

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartTracker tracker;
    private final EliminationHandler usual;
    /** Set on players this plugin banned, so their next join is a return rather than a new elimination. */
    private final NamespacedKey bannedTag;
    /** Players whose return is being saved, so a second join check doesn't revive them twice. */
    private final Set<UUID> returning = ConcurrentHashMap.newKeySet();

    BanElimination(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker, EliminationHandler usual) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
        this.usual = usual;
        this.bannedTag = new NamespacedKey(plugin, "banned");
    }

    private boolean on() {
        return plugin.settings().bans().enabled();
    }

    private boolean wasBanned(Player player) {
        return player.getPersistentDataContainer().has(bannedTag, PersistentDataType.BOOLEAN);
    }

    @Override
    public void eliminate(Player player) {
        if (on()) ban(player);
        else usual.eliminate(player);
    }

    @Override
    public void enforce(Player player) {
        // Here while still eliminated: the ban ran out or an admin lifted it.
        if (wasBanned(player)) comeBack(player);
        else if (on()) ban(player);
        else usual.enforce(player);
    }

    @Override
    public void restore(Player player, int hearts) {
        if (!wasBanned(player)) {
            usual.restore(player, hearts);
            return;
        }
        player.getPersistentDataContainer().remove(bannedTag);
        player.setGameMode(GameMode.SURVIVAL);
        player.teleportAsync(EliminationHandler.home(player));
        player.setHealth(hearts * 2.0);
        Msg.send(player, "elimination.back-from-ban", Msg.t("hearts", hearts));
    }

    @Override
    public double eliminatedMaxHealth() {
        return usual.eliminatedMaxHealth();
    }

    @Override
    public Location respawnLocation(Player player) {
        return on() ? null : usual.respawnLocation(player);
    }

    /** Bans for the length this elimination earns (they get longer through the season), then kicks. */
    private void ban(Player player) {
        hearts.eliminations(player.getUniqueId()).whenComplete((count, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Couldn't count " + player.getName() + "'s eliminations to ban them", error);
                return;
            }
            plugin.tasks().forEntity(player, () -> {
                if (!player.isOnline() || !tracker.isEliminated(player)) return;
                Duration length = plugin.settings().bans().length(count);
                String reason = PlainTextComponentSerializer.plainText().serialize(Msg.get("elimination.ban-reason"));
                player.getPersistentDataContainer().set(bannedTag, PersistentDataType.BOOLEAN, true);
                player.ban(reason, length.isZero() ? null : length, SOURCE, false);
                player.kick(length.isZero() ? Msg.get("elimination.banned-forever")
                        : Msg.get("elimination.banned", Msg.t("time", Msg.duration(length))));
                plugin.getLogger().info("Banned " + player.getName() + " for " + (length.isZero() ? "good" : Msg.duration(length))
                        + " (elimination " + count + " this season).");
            });
        });
    }

    private void comeBack(Player player) {
        if (!returning.add(player.getUniqueId())) return;
        int revive = hearts.rules().reviveHearts();
        tracker.track(hearts.apply(java.util.List.of(
                        HeartRequest.set(player.getUniqueId(), revive, HeartCause.REVIVE, "server").withNote("ban over"))))
                .whenComplete((changes, error) -> returning.remove(player.getUniqueId()));
    }

    /** Brought back while banned (a rift, a pledge, an admin): lift the ban so they can join. */
    @EventHandler
    void onRevived(PlayerRevivedEvent event) {
        unban(event.change().player());
    }

    /** Lifts this plugin's ban on a player, if there is one. Any other ban stays. */
    void unban(UUID player) {
        ProfileBanList bans = plugin.getServer().getBanList(BanListType.PROFILE);
        var profile = plugin.getServer().createProfile(player);
        BanEntry<?> entry = bans.getBanEntry(profile);
        if (entry != null && SOURCE.equals(entry.getSource())) bans.pardon(profile);
    }

    /** A new season: everyone this plugin banned can come back. */
    void unbanEveryone() {
        ProfileBanList bans = plugin.getServer().getBanList(BanListType.PROFILE);
        int lifted = 0;
        for (BanEntry<?> entry : java.util.List.<BanEntry<?>>copyOf(bans.getEntries())) {
            if (!SOURCE.equals(entry.getSource()) || !(entry.getBanTarget() instanceof com.destroystokyo.paper.profile.PlayerProfile profile)) continue;
            bans.pardon(profile);
            lifted++;
        }
        if (lifted > 0) plugin.getLogger().info("New season: lifted " + lifted + " elimination ban(s).");
    }
}
