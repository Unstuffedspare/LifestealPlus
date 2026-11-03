package dev.lifestealplus;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * %lifestealplus_...% for PlaceholderAPI. Only touched when PlaceholderAPI is installed: this
 * is the one class that refers to it. The values come from {@link PlaceholderData}.
 */
final class PlaceholderHook extends PlaceholderExpansion {

    private final LifestealPlugin plugin;
    private final PlaceholderData data;

    private PlaceholderHook(LifestealPlugin plugin, PlaceholderData data) {
        this.plugin = plugin;
        this.data = data;
    }

    static boolean hook(LifestealPlugin plugin, PlaceholderData data) {
        return new PlaceholderHook(plugin, data).register();
    }

    @Override
    public String getIdentifier() {
        return "lifestealplus";
    }

    @Override
    public String getAuthor() {
        return "LifestealPlus";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    /** Stay registered through /papi reload: the plugin is still here. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        return data.value(player, params);
    }
}
