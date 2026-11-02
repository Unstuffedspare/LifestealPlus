package dev.lifestealplus;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.lifestealplus.core.HeartChange;
import dev.lifestealplus.core.HeartService;
import dev.lifestealplus.core.PactPair;
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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;
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
                .requires(src -> src.getSender().hasPermission("lifestealplus.withdraw"))
                .executes(ctx -> asPlayer(ctx.getSource(), () -> settings().withdrawEnabled(), p -> withdraw(p, 1)))
                .then(Commands.argument("hearts", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> asPlayer(ctx.getSource(), () -> settings().withdrawEnabled(),
                                p -> withdraw(p, IntegerArgumentType.getInteger(ctx, "hearts")))))
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
            Msg.send(player, "withdraw.done", Msg.t("n", amount), Msg.t("left", change.after()));
        }, tracker.mainThread());
    }

    // ---- /rift ----

    private LiteralCommandNode<CommandSourceStack> riftCommand() {
        BooleanSupplier on = () -> settings().riftsEnabled();
        return Commands.literal("rift")
                .requires(src -> src.getSender().hasPermission("lifestealplus.rift"))
                .then(Commands.literal("confirm").executes(ctx -> asPlayer(ctx.getSource(), on, this::confirmRift)))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> asPlayer(ctx.getSource(), on, p -> requestRift(p, StringArgumentType.getString(ctx, "player")))))
                .build();
    }

    private void requestRift(Player rescuer, String name) {
        if (sealed(rescuer)) return;
        withPlayer(rescuer, name, target -> {
            if (!target.eliminated()) {
                Msg.send(rescuer, "rift.not-eliminated", Msg.t("player", target.name()));
                return;
            }
            riftRequests.put(rescuer.getUniqueId(), new Pending(target.uuid(), target.name(), Instant.now().plus(CONFIRM_WINDOW)));
            Msg.send(rescuer, "rift.confirm", Msg.t("player", target.name()), Msg.t("cost", settings().riftCost()),
                    Msg.t("revive", hearts.rules().reviveHearts()));
        });
    }

    private void confirmRift(Player rescuer) {
        Pending request = riftRequests.remove(rescuer.getUniqueId());
        if (request == null || Instant.now().isAfter(request.expires())) {
            Msg.send(rescuer, "rift.nothing-to-confirm");
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
            Msg.broadcast("rift.opened", Msg.t("rescuer", rescuer.getName()), Msg.t("target", request.targetName()));
        }, tracker.mainThread());
    }

    // ---- /pledge ----

    private LiteralCommandNode<CommandSourceStack> pledgeCommand() {
        BooleanSupplier on = () -> settings().pledgesEnabled();
        return Commands.literal("pledge")
                .requires(src -> src.getSender().hasPermission("lifestealplus.pledge"))
                .executes(ctx -> listPledges(ctx.getSource().getSender()))
                .then(Commands.literal("list").executes(ctx -> listPledges(ctx.getSource().getSender())))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> asPlayer(ctx.getSource(), () -> true,
                                        p -> cancelPledge(p, StringArgumentType.getString(ctx, "player"))))))
                .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("hearts", IntegerArgumentType.integer(1))
                                .executes(ctx -> asPlayer(ctx.getSource(), on, p -> pledge(p, StringArgumentType.getString(ctx, "player"),
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
                Msg.broadcast("pledge.voted-back", Msg.t("voters", total.voters()), Msg.t("hearts", total.hearts()),
                        Msg.t("target", target.name()));
            } else {
                Msg.broadcast("pledge.progress", Msg.t("pledger", pledger.getName()), Msg.t("n", amount),
                        Msg.t("target", target.name()), Msg.t("hearts", total.hearts()),
                        Msg.t("needed", settings().pledgeHeartsNeeded()), Msg.t("voters", total.voters()),
                        Msg.t("min", settings().pledgeMinVoters()));
            }
        }, tracker.mainThread()));
    }

    /** Always allowed, even with votes switched off, so nobody's hearts get stuck in a pledge. */
    private void cancelPledge(Player pledger, String name) {
        withPlayer(pledger, name, target -> hearts.cancelPledge(pledger.getUniqueId(), target.uuid()).whenCompleteAsync((refund, error) -> {
            if (error != null) {
                refuse(pledger, error);
                return;
            }
            tracker.track(CompletableFuture.completedFuture(List.of(refund)));
            Msg.send(pledger, "pledge.cancelled", Msg.t("target", target.name()), Msg.t("n", refund.delta()));
        }, tracker.mainThread()));
    }

    private int listPledges(CommandSender sender) {
        hearts.pledges().whenCompleteAsync((totals, error) -> {
            if (error != null) {
                refuse(sender, error);
                return;
            }
            if (totals.isEmpty()) {
                Msg.send(sender, "pledge.list-empty");
                return;
            }
            Msg.send(sender, "pledge.list-header", Msg.t("needed", settings().pledgeHeartsNeeded()),
                    Msg.t("min", settings().pledgeMinVoters()));
            for (PledgeTotal t : totals) {
                Msg.send(sender, "pledge.list-line", Msg.t("target", t.targetName()), Msg.t("hearts", t.hearts()),
                        Msg.t("voters", t.voters()));
            }
        }, tracker.mainThread());
        return Command.SINGLE_SUCCESS;
    }

    // ---- /pact ----

    private LiteralCommandNode<CommandSourceStack> pactCommand() {
        BooleanSupplier on = () -> settings().pactsEnabled();
        return Commands.literal("pact")
                .requires(src -> src.getSender().hasPermission("lifestealplus.pact"))
                .executes(ctx -> asPlayer(ctx.getSource(), on, this::pactStatus))
                .then(Commands.literal("list").executes(ctx -> listPacts(ctx.getSource().getSender())))
                .then(Commands.literal("accept")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> asPlayer(ctx.getSource(), on, p -> acceptPact(p, StringArgumentType.getString(ctx, "player"))))))
                .then(Commands.literal("break").executes(ctx -> asPlayer(ctx.getSource(), on, this::breakPact)))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> asPlayer(ctx.getSource(), on, p -> offerPact(p, StringArgumentType.getString(ctx, "player")))))
                .build();
    }

    private void pactStatus(Player player) {
        hearts.pactPartner(player.getUniqueId()).whenCompleteAsync((partner, error) -> {
            if (error != null) refuse(player, error);
            else if (partner.isPresent()) Msg.send(player, "pact.status", Msg.t("partner", partner.get().name()));
            else Msg.send(player, "pact.none");
        }, tracker.mainThread());
    }

    private int listPacts(CommandSender sender) {
        if (!settings().pactsEnabled()) {
            Msg.send(sender, "common.feature-off");
            return Command.SINGLE_SUCCESS;
        }
        hearts.allPacts().whenCompleteAsync((pacts, error) -> {
            if (error != null) {
                refuse(sender, error);
                return;
            }
            if (pacts.isEmpty()) {
                Msg.send(sender, "pact.list-empty");
                return;
            }
            Msg.send(sender, "pact.list-header");
            for (PactPair pact : pacts) Msg.send(sender, "pact.list-line", Msg.t("a", pact.firstName()), Msg.t("b", pact.secondName()));
        }, tracker.mainThread());
        return Command.SINGLE_SUCCESS;
    }

    private void offerPact(Player from, String name) {
        Player to = plugin.getServer().getPlayerExact(name);
        if (to == null || to.equals(from)) {
            Msg.send(from, "pact.offer-someone-online");
            return;
        }
        pactOffers.put(to.getUniqueId(), new Pending(from.getUniqueId(), from.getName(), Instant.now().plus(PACT_OFFER_WINDOW)));
        Msg.send(from, "pact.offered", Msg.t("player", to.getName()));
        Msg.send(to, "pact.offer", Msg.t("player", from.getName()));
    }

    private void acceptPact(Player accepter, String name) {
        Pending offer = pactOffers.get(accepter.getUniqueId());
        if (offer == null || !offer.targetName().equalsIgnoreCase(name) || Instant.now().isAfter(offer.expires())) {
            Msg.send(accepter, "pact.no-offer", Msg.t("player", name));
            return;
        }
        pactOffers.remove(accepter.getUniqueId());
        hearts.formPact(offer.target(), accepter.getUniqueId()).whenCompleteAsync((ok, error) -> {
            if (error != null) {
                refuse(accepter, error);
                return;
            }
            Msg.broadcast("pact.sealed", Msg.t("a", offer.targetName()), Msg.t("b", accepter.getName()));
            for (Player p : plugin.getServer().getOnlinePlayers()) p.playSound(p, Sound.ENTITY_WARDEN_HEARTBEAT, 0.7f, 0.8f);
        }, tracker.mainThread());
    }

    private void breakPact(Player player) {
        hearts.breakPact(player.getUniqueId(), settings().pactBreakCooldown()).whenCompleteAsync((partner, error) -> {
            if (error != null) {
                refuse(player, error);
                return;
            }
            Msg.broadcast("pact.broken", Msg.t("a", player.getName()), Msg.t("b", partner.name()));
        }, tracker.mainThread());
    }

    // ---- /ghost and /whisper ----

    private LiteralCommandNode<CommandSourceStack> ghostCommand() {
        return Commands.literal("ghost")
                .requires(src -> src.getSender().hasPermission("lifestealplus.ghost")).executes(ctx -> asPlayer(ctx.getSource(), () -> settings().ghostsEnabled(), player -> {
            if (!tracker.isEliminated(player)) {
                Msg.send(player, "ghosts.only-eliminated");
            } else if (purgatory == null) {
                Msg.send(player, "ghosts.already-spectating");
            } else {
                purgatory.toggleGhost(player);
            }
        })).build();
    }

    private LiteralCommandNode<CommandSourceStack> whisperCommand() {
        return Commands.literal("whisper")
                .requires(src -> src.getSender().hasPermission("lifestealplus.whisper"))
                .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> asPlayer(ctx.getSource(), () -> settings().whispersEnabled(), ghost -> whisper(ghost,
                                        StringArgumentType.getString(ctx, "player"), StringArgumentType.getString(ctx, "message"))))))
                .build();
    }

    private void whisper(Player ghost, String name, String message) {
        if (!tracker.isEliminated(ghost)) {
            Msg.send(ghost, "ghosts.whisper-only-eliminated");
            return;
        }
        Player living = plugin.getServer().getPlayerExact(name);
        if (living == null || tracker.isEliminated(living)) {
            Msg.send(ghost, "ghosts.whisper-no-target");
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
                Msg.send(ghost, "ghosts.voice-spent", Msg.t("time", Msg.duration(Duration.between(Instant.now(), ready))));
                return;
            }
            hearts.putState(key, String.valueOf(Instant.now().toEpochMilli()));
            Msg.send(living, "ghosts.whisper-received", Msg.t("ghost", ghost.getName()), Msg.t("message", message));
            living.playSound(living, Sound.AMBIENT_SOUL_SAND_VALLEY_ADDITIONS, 1f, 1.4f);
            Msg.send(ghost, "ghosts.whisper-sent", Msg.t("player", living.getName()));
        }, tracker.mainThread());
    }

    // ---- helpers ----

    /** Rifts and pledges bring people back; the finale says nobody comes back. */
    private boolean sealed(Player player) {
        if (!finale.isActive()) return false;
        Msg.send(player, "finale.purgatory-sealed");
        return true;
    }

    private void withPlayer(CommandSender sender, String name, Consumer<PlayerHearts> then) {
        hearts.findByName(name).whenCompleteAsync((found, error) -> {
            if (error != null) refuse(sender, error);
            else if (found.isEmpty()) Msg.send(sender, "common.no-such-player", Msg.t("name", name));
            else then.accept(found.get());
        }, tracker.mainThread());
    }

    /** Runs a player-only command, unless its feature has been switched off in config.yml. */
    private int asPlayer(CommandSourceStack source, BooleanSupplier enabled, Consumer<Player> action) {
        if (!(source.getSender() instanceof Player player)) Msg.send(source.getSender(), "common.players-only");
        else if (!enabled.getAsBoolean()) Msg.send(player, "common.feature-off");
        else action.accept(player);
        return Command.SINGLE_SUCCESS;
    }

    /** Rule breaks go to the player as-is; anything else is a real error for the log. */
    private void refuse(CommandSender sender, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof RuleException rule) {
            Msg.send(sender, "common.not-allowed", Msg.t("reason", rule.getMessage()));
            return;
        }
        plugin.getLogger().log(Level.SEVERE, "Command failed", error);
        Msg.send(sender, "common.error");
    }

    /** What a player hears when someone else's change moved their hearts: a pact share or a refunded pledge. */
    void explainFollowOn(HeartChange change) {
        Player player = plugin.getServer().getPlayer(change.player());
        if (player == null || change.delta() == 0) return;
        String other = change.otherName() == null ? "?" : change.otherName();
        switch (change.cause()) {
            case PACT_SHARE -> Msg.send(player, change.delta() > 0 ? "pact.shared-gain" : "pact.shared-death",
                    Msg.t("other", other), Msg.t("n", Math.abs(change.delta())));
            case REVIVAL_REFUND -> Msg.send(player, "pledge.refunded", Msg.t("other", other), Msg.t("n", change.delta()));
            default -> { }
        }
    }
}
