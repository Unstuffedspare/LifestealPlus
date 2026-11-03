package dev.lifestealplus.events;

import dev.lifestealplus.EventSettings;
import dev.lifestealplus.LifestealPlugin;
import dev.lifestealplus.Msg;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.NewsItem;
import dev.lifestealplus.items.HeartItems;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/**
 * Heart crates that fall out of the sky at announced coordinates, to pull people
 * together and into fights. One crate at a time. Only the time of the next drop is saved;
 * a crate still standing at shutdown is cleared on the next start, so none are left behind.
 */
public final class Airdrops implements Listener {

    private static final String NEXT = "airdrop.next";
    private static final String CRATE = "airdrop.crate";

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartItems items;
    private Instant next;
    private Object timing;
    /** Where an announced drop will land, or has landed; null when there's none. */
    private Location target;
    /** The crate on its way is a golden one: twice the loot and an extra Heart. */
    private boolean golden;
    private Block crate;
    private boolean opened;
    private final List<BukkitTask> pending = new ArrayList<>();

    public Airdrops(LifestealPlugin plugin, HeartService hearts, HeartItems items) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.items = items;
        hearts.state(CRATE).thenCombine(hearts.state(NEXT), Saved::new)
                .whenComplete((saved, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (error != null) plugin.getLogger().log(Level.SEVERE, "Couldn't load airdrop timing", error);
                    if (saved != null) {
                        saved.crate().ifPresent(this::clearLeftoverCrate);
                        next = saved.next().map(v -> Instant.ofEpochMilli(Long.parseLong(v))).orElse(null);
                    }
                    timing = timing();
                    if (next == null || !settings().schedule().isEmpty()) scheduleNext();
                    plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20 * 30, 20 * 30);
                }));
    }

    private record Saved(java.util.Optional<String> crate, java.util.Optional<String> next) {
    }

    private EventSettings.Airdrops settings() {
        return plugin.settings().events().airdrops();
    }

    /** At the next scheduled landing (announced warning-minutes before), or about every-minutes from now. */
    private void scheduleNext() {
        timing = timing();
        Duration every = settings().every();
        if (every.isZero()) {
            next = null;
            hearts.clearState(NEXT);
            return;
        }
        if (!settings().schedule().isEmpty()) {
            Duration warning = settings().warning();
            next = settings().schedule().next(Instant.now().plus(warning)).minus(warning);
        } else {
            double factor = 0.75 + ThreadLocalRandom.current().nextDouble() * 0.5;
            next = Instant.now().plusMillis((long) (every.toMillis() * factor));
        }
        hearts.putState(NEXT, String.valueOf(next.toEpochMilli()));
    }

    private Object timing() {
        return java.util.List.of(settings().every(), settings().schedule(), settings().warning());
    }

    /** After a settings change: a new schedule (or a switch off or on) moves the next drop at once. */
    public void settingsChanged() {
        if (target == null && !timing().equals(timing)) scheduleNext();
    }

    private void tick() {
        if (target != null || next == null || Instant.now().isBefore(next)) return;
        if (plugin.getServer().getOnlinePlayers().size() >= settings().minPlayers()) {
            announce(randomSpot(), settings().warning());
        } else {
            next = Instant.now().plus(Duration.ofMinutes(10));
            hearts.putState(NEXT, String.valueOf(next.toEpochMilli()));
        }
    }

    /** Starts a drop: announce where, then land after {@code warning}. False if one is already on its way or waiting. */
    public boolean announce(Location where, Duration warning) {
        if (target != null) return false;
        target = where;
        golden = ThreadLocalRandom.current().nextDouble() < settings().goldenChance();
        next = null;
        hearts.clearState(NEXT);
        broadcast(golden ? "airdrops.incoming-golden" : "airdrops.incoming", Msg.t("x", where.getBlockX()),
                Msg.t("z", where.getBlockZ()), Msg.t("time", Msg.duration(warning)));
        plugin.getServer().getOnlinePlayers().forEach(p -> p.playSound(p, Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 0.7f));
        pending.add(plugin.getServer().getScheduler().runTaskLater(plugin, this::land, Math.max(1, warning.toSeconds() * 20)));
        return true;
    }

    /** Uniform over the disc around spawn, not bunched in the middle. */
    private Location randomSpot() {
        World world = plugin.getServer().getWorlds().getFirst();
        Location spawn = world.getSpawnLocation();
        double angle = ThreadLocalRandom.current().nextDouble(2 * Math.PI);
        double distance = settings().radius() * Math.sqrt(ThreadLocalRandom.current().nextDouble());
        return new Location(world, spawn.getX() + Math.cos(angle) * distance, 0, spawn.getZ() + Math.sin(angle) * distance);
    }

    private void land() {
        if (target == null) return;
        World world = target.getWorld();
        Block ground = world.getHighestBlockAt(target.getBlockX(), target.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES);
        crate = ground.getRelative(0, 1, 0);
        crate.setType(Material.CHEST);
        if (crate.getState() instanceof Chest chest) {
            chest.customName(Msg.get("airdrops.crate-name"));
            chest.update();
            fill(chest.getBlockInventory());
        }
        opened = false;
        Location at = crate.getLocation().add(0.5, 0.5, 0.5);
        world.strikeLightningEffect(at);
        world.spawnParticle(Particle.FIREWORK, at, 60, 0.6, 1.5, 0.6, 0.05);
        hearts.putState(CRATE, world.getName() + "," + crate.getX() + "," + crate.getY() + "," + crate.getZ());
        broadcast("airdrops.landed", Msg.t("x", crate.getX()), Msg.t("y", crate.getY()), Msg.t("z", crate.getZ()));
        // Only ever this crate: if it was emptied and a newer one has landed since, leave that one alone.
        Block thisCrate = crate;
        pending.add(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (crate != null && crate.equals(thisCrate)) {
                removeCrate();
                broadcast("airdrops.crumbled");
            }
        }, settings().despawn().toSeconds() * 20));
    }

    /** One heart, a couple of fragments, and supplies for the fight over it. */
    private void fill(Inventory inv) {
        int times = golden ? 2 : 1;
        List<ItemStack> loot = new ArrayList<>(List.of(
                items.heart(times + (golden ? 1 : 0)),
                items.fragment(2 * times),
                new ItemStack(Material.GOLDEN_APPLE, 2 * times),
                new ItemStack(Material.ENDER_PEARL, 4 * times),
                new ItemStack(Material.ARROW, 16 * times)));
        if (golden) loot.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 1));
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) slots.add(i);
        Collections.shuffle(slots);
        for (int i = 0; i < loot.size(); i++) inv.setItem(slots.get(i), loot.get(i));
    }

    private boolean isCrate(Block block) {
        return crate != null && crate.equals(block);
    }

    @EventHandler
    void onOpen(InventoryOpenEvent event) {
        if (!(event.getInventory().getHolder() instanceof Chest chest) || !isCrate(chest.getBlock())) return;
        if (opened) return;
        opened = true;
        broadcast("airdrops.opened", Msg.t("player", event.getPlayer().getName()));
        hearts.addNews(NewsItem.AIRDROP, event.getPlayer().getName(), null, 0);
    }

    /** Once it's been emptied it has done its job. */
    @EventHandler
    void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Chest chest) || !isCrate(chest.getBlock())) return;
        if (event.getInventory().isEmpty()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (crate != null && crate.getState() instanceof Chest c && c.getBlockInventory().isEmpty()) removeCrate();
            });
        }
    }

    @EventHandler(ignoreCancelled = true)
    void onBreak(BlockBreakEvent event) {
        if (!isCrate(event.getBlock())) return;
        event.setCancelled(true);
        Msg.send(event.getPlayer(), "airdrops.dont-break");
    }

    @EventHandler(ignoreCancelled = true)
    void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isCrate);
    }

    @EventHandler(ignoreCancelled = true)
    void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isCrate);
    }

    private void removeCrate() {
        if (crate != null && crate.getType() == Material.CHEST) {
            if (crate.getState() instanceof Chest chest) chest.getBlockInventory().clear();
            crate.setType(Material.AIR);
        }
        crate = null;
        target = null;
        hearts.clearState(CRATE);
        scheduleNext();
    }

    private void clearLeftoverCrate(String saved) {
        String[] parts = saved.split(",");
        World world = plugin.getServer().getWorld(parts[0]);
        hearts.clearState(CRATE);
        if (world == null) return;
        Block block = world.getBlockAt(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        if (block.getType() == Material.CHEST) {
            if (block.getState() instanceof Chest chest) chest.getBlockInventory().clear();
            block.setType(Material.AIR);
        }
    }

    /** On disable: a crate mid-flight just doesn't arrive; one on the ground goes. */
    public void shutdown() {
        pending.forEach(BukkitTask::cancel);
        pending.clear();
        if (crate != null) {
            if (crate.getState() instanceof Chest chest) chest.getBlockInventory().clear();
            crate.setType(Material.AIR);
            crate = null;
        }
    }

    public Instant nextDrop() {
        return next;
    }

    private void broadcast(String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... tags) {
        Msg.broadcast(key, tags);
        for (Player p : plugin.getServer().getOnlinePlayers()) p.playSound(p, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 1.2f);
    }
}
