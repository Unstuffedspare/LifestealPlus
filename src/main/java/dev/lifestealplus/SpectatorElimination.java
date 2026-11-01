package dev.lifestealplus;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/** The fallback when Purgatory is off: eliminated players watch in spectator mode. */
final class SpectatorElimination implements EliminationHandler {

    @Override
    public void eliminate(Player player) {
        player.setGameMode(GameMode.SPECTATOR);
        player.sendRichMessage("<red>You're out of hearts. You can watch, but you're eliminated until someone revives you.");
    }

    @Override
    public void enforce(Player player) {
        if (player.getGameMode() != GameMode.SPECTATOR) player.setGameMode(GameMode.SPECTATOR);
    }

    @Override
    public void restore(Player player, int hearts) {
        player.setGameMode(GameMode.SURVIVAL);
        player.teleport(EliminationHandler.home(player));
        player.setHealth(hearts * 2.0);
        player.sendRichMessage("<green>You're back in the game with <hearts> hearts.",
                Placeholder.unparsed("hearts", String.valueOf(hearts)));
    }
}
