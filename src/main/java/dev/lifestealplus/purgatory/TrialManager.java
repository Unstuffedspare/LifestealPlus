package dev.lifestealplus.purgatory;

import dev.lifestealplus.HeartTracker;
import dev.lifestealplus.LifestealPlugin;
import dev.lifestealplus.PluginSettings.PurgatorySettings;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.PurgatoryProgress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Runs Purgatory's trials, one at a time. Someone calls a trial, others have a short
 * window to join, then everyone who joined fights through the waves together. Players
 * still standing at the end each earn a fragment, unless they won one too recently, in
 * which case they were there to help. Everything here runs on the main thread, ticked
 * once a second.
 */
public final class TrialManager {

    private static final int GATHER_SECONDS = 15;
    private static final int INTRO_SECONDS = 3;
    private static final int GLOW_AFTER_SECONDS = 45;
    private static final int WAVE_TIME_LIMIT_SECONDS = 240;

    private enum Phase { IDLE, GATHERING, INTRO, FIGHTING }

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartTracker tracker;
    private final Purgatory purgatory;
    private final PurgatoryWorld world;
    private final NamespacedKey mobTag;
    private final BukkitTask ticker;
    /** True during the season finale: trials can't bring anyone back. */
    private final BooleanSupplier sealed;

    private Phase phase = Phase.IDLE;
    private int seconds;
    private int wave;
    /** Who's in, and whether a win earns them a fragment (false while their cooldown runs). */
    private final Map<UUID, Boolean> participants = new LinkedHashMap<>();
    private final Set<UUID> mobs = new HashSet<>();
    private LivingEntity boss;
    private BossBar bossBar;

    public TrialManager(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker, Purgatory purgatory,
                        BooleanSupplier sealed) {
        this.sealed = sealed;
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
        this.purgatory = purgatory;
        this.world = purgatory.world();
        this.mobTag = new NamespacedKey(plugin, "trial_mob");
        this.ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
    }

    private PurgatorySettings settings() {
        return plugin.settings().purgatory();
    }

    /** Right-clicking the altar or /purgatory start. Joins the gathering trial if there is one. */
    public void call(Player player) {
        if (!canTakePart(player)) return;
        if (sealed.getAsBoolean()) {
            player.sendRichMessage("<dark_red>Purgatory is sealed for the finale. Nobody returns now.");
            return;
        }
        switch (phase) {
            case IDLE -> {
                phase = Phase.GATHERING;
                seconds = GATHER_SECONDS;
                announce(MiniMessage.miniMessage().deserialize(
                        "<dark_aqua><player> is calling a trial. <click:run_command:'/purgatory join'><aqua><u>[Join]</u></aqua></click>"
                                + " <gray>(starts in <seconds>s)",
                        Placeholder.unparsed("player", player.getName()),
                        Placeholder.unparsed("seconds", String.valueOf(GATHER_SECONDS))));
                join(player);
            }
            case GATHERING -> join(player);
            default -> player.sendRichMessage("<gray>A trial is already underway. Wait for it to finish.");
        }
    }

    public void join(Player player) {
        if (!canTakePart(player)) return;
        if (phase != Phase.GATHERING) {
            player.sendRichMessage(phase == Phase.IDLE
                    ? "<gray>No trial is gathering. Right-click the lodestone or use /purgatory start to call one."
                    : "<gray>A trial is already underway. Wait for it to finish.");
            return;
        }
        if (participants.containsKey(player.getUniqueId())) return;
        UUID id = player.getUniqueId();
        hearts.progress(id).whenCompleteAsync((progress, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Couldn't read Purgatory progress for " + player.getName(), error);
                player.sendRichMessage("<red>Couldn't join the trial; the error is in the server log.");
                return;
            }
            if (phase != Phase.GATHERING || !player.isOnline() || participants.containsKey(id)) return;
            Duration wait = cooldownLeft(progress);
            participants.put(id, wait.isZero());
            if (wait.isZero()) {
                player.sendRichMessage("<aqua>You've joined the trial. Survive every wave to earn a fragment.");
            } else {
                player.sendRichMessage("<aqua>You've joined the trial to help.</aqua> <gray>You won one recently, so this one "
                        + "won't earn you a fragment (next in <wait>).", Placeholder.unparsed("wait", describe(wait)));
            }
        }, tracker.mainThread());
    }

    public boolean isParticipant(Player player) {
        return participants.containsKey(player.getUniqueId());
    }

    /** The player is out of the trial: died, quit, left Purgatory, or was revived. */
    public void leave(Player player) {
        if (participants.remove(player.getUniqueId()) == null) return;
        if (bossBar != null) player.hideBossBar(bossBar);
        if ((phase == Phase.INTRO || phase == Phase.FIGHTING) && participants.isEmpty()) {
            fail("<red>The trial has claimed everyone who entered.");
        }
    }

    public void died(Player player) {
        if (!isParticipant(player)) return;
        player.sendRichMessage("<gray>You fell in the trial. You'll have to try again.");
        leave(player);
    }

    /** The countdown to start, the waves, and the checks between them. */
    private void tick() {
        switch (phase) {
            case IDLE -> { }
            case GATHERING -> {
                seconds--;
                if (seconds > 0 && seconds <= 5) {
                    forEachParticipant(p -> {
                        p.showTitle(Title.title(Component.text(seconds, NamedTextColor.AQUA), Component.empty(),
                                Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100))));
                        p.playSound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.6f);
                    });
                }
                if (seconds <= 0) begin();
            }
            case INTRO -> {
                if (--seconds <= 0) spawnWave();
            }
            case FIGHTING -> fight();
        }
    }

    private void begin() {
        participants.keySet().removeIf(id -> {
            Player p = plugin.getServer().getPlayer(id);
            return p == null || !tracker.isEliminated(p) || !world.contains(p.getLocation());
        });
        if (participants.isEmpty()) {
            phase = Phase.IDLE;
            return;
        }
        List<Player> players = onlineParticipants();
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            p.teleport(world.arenaPoint(2.5, 2 * Math.PI * i / players.size()).setDirection(
                    world.arenaCenter().toVector().subtract(world.arenaPoint(2.5, 2 * Math.PI * i / players.size()).toVector())));
            purgatory.kit().give(p);
        }
        wave = 0;
        nextWave();
    }

    private void nextWave() {
        wave++;
        phase = Phase.INTRO;
        seconds = INTRO_SECONDS;
        Component title = Waves.isFinal(wave)
                ? Component.text("Final wave", NamedTextColor.DARK_RED)
                : Component.text("Wave " + wave, NamedTextColor.AQUA);
        forEachParticipant(p -> {
            p.showTitle(Title.title(title, Component.text(wave + " of " + Waves.COUNT, NamedTextColor.GRAY)));
            p.playSound(p, Waves.isFinal(wave) ? Sound.ENTITY_WITHER_SPAWN : Sound.BLOCK_BELL_USE, 0.8f, 0.8f);
        });
    }

    private void spawnWave() {
        int players = participants.size();
        double difficulty = settings().difficulty();
        for (Waves.Group group : Waves.wave(wave)) {
            int count = Waves.scaledCount(group, players, difficulty);
            for (int i = 0; i < count; i++) {
                double angle = ThreadLocalRandom.current().nextDouble(2 * Math.PI);
                Mob mob = world.world().spawn(world.arenaPoint(12, angle), group.type(), CreatureSpawnEvent.SpawnReason.CUSTOM,
                        m -> prepare(m, players, difficulty));
                mobs.add(mob.getUniqueId());
            }
        }
        phase = Phase.FIGHTING;
        seconds = 0;
    }

    private void prepare(Mob mob, int players, double difficulty) {
        mob.getPersistentDataContainer().set(mobTag, PersistentDataType.BOOLEAN, true);
        mob.setRemoveWhenFarAway(false);
        mob.setCanPickupItems(false);
        if (mob instanceof Ageable ageable) ageable.setAdult();
        if (mob.getType() == org.bukkit.entity.EntityType.WITHER_SKELETON) {
            double health = Waves.bossHealth(players, difficulty);
            mob.getAttribute(Attribute.MAX_HEALTH).setBaseValue(health);
            mob.setHealth(health);
            mob.customName(Component.text(Waves.BOSS_NAME, NamedTextColor.DARK_RED));
            mob.setCustomNameVisible(true);
            boss = mob;
            bossBar = BossBar.bossBar(Component.text(Waves.BOSS_NAME, NamedTextColor.DARK_RED), 1f,
                    BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
            forEachParticipant(p -> p.showBossBar(bossBar));
        }
    }

    private void fight() {
        seconds++;
        mobs.removeIf(id -> {
            Entity e = plugin.getServer().getEntity(id);
            return e == null || e.isDead() || !e.isValid();
        });
        List<Player> players = onlineParticipants();
        for (UUID id : mobs) {
            if (!(plugin.getServer().getEntity(id) instanceof Mob mob)) continue;
            if (!(mob.getTarget() instanceof Player target) || !isParticipant(target)) {
                players.stream()
                        .min((a, b) -> Double.compare(a.getLocation().distanceSquared(mob.getLocation()),
                                b.getLocation().distanceSquared(mob.getLocation())))
                        .ifPresent(mob::setTarget);
            }
            // Stragglers get easier to find the longer a wave drags on.
            if (seconds == GLOW_AFTER_SECONDS) mob.setGlowing(true);
        }
        if (bossBar != null && boss != null) {
            double max = boss.getAttribute(Attribute.MAX_HEALTH).getValue();
            bossBar.progress((float) Math.max(0, Math.min(1, boss.isDead() ? 0 : boss.getHealth() / max)));
        }

        if (mobs.isEmpty()) {
            if (Waves.isFinal(wave)) {
                win();
            } else {
                // A breather between waves.
                forEachParticipant(p -> p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1)));
                nextWave();
            }
        } else if (seconds >= WAVE_TIME_LIMIT_SECONDS) {
            fail("<red>The trial's time ran out.");
        }
    }

    private void win() {
        Map<UUID, Boolean> survivors = new LinkedHashMap<>(participants);
        reset();
        int needed = settings().fragmentsNeeded();
        if (sealed.getAsBoolean()) {
            announce(MiniMessage.miniMessage().deserialize(
                    "<dark_red>The trial is won, but the finale has sealed Purgatory: no fragments now."));
            survivors.keySet().forEach(id -> {
                Player p = plugin.getServer().getPlayer(id);
                if (p != null) p.teleport(world.hub());
            });
            return;
        }
        announce(MiniMessage.miniMessage().deserialize("<aqua>The trial is won."));
        survivors.forEach((id, earns) -> {
            Player p = plugin.getServer().getPlayer(id);
            if (p == null) return;
            p.teleport(world.hub());
            purgatory.kit().give(p);
            p.showTitle(Title.title(Component.text("Trial won", NamedTextColor.AQUA), Component.empty()));
            p.playSound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            if (!earns) {
                p.sendRichMessage("<gray>You helped win this one. Your next fragment can be earned once your cooldown ends.");
                return;
            }
            hearts.awardFragment(id, needed).whenCompleteAsync((reward, error) -> {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "Couldn't award a fragment to " + p.getName(), error);
                    p.sendRichMessage("<red>Your fragment couldn't be recorded; the error is in the server log.");
                } else if (reward.revive() != null) {
                    tracker.track(CompletableFuture.completedFuture(List.of(reward.revive())));
                } else if (reward.fragments() > 0) {
                    p.sendRichMessage("<aqua>Fragment earned: <white><have>/<needed></white>.</aqua> <gray>You can earn the next one in <wait>.",
                            Placeholder.unparsed("have", String.valueOf(reward.fragments())),
                            Placeholder.unparsed("needed", String.valueOf(reward.needed())),
                            Placeholder.unparsed("wait", describe(settings().trialCooldown())));
                }
            }, tracker.mainThread());
        });
    }

    private void fail(String message) {
        List<Player> standing = onlineParticipants();
        reset();
        announce(MiniMessage.miniMessage().deserialize(message));
        standing.forEach(p -> {
            p.teleport(world.hub());
            purgatory.kit().give(p);
        });
    }

    /** Clears the arena and goes back to waiting for someone to call a trial. */
    private void reset() {
        for (UUID id : mobs) {
            Entity e = plugin.getServer().getEntity(id);
            if (e != null) e.remove();
        }
        mobs.clear();
        if (bossBar != null) onlineParticipants().forEach(p -> p.hideBossBar(bossBar));
        bossBar = null;
        boss = null;
        participants.clear();
        phase = Phase.IDLE;
    }

    /** On plugin disable: nobody should be left standing in an arena full of mobs. */
    public void shutdown() {
        ticker.cancel();
        List<Player> standing = onlineParticipants();
        reset();
        standing.forEach(p -> p.teleport(world.hub()));
    }

    public String status() {
        return switch (phase) {
            case IDLE -> "No trial is running.";
            case GATHERING -> "A trial is gathering (" + seconds + "s left to join).";
            case INTRO, FIGHTING -> "A trial is underway (wave " + wave + " of " + Waves.COUNT + ", "
                    + participants.size() + " still standing).";
        };
    }

    Duration cooldownLeft(PurgatoryProgress progress) {
        if (progress.lastWin() == null) return Duration.ZERO;
        Duration left = Duration.between(Instant.now(), progress.lastWin().plus(settings().trialCooldown()));
        return left.isNegative() ? Duration.ZERO : left;
    }

    static String describe(Duration d) {
        long minutes = (d.toSeconds() + 59) / 60;
        return minutes <= 1 ? "a minute" : minutes + " minutes";
    }

    private boolean canTakePart(Player player) {
        if (!tracker.isEliminated(player) || !world.contains(player.getLocation())) {
            player.sendRichMessage("<gray>Only players in Purgatory can take part in trials.");
            return false;
        }
        return true;
    }

    private List<Player> onlineParticipants() {
        List<Player> online = new ArrayList<>();
        for (UUID id : participants.keySet()) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) online.add(p);
        }
        return online;
    }

    private void forEachParticipant(java.util.function.Consumer<Player> action) {
        onlineParticipants().forEach(action);
    }

    /** Tells everyone in Purgatory. */
    private void announce(Component message) {
        world.world().getPlayers().forEach(p -> p.sendMessage(message));
    }

    boolean isTrialMob(Entity entity) {
        return entity.getPersistentDataContainer().has(mobTag, PersistentDataType.BOOLEAN);
    }
}
