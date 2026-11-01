package dev.lifestealplus;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.lifestealplus.core.HeartChange;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.PlayerHearts;
import dev.lifestealplus.core.PledgeTotal;
import dev.lifestealplus.core.RuleException;
import dev.lifestealplus.events.Finale;
import dev.lifestealplus.items.HeartItems;
import dev.lifestealplus.purgatory.Purgatory;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /withdraw, /rift, /pledge, /pact, /ghost and /whisper: the ways players trade, rescue and bind themselves. */
final class SocialCommands {

    private static final Duration CONFIRM_WINDOW = Duration.ofSeconds(30);
    private static final Duration PACT_OFFER_WINDOW = Duration.ofSeconds(60);

    private record Pending(UUID target, String targetName, Instant expires) {
    }

    private final LifestealPlugin plugin;
    private final HeartService hearts;
    private final HeartTracker tracker;
    private final HeartItems items;
    private final Finale finale;
    /** Null when Purgatory is off: then there's nowhere for a ghost to come back to. */
    private final Purgatory purgatory;
    private final Map<UUID, Pending> riftRequests = new ConcurrentHashMap<>();
    /** Pact offers, keyed by who was asked. */
    private final Map<UUID, Pending> pactOffers = new ConcurrentHashMap<>();

    SocialCommands(LifestealPlugin plugin, HeartService hearts, HeartTracker tracker, HeartItems items, Finale finale,
                   Purgatory purgatory) {
        this.plugin = plugin;
        this.hearts = hearts;
        this.tracker = tracker;
        this.items = items;
        this.finale = finale;
        this.purgatory = purgatory;
    }

    void register(Commands commands) {
        commands.register(withdrawCommand(), "Turn some of your hearts into Heart items");
        commands.register(riftCommand(), "Pay hearts to pull an eliminated player out of Purgatory");
        commands.register(pledgeCommand(), "Pledge hearts toward bringing an eliminated player back");
        commands.register(pactCommand(), "Bind your fate to another player's");
        commands.register(ghostCommand(), "While eliminated, roam the overworld as a ghost");
        commands.register(whisperCommand(), "While eliminated, whisper to a living player once an hour");
    }

    private SocialSettings settings() {
        return plugin.settings().social();
    }

    // ---- /withdraw ----

    private LiteralCommandNode<CommandSourceStack> withdrawCommand() {
        return Commands.literal("withdraw")
                .executes(ctx -> asPlayer(ctx.getSource(), p -> withdraw(p, 1)))
                .then(Commands.argument("hearts", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> asPlayer(ctx.getSource(), p -> withdraw(p, IntegerArgumentType.getInteger(ctx, "hearts")))))
                .build();
    }

    private void withdraw(Player player, int amount) {
        hearts.withdraw(player.getUniqueId(), amount).whenCompleteAsync((change, error) -> {
            if (error != null) {
                refuse(player, error);
                return;
            }
            tracker.track(CompletableFuture.completedFuture(List.of(change)));
            player.getInventory().addItem(items.heart(amount)).values()
                    .forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
            player.sendRichMessage("<red>❤</red> <gray>You drew <n> heart(s) out into Heart items. You have <left> left. "
                            + "Held Hearts fade over time, so don't sit on them.",
                    Placeholder.unparsed("n", String.valueOf(amount)),
                    Placeholder.unparsed("left", String.valueOf(change.after())));
        }, tracker.mainThread());
    }

    // ---- /rift ----

    private LiteralCommandNode<CommandSourceStack> riftCommand() {
        return Commands.literal("rift")
                .then(Commands.literal("confirm").executes(ctx -> asPlayer(ctx.getSource(), this::confirmRift)))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> asPlayer(ctx.getSource(), p -> requestRift(p, StringArgumentType.getString(ctx, "player")))))
                .build();
    }

    private void requestRift(Player rescuer, String name) {
        if (sealed(rescuer)) return;
        withPlayer(rescuer, name, target -> {
            if (!target.eliminated()) {
                rescuer.sendRichMessage("<gray><player> isn't eliminated.", Placeholder.unparsed("player", target.name()));
                return;
            }
            riftRequests.put(rescuer.getUniqueId(), new Pending(target.uuid(), target.name(), Instant.now().plus(CONFIRM_WINDOW)));
            rescuer.sendRichMessage("<dark_purple>Opening a rift for <player> costs <cost> of your hearts</dark_purple> <gray>"
                            + "(they come back with <revive>). Run <white>/rift confirm</white> within 30 seconds.",
                    Placeholder.unparsed("player", target.name()),
                    Placeholder.unparsed("cost", String.valueOf(settings().riftCost())),
                    Placeholder.unparsed("revive", String.valueOf(hearts.rules().reviveHearts())));
        });
    }

    private void confirmRift(Player rescuer) {
        Pending request = riftRequests.remove(rescuer.getUniqueId());
        if (request == null || Instant.now().isAfter(request.expires())) {
            rescuer.sendRichMessage("<gray>Nothing to confirm. Start with /rift <player>.");
            return;
        }
        if (sealed(rescuer)) return;
        hearts.openRift(rescuer.getUniqueId(), request.target(), settings().riftCost()).whenCompleteAsync((changes, error) -> {
            if (error != null) {
                refuse(rescuer, error);
                return;
            }
            tracker.track(CompletableFuture.completedFuture(changes));
            rescuer.getWorld().strikeLightningEffect(rescuer.getLocation());
            broadcast("<dark_purple>⚡ <rescuer> tore open a rift and pulled <target> out of Purgatory.",
                    Placeholder.unparsed("rescuer", rescuer.getName()),
                    Placeholder.unparsed("target", request.targetName()));
        }, tracker.mainThread());
    }

    // ---- /pledge ----

    private LiteralCommandNode<CommandSourceStack> pledgeCommand() {
        return Commands.literal("pledge")
                .executes(ctx -> listPledges(ctx.getSource().getSender()))
                .then(Commands.literal("list").executes(ctx -> listPledges(ctx.getSource().getSender())))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> asPlayer(ctx.getSource(), p -> cancelPledge(p, StringArgumentType.getString(ctx, "player"))))))
                .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("hearts", IntegerArgumentType.integer(1))
                                .executes(ctx -> asPlayer(ctx.getSource(), p -> pledge(p, StringArgumentType.getString(ctx, "player"),
                                        IntegerArgumentType.getInteger(ctx, "hearts"))))))
                .build();
    }

    private void pledge(Player pledger, String name, int amount) {
        if (sealed(pledger)) return;
        withPlayer(pledger, name, target -> hearts.pledge(pledger.getUniqueId(), target.uuid(), amount,
                settings().pledgeHeartsNeeded(), settings().pledgeMinVoters()).whenCompleteAsync((outcome, error) -> {
            if (error != null) {
                refuse(pledger, error);
                return;
            }
            tracker.track(CompletableFuture.completedFuture(outcome.revive() == null
                    ? List.of(outcome.paid()) : List.of(outcome.paid(), outcome.revive())));
            PledgeTotal total = outcome.total();
            if (outcome.revive() != null) {
                broadcast("<green>🗳 <voters> players pledged <hearts> hearts and voted <target> back to life.",
                        Placeholder.unparsed("voters", String.valueOf(total.voters())),
                        Placeholder.unparsed("hearts", String.valueOf(total.hearts())),
                        Placeholder.unparsed("target", target.name()));
            } else {
                broadcast("<yellow>🗳 <pledger> pledged <n> heart(s) to bring <target> back</yellow> <gray>(<hearts>/<needed> hearts, "
                                + "<voters>/<min> players). /pledge <target> <amount> to chip in.",
                        Placeholder.unparsed("pledger", pledger.getName()),
                        Placeholder.unparsed("n", String.valueOf(amount)),
                        Placeholder.unparsed("target", target.name()),
                        Placeholder.unparsed("hearts", String.valueOf(total.hearts())),
                        Placeholder.unparsed("needed", String.valueOf(settings().pledgeHeartsNeeded())),
                        Placeholder.unparsed("voters", String.valueOf(total.voters())),
                        Placeholder.unparsed("min", String.valueOf(settings().pledgeMinVoters())));
            }
        }, tracker.mainThread()));
    }

    private void cancelPledge(Player pledger, String name) {
        withPlayer(pledger, name, target -> hearts.cancelPledge(pledger.getUniqueId(), target.uuid()).whenCompleteAsync((refund, error) -> {
            if (error != null) {
                refuse(pledger, error);
                return;
            }
            tracker.track(CompletableFuture.completedFuture(List.of(refund)));
            pledger.sendRichMessage("<gray>You took back your pledge for <target> (<n> hearts).",
                    Placeholder.unparsed("target", target.name()),
                    Placeholder.unparsed("n", String.valueOf(refund.delta())));
        }, tracker.mainThread()));
    }

    private int listPledges(CommandSender sender) {
        hearts.pledges().whenCompleteAsync((totals, error) -> {
            if (error != null) {
                refuse(sender, error);
                return;
            }
            if (totals.isEmpty()) {
                sender.sendRichMessage("<gray>Nobody has pledged for anyone yet. /pledge <player> <hearts> to start.");
                return;
            }
            sender.sendRichMessage("<yellow>Revival pledges <gray>(<needed> hearts from <min> players brings someone back)",
                    Placeholder.unparsed("needed", String.valueOf(settings().pledgeHeartsNeeded())),
                    Placeholder.unparsed("min", String.valueOf(settings().pledgeMinVoters())));
            for (PledgeTotal t : totals) {
                sender.sendRichMessage(" <white><target></white> <gray><hearts> hearts from <voters> player(s)",
                        Placeholder.unparsed("target", t.targetName()),
                        Placeholder.unparsed("hearts", String.valueOf(t.hearts())),
                        Placeholder.unparsed("voters", String.valueOf(t.voters())));
            }
        }, tracker.mainThread());
        return Command.SINGLE_SUCCESS;
    }

    // ---- /pact ----

    private LiteralCommandNode<CommandSourceStack> pactCommand() {
        return Commands.literal("pact")
                .executes(ctx -> asPlayer(ctx.getSource(), this::pactStatus))
                .then(Commands.literal("accept")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> asPlayer(ctx.getSource(), p -> acceptPact(p, StringArgumentType.getString(ctx, "player"))))))
                .then(Commands.literal("break").executes(ctx -> asPlayer(ctx.getSource(), this::breakPact)))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> asPlayer(ctx.getSource(), p -> offerPact(p, StringArgumentType.getString(ctx, "player")))))
                .build();
    }

    private void pactStatus(Player player) {
        hearts.pactPartner(player.getUniqueId()).whenCompleteAsync((partner, error) -> {
            if (error != null) {
                refuse(player, error);
            } else if (partner.isPresent()) {
                player.sendRichMessage("<dark_red>🩸 You're in a Blood Pact with <partner>.</dark_red> <gray>Their gains are yours; "
                        + "their deaths cost you a heart. /pact break ends it.", Placeholder.unparsed("partner", partner.get().name()));
            } else {
                player.sendRichMessage("<gray>You're not in a Blood Pact. /pact <player> offers one.");
            }
        }, tracker.mainThread());
    }

    private void offerPact(Player from, String name) {
        Player to = plugin.getServer().getPlayerExact(name);
        if (to == null || to.equals(from)) {
            from.sendRichMessage("<gray>Offer a pact to someone else who's online.");
            return;
        }
        pactOffers.put(to.getUniqueId(), new Pending(from.getUniqueId(), from.getName(), Instant.now().plus(PACT_OFFER_WINDOW)));
        from.sendRichMessage("<dark_red>🩸 You offered <player> a Blood Pact.</dark_red> <gray>They have a minute to accept.",
                Placeholder.unparsed("player", to.getName()));
        to.sendRichMessage("<dark_red>🩸 <player> offers you a Blood Pact:</dark_red> <gray>you'd share the hearts each of you wins "
                        + "from kills and bounties, and when either of you dies the other loses a heart too.</gray> "
                        + "<click:run_command:'/pact accept <player>'><red><u>[Accept]</u></red></click>",
                Placeholder.unparsed("player", from.getName()));
    }

    private void acceptPact(Player accepter, String name) {
        Pending offer = pactOffers.get(accepter.getUniqueId());
        if (offer == null || !offer.targetName().equalsIgnoreCase(name) || Instant.now().isAfter(offer.expires())) {
            accepter.sendRichMessage("<gray>No pact offer from <player> to accept.", Placeholder.unparsed("player", name));
            return;
        }
        pactOffers.remove(accepter.getUniqueId());
        hearts.formPact(offer.target(), accepter.getUniqueId()).whenCompleteAsync((ok, error) -> {
            if (error != null) {
                refuse(accepter, error);
                return;
            }
            broadcast("<dark_red>🩸 <a> and <b> have sealed a Blood Pact.",
                    Placeholder.unparsed("a", offer.targetName()), Placeholder.unparsed("b", accepter.getName()));
            for (Player p : plugin.getServer().getOnlinePlayers()) p.playSound(p, Sound.ENTITY_WARDEN_HEARTBEAT, 0.7f, 0.8f);
        }, tracker.mainThread());
    }

    private void breakPact(Player player) {
        hearts.breakPact(player.getUniqueId(), settings().pactBreakCooldown()).whenCompleteAsync((partner, error) -> {
            if (error != null) {
                refuse(player, error);
                return;
            }
            broadcast("<gray>🩸 <a> has broken their Blood Pact with <b>.",
                    Placeholder.unparsed("a", player.getName()), Placeholder.unparsed("b", partner.name()));
        }, tracker.mainThread());
    }

    // ---- /ghost and /whisper ----

    private LiteralCommandNode<CommandSourceStack> ghostCommand() {
        return Commands.literal("ghost").executes(ctx -> asPlayer(ctx.getSource(), player -> {
            if (!tracker.isEliminated(player)) {
                player.sendRichMessage("<gray>Only the eliminated can walk as ghosts.");
            } else if (purgatory == null) {
                player.sendRichMessage("<gray>You're already spectating; /whisper still works.");
            } else {
                purgatory.toggleGhost(player);
            }
        })).build();
    }

    private LiteralCommandNode<CommandSourceStack> whisperCommand() {
        return Commands.literal("whisper")
                .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> asPlayer(ctx.getSource(), ghost -> whisper(ghost,
                                        StringArgumentType.getString(ctx, "player"), StringArgumentType.getString(ctx, "message"))))))
                .build();
    }

    private void whisper(Player ghost, String name, String message) {
        if (!tracker.isEliminated(ghost)) {
            ghost.sendRichMessage("<gray>Only the eliminated whisper. You can just talk.");
            return;
        }
        Player living = plugin.getServer().getPlayerExact(name);
        if (living == null || tracker.isEliminated(living)) {
            ghost.sendRichMessage("<gray>Whispers only reach living players who are online.");
            return;
        }
        String key = "whisper." + ghost.getUniqueId();
        hearts.state(key).whenCompleteAsync((last, error) -> {
            if (error != null) {
                refuse(ghost, error);
                return;
            }
            Instant ready = last.map(v -> Instant.ofEpochMilli(Long.parseLong(v)).plus(settings().whisperCooldown())).orElse(Instant.MIN);
            if (Instant.now().isBefore(ready)) {
                long minutes = Math.max(1, Duration.between(Instant.now(), ready).toMinutes());
                ghost.sendRichMessage("<gray>Your voice is spent. You can whisper again in <m> minute(s).",
                        Placeholder.unparsed("m", String.valueOf(minutes)));
                return;
            }
            hearts.putState(key, String.valueOf(Instant.now().toEpochMilli()));
            living.sendRichMessage("<gray><italic>👻 <ghost>'s ghost whispers to you: <message>",
                    Placeholder.unparsed("ghost", ghost.getName()), Placeholder.unparsed("message", message));
            living.playSound(living, Sound.AMBIENT_SOUL_SAND_VALLEY_ADDITIONS, 1f, 1.4f);
            ghost.sendRichMessage("<gray>👻 Your whisper reaches <player>.", Placeholder.unparsed("player", living.getName()));
        }, tracker.mainThread());
    }

    // ---- helpers ----

    /** Rifts and pledges bring people back; the finale says nobody comes back. */
    private boolean sealed(Player player) {
        if (!finale.isActive()) return false;
        player.sendRichMessage("<dark_red>Purgatory is sealed for the finale. Nobody returns now.");
        return true;
    }

    private void withPlayer(CommandSender sender, String name, Consumer<PlayerHearts> then) {
        hearts.findByName(name).whenCompleteAsync((found, error) -> {
            if (error != null) refuse(sender, error);
            else if (found.isEmpty()) sender.sendRichMessage("<red>No player named <name> has joined.", Placeholder.unparsed("name", name));
            else then.accept(found.get());
        }, tracker.mainThread());
    }

    private int asPlayer(CommandSourceStack source, Consumer<Player> action) {
        if (source.getSender() instanceof Player player) action.accept(player);
        else source.getSender().sendRichMessage("<red>Only players can use this.");
        return Command.SINGLE_SUCCESS;
    }

    /** Rule breaks go to the player as-is; anything else is a real error for the log. */
    private void refuse(CommandSender sender, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof RuleException rule) {
            sender.sendRichMessage("<red><reason>", Placeholder.unparsed("reason", rule.getMessage()));
            return;
        }
        plugin.getLogger().log(Level.SEVERE, "Command failed", error);
        sender.sendRichMessage("<red>That didn't work; the error is in the server log.");
    }

    private void broadcast(String message, TagResolver... tags) {
        plugin.getServer().broadcast(MiniMessage.miniMessage().deserialize(message, tags));
    }

    /** What a player hears when someone else's change moved their hearts: a pact share or a refunded pledge. */
    void explainFollowOn(HeartChange change) {
        Player player = plugin.getServer().getPlayer(change.player());
        if (player == null || change.delta() == 0) return;
        String other = change.otherName() == null ? "someone" : change.otherName();
        switch (change.cause()) {
            case PACT_SHARE -> player.sendRichMessage(change.delta() > 0
                            ? "<dark_red>🩸 Blood Pact:</dark_red> <green><other> won hearts, so you gain <n> too."
                            : "<dark_red>🩸 Blood Pact:</dark_red> <red><other> died, and it costs you a heart.",
                    Placeholder.unparsed("other", other), Placeholder.unparsed("n", String.valueOf(change.delta())));
            case REVIVAL_REFUND -> player.sendRichMessage("<gray><other> came back another way, so your pledge of <n> heart(s) is returned.",
                    Placeholder.unparsed("other", other), Placeholder.unparsed("n", String.valueOf(change.delta())));
            default -> { }
        }
    }
}
