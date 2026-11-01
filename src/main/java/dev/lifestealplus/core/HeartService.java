package dev.lifestealplus.core;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The one way into heart state. All work runs in order on a single database thread,
 * so two changes to the same player (a kill landing while an admin edits them) can
 * never interleave. Callers get futures and hop back to the main thread themselves.
 */
public final class HeartService implements AutoCloseable {

    private final HeartStore store;
    private final ExecutorService db = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LifestealPlus-DB");
        t.setDaemon(true);
        return t;
    });
    private volatile HeartRules rules;

    public HeartService(HeartStore store, HeartRules rules) {
        this.store = store;
        this.rules = rules;
    }

    public HeartRules rules() {
        return rules;
    }

    public void setRules(HeartRules rules) {
        this.rules = rules;
    }

    public CompletableFuture<PlayerHearts> touch(UUID uuid, String name) {
        return submit(() -> store.touch(uuid, name, rules));
    }

    public CompletableFuture<List<HeartChange>> apply(List<HeartRequest> requests) {
        return submit(() -> store.apply(requests, rules));
    }

    public CompletableFuture<HeartChange> apply(HeartRequest request) {
        return apply(List.of(request)).thenApply(List::getFirst);
    }

    public CompletableFuture<KillOutcome> kill(UUID victim, UUID killer) {
        return submit(() -> store.kill(victim, killer, rules));
    }

    /** Fails with {@link BountyException} (wrapped) when the rules don't allow it. */
    public CompletableFuture<BountyPlacement> placeBounty(UUID placer, UUID target, int hearts) {
        return submit(() -> store.placeBounty(placer, target, hearts, rules));
    }

    public CompletableFuture<List<HeartChange>> clearBounties(UUID target, String actor) {
        return submit(() -> store.clearBounties(target, actor, rules));
    }

    public CompletableFuture<Integer> bountyOn(UUID target) {
        return submit(() -> store.bountyOn(target));
    }

    public CompletableFuture<List<BountyTotal>> topBounties(int limit) {
        return submit(() -> store.topBounties(limit));
    }

    /** Marks this player can use, then marks others hold on them. */
    public CompletableFuture<Marks> marks(UUID player) {
        return submit(() -> new Marks(store.marksHeldBy(player), store.marksOn(player)));
    }

    public record Marks(List<VengeanceMark> held, List<VengeanceMark> against) {
    }

    public CompletableFuture<PurgatoryProgress> progress(UUID player) {
        return submit(() -> store.progress(player));
    }

    public CompletableFuture<TrialReward> awardFragment(UUID player, int needed) {
        return submit(() -> store.awardFragment(player, needed, rules));
    }

    public CompletableFuture<Optional<PlayerHearts>> find(UUID uuid) {
        return submit(() -> store.find(uuid));
    }

    public CompletableFuture<Optional<PlayerHearts>> findByName(String name) {
        return submit(() -> store.findByName(name));
    }

    public CompletableFuture<LedgerPage> ledger(UUID player, int page, int pageSize) {
        return submit(() -> new LedgerPage(
                store.ledger(player, pageSize, (page - 1) * pageSize),
                page,
                Math.max(1, (store.ledgerSize(player) + pageSize - 1) / pageSize)));
    }

    public record LedgerPage(List<LedgerEntry> entries, int page, int pages) {
    }

    @FunctionalInterface
    private interface StoreCall<T> {
        T call() throws SQLException;
    }

    private <T> CompletableFuture<T> submit(StoreCall<T> call) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return call.call();
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, db);
    }

    /** Finishes queued writes (deaths right before shutdown still get recorded), then closes the database. */
    @Override
    public void close() throws SQLException, InterruptedException {
        db.shutdown();
        if (!db.awaitTermination(10, TimeUnit.SECONDS)) db.shutdownNow();
        store.close();
    }
}
