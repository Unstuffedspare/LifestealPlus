package dev.lifestealplus;

import dev.lifestealplus.admin.AdminCommands;
import dev.lifestealplus.admin.AltTracker;
import dev.lifestealplus.admin.ImportCommand;
import dev.lifestealplus.admin.SeasonReset;
import dev.lifestealplus.combat.CombatBodies;
import dev.lifestealplus.combat.CombatTagger;
import dev.lifestealplus.combat.NewbieShield;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.HeartStore;
import dev.lifestealplus.events.Airdrops;
import dev.lifestealplus.events.BloodMoon;
import dev.lifestealplus.events.Finale;
import dev.lifestealplus.events.KingOfTheHill;
import dev.lifestealplus.events.TimedEvents;
import dev.lifestealplus.combat.CombatItems;
import dev.lifestealplus.items.HeartItems;
import dev.lifestealplus.items.LifestealZItems;
import dev.lifestealplus.purgatory.Purgatory;
import dev.lifestealplus.purgatory.PurgatoryCommands;
import dev.lifestealplus.purgatory.PurgatoryListener;
import dev.lifestealplus.purgatory.PurgatoryWorld;
import dev.lifestealplus.purgatory.TrialManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class LifestealPlugin extends JavaPlugin {

    private volatile PluginSettings settings;
    private HeartService hearts;
    private TrialManager trials;
    private MostWanted mostWanted;
    private CombatBodies bodies;
    private BloodMoon bloodMoon;
    private Airdrops airdrops;
    private TimedEvents timedEvents;
    private MenuLook menuLook;
    private KingOfTheHill koth;
    private Finale finale;
    private Purgatory purgatory;
    private CapSchedule capSchedule;
    private NewbieShield shield;
    private NewsFeed news;
    private HeartItems heartItems;

    /** WorldGuard's region flags, when WorldGuard is installed. */
    private Zones.RegionRules regionRules;
    private Zones zones;

    /** WorldGuard only accepts new flags while plugins load. */
    @Override
    public void onLoad() {
        if (getServer().getPluginManager().getPlugin("WorldGuard") != null) {
            regionRules = WorldGuardRules.register(getLogger());
        }
    }

    /** Where lifesteal applies: excluded worlds and WorldGuard regions. */
    public Zones zones() {
        return zones;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        addMissingSettings();
        List<String> newMessages = Msg.load(this);
        if (!newMessages.isEmpty()) getLogger().info("Added new messages to messages.yml: " + String.join(", ", newMessages));
        PluginSettings.itemNames(name -> {
            Material material = Material.matchMaterial(name);
            return material != null && material.isItem() && !material.isAir();
        });
        PluginSettings.Loaded loaded = PluginSettings.load(getConfig());
        settings = loaded.settings();
        logWarnings(loaded.warnings());
        zones = new Zones(this, regionRules);
        if (regionRules != null) getLogger().info("WorldGuard found: the lifesteal region flags are ready.");
        try {
            getDataFolder().mkdirs();
            Path database = getDataFolder().toPath().resolve(settings.databaseFile());
            Backups.restorePending(getDataFolder().toPath(), database, getLogger());
            HeartStore store = new HeartStore(database, Clock.systemUTC());
            hearts = new HeartService(store, settings.rules());
            applyStoreSwitches();
        } catch (Exception e) {
            // Running without the ledger would let hearts change unrecorded, so don't run at all.
            getLogger().log(Level.SEVERE, "LifestealPlus couldn't start; disabling", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        HeartTracker tracker = new HeartTracker(this);
        heartItems = new HeartItems(this, hearts, tracker);
        heartItems.syncRecipe();
        KillHandler kills = new KillHandler(this, hearts, tracker, heartItems);
        CombatTagger tagger = new CombatTagger(this, tracker);
        bodies = new CombatBodies(this, hearts, kills, tagger);
        getServer().getPluginManager().registerEvents(tagger, this);
        getServer().getPluginManager().registerEvents(bodies, this);
        shield = new NewbieShield(this, hearts);
        getServer().getPluginManager().registerEvents(shield, this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this, hearts, tracker, kills, bodies), this);
        bloodMoon = new BloodMoon(this, hearts, heartItems);
        airdrops = new Airdrops(this, hearts, heartItems);
        finale = new Finale(this, hearts);
        timedEvents = new TimedEvents(this, hearts, heartItems);
        getServer().getPluginManager().registerEvents(new Rewards(this, hearts, tracker, heartItems), this);
        koth = new KingOfTheHill(this, hearts, tracker);
        menuLook = new MenuLook(this);
        getServer().getPluginManager().registerEvents(new RevivalMenu(this, hearts, tracker, heartItems, finale, menuLook), this);
        for (var listener : List.of(heartItems, bloodMoon, airdrops, finale, new LifestealZItems(this, heartItems),
                new CombatItems(this), timedEvents, koth)) {
            getServer().getPluginManager().registerEvents(listener, this);
        }
        mostWanted = new MostWanted(this, tracker);
        mostWanted.schedule();
        new TabList(this, hearts, tracker);
        new VengeanceTracker(this, hearts, tagger);
        try {
            getServer().getPluginManager().registerEvents(new AltTracker(this, hearts), this);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Couldn't start alt-account tracking; logins won't be recorded", e);
        }
        Backups backups = new Backups(this, hearts, tracker);
        AuditLog audit = new AuditLog(this, hearts, tracker);
        getServer().getPluginManager().registerEvents(audit, this);
        AdminCommands admin = new AdminCommands(this, hearts, tracker,
                new SeasonReset(this, hearts, tracker, shield, bodies, finale, () -> capSchedule.seasonStarted(),
                        () -> backups.backUp("before-season-reset", null)));
        YamlConfiguration shippedConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(getResource("config.yml"), StandardCharsets.UTF_8));
        Presets presets = new Presets(YamlConfiguration.loadConfiguration(
                new InputStreamReader(getResource("presets.yml"), StandardCharsets.UTF_8)), shippedConfig);
        PersonalCaps caps = new PersonalCaps(this, hearts);
        PlaceholderData placeholders = new PlaceholderData(this, hearts, tracker, shield, caps);
        getServer().getPluginManager().registerEvents(placeholders, this);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI") && PlaceholderHook.hook(this, placeholders)) {
            getLogger().info("PlaceholderAPI found: %lifestealplus_...% placeholders are ready.");
        }
        getServer().getServicesManager().register(dev.lifestealplus.api.LifestealAPI.class,
                new ApiService(this, hearts, tracker, caps), this, org.bukkit.plugin.ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(new UpdateChecker(this), this);
        new DiscordFeed(this, hearts);
        getServer().getPluginManager().registerEvents(caps, this);
        SettingsMenu settingsMenu = new SettingsMenu(this, new SettingsCatalog(shippedConfig), presets, audit, menuLook);
        getServer().getPluginManager().registerEvents(settingsMenu, this);
        LifestealCommands commands = new LifestealCommands(this, hearts, tracker, mostWanted, shield,
                new EventCommands(this, heartItems, bloodMoon, airdrops, finale, timedEvents, koth), admin, new FeaturesCommand(this),
                new ImportCommand(this, hearts, tracker), settingsMenu, caps, id -> {
                    Player player = getServer().getPlayer(id);
                    return player != null && tagger.isTagged(player) || bodies.hasBody(id);
                }, new Dashboard(this, hearts, tracker, bloodMoon, airdrops, finale),
                List.of(audit.node(), backups.backupNode(), backups.exportNode()));
        PurgatoryCommands purgatoryCommands = settings.purgatory().enabled() ? startPurgatory(tracker) : null;
        capSchedule = new CapSchedule(this, hearts);
        SocialCommands social = new SocialCommands(this, hearts, tracker, heartItems, finale, purgatory);
        news = new NewsFeed(this, hearts, tracker);
        HeartShop shop = new HeartShop(this, heartItems,
                () -> getServer().getPluginManager().isPluginEnabled("Vault") ? VaultWallet.find(this) : null);
        getServer().getPluginManager().registerEvents(news, this);
        getServer().getPluginManager().registerEvents(new GhostChat(this, tracker), this);
        // Pact shares and pledge refunds happen inside other changes; mirror them and tell the players.
        hearts.onSideEffects(changes -> {
            tracker.track(CompletableFuture.completedFuture(changes));
            tracker.mainThread().execute(() -> changes.forEach(social::explainFollowOn));
        });
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            commands.register(event.registrar());
            social.register(event.registrar());
            news.register(event.registrar());
            event.registrar().register(shop.command(), "Buy heart items with money");
            event.registrar().register(admin.seasonsCommand(), "Past seasons and who won them");
            if (purgatoryCommands != null) purgatoryCommands.register(event.registrar());
        });

        // Players already online after a /reload never fire join; sync them now.
        getServer().getOnlinePlayers().forEach(player ->
                hearts.touch(player.getUniqueId(), player.getName())
                        .thenAcceptAsync(h -> tracker.joined(player, h), tracker.mainThread()));
    }

    /**
     * saveDefaultConfig never touches an existing config.yml, so settings added by later
     * versions would stay invisible to admins. Copy any that are missing in, with their
     * comments, and leave everything the admin already set alone.
     */
    private void addMissingSettings() {
        FileConfiguration config = getConfig();
        Configuration defaults = config.getDefaults();
        if (defaults == null) return;
        List<String> added = new ArrayList<>();
        for (String key : defaults.getKeys(true)) {
            if (config.isSet(key)) continue;
            if (defaults.isConfigurationSection(key)) {
                config.createSection(key);
            } else {
                config.set(key, defaults.get(key));
                added.add(key);
            }
            config.setComments(key, defaults.getComments(key));
        }
        if (added.isEmpty()) return;
        saveConfig();
        getLogger().info("Added new settings to config.yml: " + String.join(", ", added));
    }

    private void applyStoreSwitches() {
        PluginSettings s = settings;
        hearts.setFeatures(s.extras().shieldsEnabled(), s.extras().shieldExpiry(), s.social().pactsEnabled(), s.extras().altDetection());
        hearts.setStreaks(s.streaks());
        hearts.setRevivalRules(s.revival().cooldown(), s.revival().extraPerElimination());
    }

    private void logWarnings(List<String> warnings) {
        for (String warning : warnings) getLogger().warning("config.yml: " + warning);
    }

    /** Falls back to spectating (and says so) rather than failing the whole plugin if the world can't be made. */
    private PurgatoryCommands startPurgatory(HeartTracker tracker) {
        PurgatoryWorld world;
        try {
            world = PurgatoryWorld.load(this, settings.purgatory().world());
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Couldn't set up Purgatory; eliminated players will spectate instead", e);
            return null;
        }
        purgatory = new Purgatory(this, world);
        trials = new TrialManager(this, hearts, tracker, purgatory, finale::isActive);
        purgatory.setTrials(trials);
        tracker.setEliminationHandler(purgatory);
        getServer().getPluginManager().registerEvents(new PurgatoryListener(world, trials), this);
        return new PurgatoryCommands(this, hearts, tracker, trials);
    }

    @Override
    public void onDisable() {
        if (trials != null) trials.shutdown();
        if (bodies != null) bodies.shutdown();
        if (bloodMoon != null) bloodMoon.shutdown();
        if (airdrops != null) airdrops.shutdown();
        if (timedEvents != null) timedEvents.shutdown();
        if (koth != null) koth.shutdown();
        if (hearts == null) return;
        if (news != null) news.shutdown();
        try {
            hearts.close();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error closing the heart database", e);
        }
    }

    public PluginSettings settings() {
        return settings;
    }

    /**
     * Re-reads config.yml and messages.yml. Never fails: anything unusable falls back to its
     * default, and the returned warnings say which settings those were.
     */
    List<String> reloadSettings() {
        reloadConfig();
        addMissingSettings();
        Msg.load(this);
        PluginSettings.Loaded loaded = PluginSettings.load(getConfig());
        logWarnings(loaded.warnings());
        PluginSettings fresh = loaded.settings();
        boolean wantedTimerChanged = !fresh.mostWanted().interval().equals(settings.mostWanted().interval());
        settings = fresh;
        hearts.setRules(fresh.rules());
        capSchedule.refresh();
        if (wantedTimerChanged) mostWanted.schedule();
        applyStoreSwitches();
        shield.refresh();
        heartItems.syncRecipe();
        bloodMoon.settingsChanged();
        airdrops.settingsChanged();
        timedEvents.settingsChanged();
        menuLook.reload();
        return loaded.warnings();
    }

    /**
     * Changes one setting in config.yml (comments kept) and applies it straight away. Re-reads
     * the file first, so edits made there since the last reload aren't lost.
     *
     * @return why the value can't be used, or null once it's saved and applied
     */
    String changeSetting(String key, Object value) {
        return changeSettings(Map.of(key, value));
    }

    /** Like {@link #changeSetting}, for several settings at once (a preset): all of them or none. */
    String changeSettings(Map<String, Object> changes) {
        reloadConfig();
        String problem = SettingsCatalog.problem(getConfig(), changes);
        if (problem != null) return problem;
        changes.forEach(getConfig()::set);
        saveConfig();
        reloadSettings();
        return null;
    }
}
