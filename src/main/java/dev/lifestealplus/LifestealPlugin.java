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
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

public final class LifestealPlugin extends JavaPlugin {

    private volatile PluginSettings settings;
    private HeartService hearts;
    private TrialManager trials;

    @Override
    public void onEnable() {
        saveDefaultConfig();
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
        LifestealCommands commands = new LifestealCommands(this, hearts, tracker);
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
        settings = fresh;
        hearts.setRules(fresh.rules());
    }
}
