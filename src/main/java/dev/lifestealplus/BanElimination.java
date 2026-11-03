package dev.lifestealplus;

import dev.lifestealplus.core.HeartCause;
import dev.lifestealplus.core.HeartRequest;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.event.PlayerEliminatedEvent;
import dev.lifestealplus.event.PlayerRevivedEvent;
import io.papermc.paper.ban.BanListType;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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
 * eliminations.ban: eliminated players are kept out for a while instead of going to Purgatory
 * or spectating, and come back with hearts.revive hearts the first time they join after that.
 * The plugin bans them itself, or (use-hooks) leaves it to a ban plugin run from hooks.yml.
 * Wraps the usual handler and hands everything to it while the setting is off, so switching
 * needs no restart; players already in Purgatory when it's switched on stay there.
 *
 * <p>When a player was banned is kept in the shared database, so on a network they come back
 * on whichever server they join.
 */
final class BanElimination implements EliminationHandler, Listener {

    /** The ban list's "source" for bans this plugin made, so it only ever lifts its own. */
    static final String SOURCE = "LifestealPlus";

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartTracker tracker;
    private final EliminationHandler usual;
    /** Set on players ban mode is holding on this server, so their return is handled here and not by Purgatory. */
    private final NamespacedKey heldTag;
    /** Players whose return is being saved, so a second check doesn't revive them twice. */
    private final Set<UUID> returning = ConcurrentHashMap.newKeySet();

    BanElimination(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker, EliminationHandler usual) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
        this.usual = usual;
        this.heldTag = new NamespacedKey(plugin, "banned");
    }

    private PluginSettings.BanSettings settings() {
        return plugin.settings().bans();
    }

    /** When this player's ban began, in the shared state: their first join after it means it's over. */
    private static final String BANNED_AT = "ban-mode.banned-at.";

    private static String bannedAtKey(UUID player) {
        return BANNED_AT + player;
    }

    private boolean held(Player player) {
        return player.getPersistentDataContainer().has(heldTag, PersistentDataType.BOOLEAN);
    }

    @Override
    public void eliminate(Player player) {
        if (!settings().enabled()) {
            usual.eliminate(player);
            return;
        }
        player.getPersistentDataContainer().set(heldTag, PersistentDataType.BOOLEAN, true);
        player.setGameMode(GameMode.SPECTATOR);
        decide(player);
    }

    @Override
    public void enforce(Player player) {
        if (held(player)) decide(player);
        else usual.enforce(player);
    }

    @Override
    public void restore(Player player, int hearts) {
        if (!held(player)) {
            usual.restore(player, hearts);
            return;
        }
        player.getPersistentDataContainer().remove(heldTag);
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
        return settings().enabled() ? null : usual.respawnLocation(player);
    }

    /** With a ban plugin doing the banning, its ban starts as the elimination happens (the player may be offline). */
    @EventHandler
    void onEliminated(PlayerEliminatedEvent event) {
        if (settings().enabled() && settings().useHooks()) {
            hearts.putState(bannedAtKey(event.change().player()), String.valueOf(System.currentTimeMillis()));
        }
    }

    /**
     * Back after the ban (they've logged in since it began): revive them. Otherwise ban them now,
     * or with use-hooks, wait as a spectator for the ban plugin to kick them.
     */
    private void decide(Player player) {
        UUID id = player.getUniqueId();
        hearts.state(bannedAtKey(id)).thenCombine(hearts.eliminations(id), Decision::new).whenComplete((decision, error) ->
                plugin.tasks().forEntity(player, () -> {
                    if (!player.isOnline() || !tracker.isEliminated(player)) return;
                    if (error != null) {
                        // Better in Purgatory than left standing around eliminated.
                        plugin.getLogger().log(Level.SEVERE, "Couldn't work out " + player.getName() + "'s ban; using the usual elimination", error);
                        player.getPersistentDataContainer().remove(heldTag);
                        usual.eliminate(player);
                        return;
                    }
                    long bannedAt = decision.bannedAt();
                    if (bannedAt > 0 && player.getLastLogin() > bannedAt) comeBack(player);
                    else if (!settings().useHooks()) ban(player, decision.count(), bannedAt);
                }));
    }

    private record Decision(Optional<String> saved, int count) {

        long bannedAt() {
            try {
                return saved.map(Long::parseLong).orElse(0L);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    /** Bans for the length this elimination earns (they get longer through the season), then kicks. */
    private void ban(Player player, int count, long bannedAt) {
        if (bannedAt == 0) hearts.putState(bannedAtKey(player.getUniqueId()), String.valueOf(System.currentTimeMillis()));
        Duration length = settings().length(count);
        String reason = PlainTextComponentSerializer.plainText().serialize(Msg.get("elimination.ban-reason"));
        player.ban(reason, length.isZero() ? null : length, SOURCE, false);
        player.kick(length.isZero() ? Msg.get("elimination.banned-forever")
                : Msg.get("elimination.banned", Msg.t("time", Msg.duration(length))));
        plugin.getLogger().info("Banned " + player.getName() + " for " + (length.isZero() ? "good" : Msg.duration(length))
                + " (elimination " + count + " this season).");
    }

    private void comeBack(Player player) {
        if (!returning.add(player.getUniqueId())) return;
        int revive = hearts.rules().reviveHearts();
        tracker.track(hearts.apply(List.of(
                        HeartRequest.set(player.getUniqueId(), revive, HeartCause.REVIVE, "server").withNote("ban over"))))
                .whenComplete((changes, error) -> returning.remove(player.getUniqueId()));
    }

    /** Brought back while banned (a rift, a pledge, an admin): lift the ban so they can join. */
    @EventHandler
    void onRevived(PlayerRevivedEvent event) {
        UUID player = event.change().player();
        hearts.clearState(bannedAtKey(player));
        ProfileBanList bans = plugin.getServer().getBanList(BanListType.PROFILE);
        var profile = plugin.getServer().createProfile(player);
        BanEntry<?> entry = bans.getBanEntry(profile);
        if (entry != null && SOURCE.equals(entry.getSource())) bans.pardon(profile);
    }

    /** A new season: everyone this plugin banned can come back, and nobody's ban carries over. */
    void unbanEveryone() {
        hearts.clearStates(BANNED_AT);
        ProfileBanList bans = plugin.getServer().getBanList(BanListType.PROFILE);
        int lifted = 0;
        for (BanEntry<?> entry : List.<BanEntry<?>>copyOf(bans.getEntries())) {
            if (!SOURCE.equals(entry.getSource()) || !(entry.getBanTarget() instanceof com.destroystokyo.paper.profile.PlayerProfile profile)) continue;
            bans.pardon(profile);
            lifted++;
        }
        if (lifted > 0) plugin.getLogger().info("New season: lifted " + lifted + " elimination ban(s).");
    }
}
