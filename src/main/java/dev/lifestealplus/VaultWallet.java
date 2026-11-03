package dev.lifestealplus;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/** The heart shop's money, through Vault. Only touched when Vault is installed: this is the one class that refers to it. */
final class VaultWallet implements HeartShop.Wallet {

    private final Economy economy;

    private VaultWallet(Economy economy) {
        this.economy = economy;
    }

    /** The server's economy, or null if Vault has none registered (no economy plugin). */
    static VaultWallet find(LifestealPlugin plugin) {
        RegisteredServiceProvider<Economy> provider = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        return provider == null ? null : new VaultWallet(provider.getProvider());
    }

    @Override
    public boolean charge(Player player, double amount) {
        if (!economy.has(player, amount)) return false;
        return economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    @Override
    public String format(double amount) {
        return economy.format(amount);
    }
}
