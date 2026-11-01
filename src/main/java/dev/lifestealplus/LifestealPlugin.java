package dev.lifestealplus;

import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.HeartStore;
import dev.lifestealplus.purgatory.Purgatory;
import dev.lifestealplus.purgatory.PurgatoryCommands;
import dev.lifestealplus.purgatory.PurgatoryListener;
import dev.lifestealplus.purgatory.PurgatoryWorld;
import dev.lifestealplus.purgatory.TrialManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class LifestealPlugin extends JavaPlugin {

    private volatile PluginSettings settings;
    private HeartService hearts;
    private TrialManager trials;
    private MostWanted mostWanted;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        addMissingSettings();
        try {
            settings = PluginSettings.from(getConfig());
            getDataFolder().mkdirs();
            HeartStore store = new HeartStore(getDataFolder().toPath().resolve(settings.databaseFile()), Clock.systemUTC());
            hearts = new HeartService(store, settings.rules());
        } catch (Exception e) {
            // Running without the ledger would let hearts change unrecorded, so don't run at all.
            getLogger().log(Level.SEVERE, "LifestealPlus couldn't start; disabling", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        HeartTracker tracker = new HeartTracker(this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this, hearts, tracker), this);
        mostWanted = new MostWanted(this, tracker);
        mostWanted.schedule();
        LifestealCommands commands = new LifestealCommands(this, hearts, tracker, mostWanted);
        PurgatoryCommands purgatoryCommands = settings.purgatory().enabled() ? startPurgatory(tracker) : null;
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            commands.register(event.registrar());
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

    /** Falls back to spectating (and says so) rather than failing the whole plugin if the world can't be made. */
    private PurgatoryCommands startPurgatory(HeartTracker tracker) {
        PurgatoryWorld world;
        try {
            world = PurgatoryWorld.load(this, settings.purgatory().world());
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Couldn't set up Purgatory; eliminated players will spectate instead", e);
            return null;
        }
        Purgatory purgatory = new Purgatory(this, world);
        trials = new TrialManager(this, hearts, tracker, purgatory);
        purgatory.setTrials(trials);
        tracker.setEliminationHandler(purgatory);
        getServer().getPluginManager().registerEvents(new PurgatoryListener(world, trials), this);
        return new PurgatoryCommands(this, hearts, tracker, trials);
    }

    @Override
    public void onDisable() {
        if (trials != null) trials.shutdown();
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
    void reloadSettings() {
        reloadConfig();
        PluginSettings fresh = PluginSettings.from(getConfig());
        boolean wantedTimerChanged = !fresh.mostWanted().interval().equals(settings.mostWanted().interval());
        settings = fresh;
        hearts.setRules(fresh.rules());
        if (wantedTimerChanged) mostWanted.schedule();
    }
}
