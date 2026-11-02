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
import dev.lifestealplus.items.HeartItems;
import dev.lifestealplus.items.LifestealZItems;
import dev.lifestealplus.purgatory.Purgatory;
import dev.lifestealplus.purgatory.PurgatoryCommands;
import dev.lifestealplus.purgatory.PurgatoryListener;
import dev.lifestealplus.purgatory.PurgatoryWorld;
import dev.lifestealplus.purgatory.TrialManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class LifestealPlugin extends JavaPlugin {

    private volatile PluginSettings settings;
    private HeartService hearts;
    private TrialManager trials;
    private MostWanted mostWanted;
    private CombatBodies bodies;
    private BloodMoon bloodMoon;
    private Airdrops airdrops;
    private Finale finale;
    private Purgatory purgatory;
    private CapSchedule capSchedule;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        addMissingSettings();
        List<String> newMessages = Msg.load(this);
        if (!newMessages.isEmpty()) getLogger().info("Added new messages to messages.yml: " + String.join(", ", newMessages));
        PluginSettings.Loaded loaded = PluginSettings.load(getConfig());
        settings = loaded.settings();
        logWarnings(loaded.warnings());
        try {
            getDataFolder().mkdirs();
            HeartStore store = new HeartStore(getDataFolder().toPath().resolve(settings.databaseFile()), Clock.systemUTC());
            hearts = new HeartService(store, settings.rules());
            applyStoreSwitches();
        } catch (Exception e) {
            // Running without the ledger would let hearts change unrecorded, so don't run at all.
            getLogger().log(Level.SEVERE, "LifestealPlus couldn't start; disabling", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        HeartTracker tracker = new HeartTracker(this);
        KillHandler kills = new KillHandler(this, hearts, tracker);
        CombatTagger tagger = new CombatTagger(this, tracker);
        bodies = new CombatBodies(this, hearts, kills, tagger);
        getServer().getPluginManager().registerEvents(tagger, this);
        getServer().getPluginManager().registerEvents(bodies, this);
        NewbieShield shield = new NewbieShield(this, hearts);
        getServer().getPluginManager().registerEvents(shield, this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this, hearts, tracker, kills, bodies), this);
        HeartItems heartItems = new HeartItems(this, hearts, tracker);
        bloodMoon = new BloodMoon(this, hearts, heartItems);
        airdrops = new Airdrops(this, hearts, heartItems);
        finale = new Finale(this, hearts);
        for (var listener : List.of(heartItems, bloodMoon, airdrops, finale, new LifestealZItems(this, heartItems))) {
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
        AdminCommands admin = new AdminCommands(this, hearts, tracker,
                new SeasonReset(this, hearts, tracker, shield, bodies, finale, () -> capSchedule.seasonStarted()));
        LifestealCommands commands = new LifestealCommands(this, hearts, tracker, mostWanted, shield,
                new EventCommands(this, heartItems, bloodMoon, airdrops, finale), admin, new FeaturesCommand(this),
                new ImportCommand(this, hearts, tracker));
        PurgatoryCommands purgatoryCommands = settings.purgatory().enabled() ? startPurgatory(tracker) : null;
        capSchedule = new CapSchedule(this, hearts);
        SocialCommands social = new SocialCommands(this, hearts, tracker, heartItems, finale, purgatory);
        getServer().getPluginManager().registerEvents(new GhostChat(this, tracker), this);
        // Pact shares and pledge refunds happen inside other changes; mirror them and tell the players.
        hearts.onSideEffects(changes -> {
            tracker.track(CompletableFuture.completedFuture(changes));
            tracker.mainThread().execute(() -> changes.forEach(social::explainFollowOn));
        });
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            commands.register(event.registrar());
            social.register(event.registrar());
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
        hearts.setFeatures(s.extras().shieldsEnabled(), s.social().pactsEnabled(), s.extras().altDetection());
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
        if (hearts == null) return;
        try {
            hearts.close();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error closing the heart database", e);
        }
    }

    public PluginSettings settings() {
        return settings;
    }

    /** Re-reads config.yml. Throws (leaving the old settings in place) if the new values are invalid. */
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
        return loaded.warnings();
    }
}
