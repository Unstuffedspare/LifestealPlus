package dev.lifestealplus.core;

import java.sql.SQLException;
import java.time.Duration;
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
    /** The rising-cap schedule's current cap, or null to use the configured one. */
    private volatile Integer capOverride;
    /** Gets heart changes that followed from another (pact shares, pledge refunds), on the database thread. */
    private volatile java.util.function.Consumer<List<HeartChange>> sideEffects = changes -> { };
    /** Multiplies heartsPerKill while an event (a Blood Moon) is running. */
    private volatile int killMultiplier = 1;

    public HeartService(HeartStore store, HeartRules rules) {
        this.store = store;
        this.rules = rules;
    }

    /** The rules in force right now: the configured ones, with the scheduled cap if there is one. */
    public HeartRules rules() {
        Integer cap = capOverride;
        return cap == null ? rules : rules.withMaxHearts(cap);
    }

    public void setCapOverride(Integer cap) {
        this.capOverride = cap;
    }

    public void onSideEffects(java.util.function.Consumer<List<HeartChange>> listener) {
        this.sideEffects = listener;
    }

    public CompletableFuture<HeartChange> withdraw(UUID player, int hearts) {
        return submit(() -> store.withdraw(player, hearts, rules()));
    }

    public CompletableFuture<List<HeartChange>> openRift(UUID rescuer, UUID target, int cost) {
        return submit(() -> store.openRift(rescuer, target, cost, rules()));
    }

    public CompletableFuture<PledgeOutcome> pledge(UUID pledger, UUID target, int hearts, int needed, int minVoters) {
        return submit(() -> store.pledge(pledger, target, hearts, needed, minVoters, rules()));
    }

    public CompletableFuture<HeartChange> cancelPledge(UUID pledger, UUID target) {
        return submit(() -> store.cancelPledge(pledger, target, rules()));
    }

    public CompletableFuture<List<PledgeTotal>> pledges() {
        return submit(store::pledges);
    }

    public CompletableFuture<Void> formPact(UUID first, UUID second) {
        return submit(() -> {
            store.formPact(first, second);
            return null;
        });
    }

    public CompletableFuture<PlayerHearts> breakPact(UUID player, Duration cooldown) {
        return submit(() -> store.breakPact(player, cooldown));
    }

    /** Applies the feature switches that live in the store (shields, pacts, alt flags). */
    public CompletableFuture<Void> setFeatures(boolean shields, boolean pacts, boolean altFlags) {
        return submit(() -> {
            store.setFeatures(shields, pacts, altFlags);
            return null;
        });
    }

    public CompletableFuture<List<VengeanceMark>> liveMarks() {
        return submit(store::liveMarks);
    }

    public CompletableFuture<ImportResult> importPlayers(List<LifestealZImport.Planned> players, String source) {
        return submit(() -> store.importPlayers(players, source, rules()));
    }

    /** Players already known here, out of these; for an import preview. */
    public CompletableFuture<Integer> countKnown(List<UUID> players) {
        return submit(() -> {
            int known = 0;
            for (UUID id : players) if (store.find(id).isPresent()) known++;
            return known;
        });
    }

    public CompletableFuture<List<PactPair>> allPacts() {
        return submit(store::allPacts);
    }

    public CompletableFuture<Optional<PlayerHearts>> pactPartner(UUID player) {
        return submit(() -> store.pactPartner(player));
    }

    public void setRules(HeartRules rules) {
        this.rules = rules;
    }

    public CompletableFuture<PlayerHearts> touch(UUID uuid, String name) {
        return submit(() -> store.touch(uuid, name, rules()));
    }

    public CompletableFuture<List<HeartChange>> apply(List<HeartRequest> requests) {
        return submit(() -> store.apply(requests, rules()));
    }

    public CompletableFuture<HeartChange> apply(HeartRequest request) {
        return apply(List.of(request)).thenApply(List::getFirst);
    }

    public void setKillMultiplier(int multiplier) {
        this.killMultiplier = Math.max(1, multiplier);
    }

    public int killMultiplier() {
        return killMultiplier;
    }

    public CompletableFuture<KillOutcome> kill(UUID victim, UUID killer) {
        HeartRules now = rules();
        HeartRules applied = killMultiplier == 1 ? now : now.withHeartsPerKill(now.heartsPerKill() * killMultiplier);
        return submit(() -> store.kill(victim, killer, applied));
    }

    /** Fails with {@link BountyException} (wrapped) when the rules don't allow it. */
    public CompletableFuture<BountyPlacement> placeBounty(UUID placer, UUID target, int hearts) {
        return submit(() -> store.placeBounty(placer, target, hearts, rules()));
    }

    public CompletableFuture<List<HeartChange>> clearBounties(UUID target, String actor) {
        return submit(() -> store.clearBounties(target, actor, rules()));
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

    public CompletableFuture<Void> recordLogin(UUID player, String addressHash) {
        return submit(() -> {
            store.recordLogin(player, addressHash);
            return null;
        });
    }

    public CompletableFuture<List<PlayerHearts>> linkedAccounts(UUID player) {
        return submit(() -> store.linkedAccounts(player));
    }

    public CompletableFuture<List<AltFlag>> altFlags(int limit) {
        return submit(() -> store.altFlags(limit));
    }

    public CompletableFuture<List<SeasonStanding>> archiveSeason(String name) {
        return submit(() -> store.archiveSeason(name));
    }

    public CompletableFuture<List<SeasonStanding>> seasonStandings(String season) {
        return submit(() -> store.seasonStandings(season));
    }

    public CompletableFuture<List<String>> seasons() {
        return submit(store::seasons);
    }

    public CompletableFuture<List<PlayerHearts>> topHearts(int limit) {
        return submit(() -> store.topHearts(limit));
    }

    public CompletableFuture<Duration> shieldLeft(UUID player) {
        return submit(() -> store.shieldLeft(player));
    }

    public CompletableFuture<Duration> spendShield(UUID player, Duration played) {
        return submit(() -> store.spendShield(player, played));
    }

    public CompletableFuture<Boolean> breakShield(UUID player) {
        return submit(() -> store.breakShield(player));
    }

    public CompletableFuture<Void> setShield(UUID player, Duration left) {
        return submit(() -> {
            store.setShield(player, left);
            return null;
        });
    }

    public CompletableFuture<Void> recordBodyDeath(UUID player) {
        return submit(() -> {
            store.recordBodyDeath(player);
            return null;
        });
    }

    public CompletableFuture<Boolean> takeBodyDeath(UUID player) {
        return submit(() -> store.takeBodyDeath(player));
    }

    public CompletableFuture<Optional<String>> state(String key) {
        return submit(() -> store.state(key));
    }

    public CompletableFuture<Void> putState(String key, String value) {
        return submit(() -> {
            store.putState(key, value);
            return null;
        });
    }

    public CompletableFuture<Void> clearState(String key) {
        return submit(() -> {
            store.clearState(key);
            return null;
        });
    }

    public CompletableFuture<Optional<PlayerHearts>> lastStanding() {
        return submit(store::lastStanding);
    }

    public CompletableFuture<PurgatoryProgress> progress(UUID player) {
        return submit(() -> store.progress(player));
    }

    public CompletableFuture<TrialReward> awardFragment(UUID player, int needed) {
        return submit(() -> store.awardFragment(player, needed, rules()));
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
                T result = call.call();
                List<HeartChange> followOn = store.drainSideEffects();
                if (!followOn.isEmpty()) sideEffects.accept(followOn);
                return result;
            } catch (SQLException e) {
                throw new CompletionException(e);
            } finally {
                // If the call failed, its transaction rolled back: forget anything it queued.
                store.drainSideEffects();
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
