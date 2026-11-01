package dev.lifestealplus.purgatory;

import java.util.List;
import java.util.Random;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * The Purgatory dimension: an empty void world holding two floating islands built
 * from code, a hub where eliminated players wait and an arena where trials run.
 * Building from code means there are no map files to ship or install.
 */
public final class PurgatoryWorld {

    /** Bump when the layout changes so existing worlds get rebuilt on the next start. */
    private static final int LAYOUT_VERSION = 1;

    static final int FLOOR_Y = 64;
    private static final int HUB_RADIUS = 9;
    static final int ARENA_RADIUS = 16;
    private static final int ARENA_Z = 64;
    private static final int WALL_TOP = FLOOR_Y + 7;

    private final World world;

    private PurgatoryWorld(World world) {
        this.world = world;
    }

    public static PurgatoryWorld load(Plugin plugin, String name) {
        World world = new WorldCreator(name)
                .environment(World.Environment.NETHER)
                .generator(new VoidGenerator())
                .biomeProvider(new SoulValley())
                .generateStructures(false)
                .createWorld();
        if (world == null) throw new IllegalStateException("Couldn't create the Purgatory world '" + name + "'");

        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.MOB_DROPS, false);
        world.setGameRule(GameRules.ENTITY_DROPS, false);
        world.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
        world.setGameRule(GameRules.MOB_GRIEFING, false);
        world.setGameRule(GameRules.KEEP_INVENTORY, false);
        world.setGameRule(GameRules.SHOW_DEATH_MESSAGES, false);
        world.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        world.setGameRule(GameRules.PVP, false);
        world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);

        PurgatoryWorld purgatory = new PurgatoryWorld(world);
        NamespacedKey layoutKey = new NamespacedKey(plugin, "layout");
        Integer built = world.getPersistentDataContainer().get(layoutKey, PersistentDataType.INTEGER);
        if (built == null || built != LAYOUT_VERSION) {
            plugin.getLogger().info("Building Purgatory (layout v" + LAYOUT_VERSION + ")...");
            purgatory.build();
            world.getPersistentDataContainer().set(layoutKey, PersistentDataType.INTEGER, LAYOUT_VERSION);
        }
        return purgatory;
    }

    public World world() {
        return world;
    }

    public boolean contains(Location location) {
        return location.getWorld() != null && location.getWorld().equals(world);
    }

    /** Where eliminated players arrive and respawn, facing the altar. */
    public Location hub() {
        return new Location(world, 0.5, FLOOR_Y + 1, 5.5, 180, 0);
    }

    /** Right-clicking this block calls a trial. */
    public Block altar() {
        return world.getBlockAt(0, FLOOR_Y + 1, 0);
    }

    public Location arenaCenter() {
        return new Location(world, 0.5, FLOOR_Y + 1, ARENA_Z + 0.5);
    }

    public boolean inArena(Location location) {
        if (!contains(location)) return false;
        double dx = location.getX() - 0.5, dz = location.getZ() - (ARENA_Z + 0.5);
        return dx * dx + dz * dz <= (ARENA_RADIUS + 2) * (ARENA_RADIUS + 2);
    }

    /** A point on a circle around the arena center, on the floor. */
    Location arenaPoint(double radius, double angle) {
        return new Location(world, 0.5 + Math.cos(angle) * radius, FLOOR_Y + 1, ARENA_Z + 0.5 + Math.sin(angle) * radius);
    }

    private void build() {
        buildHub();
        buildArena();
    }

    private void buildHub() {
        for (int x = -HUB_RADIUS - 1; x <= HUB_RADIUS + 1; x++) {
            for (int z = -HUB_RADIUS - 1; z <= HUB_RADIUS + 1; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d > HUB_RADIUS + 0.5) continue;
                set(x, FLOOR_Y, z, d > HUB_RADIUS - 0.5 ? Material.CHISELED_POLISHED_BLACKSTONE
                        : (x * 7 + z * 13) % 5 == 0 ? Material.CRACKED_POLISHED_BLACKSTONE_BRICKS
                        : Material.POLISHED_BLACKSTONE_BRICKS);
                underside(x, z, d, HUB_RADIUS, Material.BLACKSTONE, Material.BASALT);
                if (d > HUB_RADIUS - 0.5) set(x, FLOOR_Y + 1, z, Material.POLISHED_BLACKSTONE_BRICK_WALL);
            }
        }
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            int x = (int) Math.round(Math.cos(angle) * HUB_RADIUS), z = (int) Math.round(Math.sin(angle) * HUB_RADIUS);
            set(x, FLOOR_Y + 1, z, Material.POLISHED_BLACKSTONE);
            set(x, FLOOR_Y + 2, z, Material.SOUL_LANTERN);
        }
        set(0, FLOOR_Y, 0, Material.CRYING_OBSIDIAN);
        for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            set(side[0], FLOOR_Y, side[1], Material.CRYING_OBSIDIAN);
        }
        set(0, FLOOR_Y + 1, 0, Material.LODESTONE);
    }

    private void buildArena() {
        int outer = ARENA_RADIUS + 2;
        for (int x = -outer; x <= outer; x++) {
            for (int dz = -outer; dz <= outer; dz++) {
                int z = ARENA_Z + dz;
                double d = Math.sqrt(x * x + dz * dz);
                if (d > outer + 0.5) continue;
                boolean lit = d < ARENA_RADIUS - 1 && Math.floorMod(x, 6) == 0 && Math.floorMod(dz, 6) == 0;
                set(x, FLOOR_Y, z, lit ? Material.SHROOMLIGHT : Material.POLISHED_DEEPSLATE);
                underside(x, z, d, outer, Material.DEEPSLATE, Material.COBBLED_DEEPSLATE);
                if (d > ARENA_RADIUS + 0.5) {
                    for (int y = FLOOR_Y + 1; y <= WALL_TOP; y++) set(x, y, z, Material.DEEPSLATE_BRICKS);
                } else if (d > ARENA_RADIUS - 0.5) {
                    // A lip at the top of the wall, so climbing mobs can't get over it.
                    set(x, WALL_TOP, z, Material.DEEPSLATE_BRICKS);
                }
            }
        }
        for (int i = 0; i < 16; i++) {
            double angle = i * Math.PI / 8;
            int x = (int) Math.round(Math.cos(angle) * (ARENA_RADIUS + 1));
            int z = ARENA_Z + (int) Math.round(Math.sin(angle) * (ARENA_RADIUS + 1));
            set(x, FLOOR_Y + 3, z, Material.SHROOMLIGHT);
            set(x, FLOOR_Y + 1, z, Material.CRYING_OBSIDIAN);
        }
    }

    /** A rough downward cone under an island, so it reads as floating rock rather than a flat sheet. */
    private void underside(int x, int z, double d, int radius, Material main, Material accent) {
        int depth = (int) Math.round((radius + 0.5 - d) * 0.7) + 1;
        for (int y = 1; y <= depth; y++) {
            set(x, FLOOR_Y - y, z, (x + z + y) % 4 == 0 ? accent : main);
        }
    }

    private void set(int x, int y, int z, Material material) {
        world.getBlockAt(x, y, z).setType(material, false);
    }

    /** Generates nothing at all: everything in the world is placed by {@link #build}. */
    private static final class VoidGenerator extends ChunkGenerator {
        @Override
        public Location getFixedSpawnLocation(World world, Random random) {
            return new Location(world, 0.5, FLOOR_Y + 1, 5.5);
        }
    }

    /** Soul sand valley everywhere, for its blue fog, drifting ash and ambient sound. */
    private static final class SoulValley extends BiomeProvider {
        @Override
        public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
            return Biome.SOUL_SAND_VALLEY;
        }

        @Override
        public List<Biome> getBiomes(WorldInfo worldInfo) {
            return List.of(Biome.SOUL_SAND_VALLEY);
        }
    }
}
