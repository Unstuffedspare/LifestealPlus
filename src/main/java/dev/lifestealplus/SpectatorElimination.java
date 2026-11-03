package dev.lifestealplus;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/** The fallback when Purgatory is off: eliminated players watch in spectator mode. */
final class SpectatorElimination implements EliminationHandler {

    @Override
    public void eliminate(Player player) {
        player.setGameMode(GameMode.SPECTATOR);
        Msg.send(player, "elimination.spectating");
    }

    @Override
    public void enforce(Player player) {
        if (player.getGameMode() != GameMode.SPECTATOR) player.setGameMode(GameMode.SPECTATOR);
    }

    @Override
    public void restore(Player player, int hearts) {
        player.setGameMode(GameMode.SURVIVAL);
        player.teleportAsync(EliminationHandler.home(player));
        player.setHealth(hearts * 2.0);
        Msg.send(player, "elimination.back-from-spectating", Msg.t("hearts", hearts));
    }
}
