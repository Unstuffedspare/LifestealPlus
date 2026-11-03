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
    /** Multiplies heartsPerKill while events run, by event; the biggest applies. */
    private final java.util.Map<String, Integer> killMultipliers = new java.util.concurrent.ConcurrentHashMap<>();
    /** Extra hearts on the cap while events run, by event; they add up. */
    private final java.util.Map<String, Integer> capBonuses = new java.util.concurrent.ConcurrentHashMap<>();

    public HeartService(HeartStore store, HeartRules rules) {
        this.store = store;
        this.rules = rules;
    }

    /** The rules in force right now: the configured ones, with the scheduled cap if there is one, plus any event's boost. */
    public HeartRules rules() {
        Integer cap = capOverride;
        int bonus = capBonus();
        if (cap == null && bonus == 0) return rules;
        return rules.withMaxHearts((cap == null ? rules.maxHearts() : cap) + bonus);
    }

    /** Raises the cap by {@code extra} while an event runs; 0 removes that event's boost. */
    public void setCapBonus(String event, int extra) {
        if (extra <= 0) capBonuses.remove(event);
        else capBonuses.put(event, extra);
        store.setCapBonus(capBonus());
    }

    public int capBonus() {
        return capBonuses.values().stream().mapToInt(Integer::intValue).sum();
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

    public CompletableFuture<TradeOutcome> giveHearts(UUID from, UUID to, int hearts, int taxPercent) {
        return submit(() -> store.giveHearts(from, to, hearts, taxPercent, rules()));
    }

    /** How many times a player has been eliminated this season. */
    public CompletableFuture<Integer> eliminations(UUID player) {
        return submit(() -> store.eliminations(player));
    }

    public CompletableFuture<Integer> revivalCost(UUID target, int base) {
        return submit(() -> store.revivalCost(target, base));
    }

    public CompletableFuture<List<PlayerHearts>> eliminatedPlayers(int limit) {
        return submit(() -> store.eliminatedPlayers(limit));
    }

    public CompletableFuture<HeartChange> reviveWithItem(UUID user, UUID target) {
        return submit(() -> store.reviveWithItem(user, target, rules()));
    }

    public void setRevivalRules(java.time.Duration cooldown, int extraPerElimination) {
        store.setRevivalRules(cooldown, extraPerElimination);
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
    public CompletableFuture<Void> setFeatures(boolean shields, Duration shieldExpiry, boolean pacts, boolean altFlags) {
        return submit(() -> {
            store.setFeatures(shields, shieldExpiry, pacts, altFlags);
            return null;
        });
    }

    public CompletableFuture<Void> setStreaks(StreakRules streaks) {
        return submit(() -> {
            store.setStreaks(streaks);
            return null;
        });
    }

    public CompletableFuture<Void> endStreak(UUID player) {
        return submit(() -> {
            store.endStreak(player);
            return null;
        });
    }

    public CompletableFuture<Optional<PlayerStreak>> streak(UUID player) {
        return submit(() -> store.streak(player));
    }

    public CompletableFuture<Void> addNews(String kind, String player, String other, int amount) {
        return submit(() -> {
            store.addNews(kind, player, other, amount);
            return null;
        });
    }

    public CompletableFuture<List<java.util.Map.Entry<Long, NewsItem>>> newsAfter(long afterId, int limit) {
        return submit(() -> store.newsAfter(afterId, limit));
    }

    public CompletableFuture<Long> lastNewsId() {
        return submit(store::lastNewsId);
    }

    public CompletableFuture<List<NewsItem>> latestNews(int limit) {
        return submit(() -> store.latestNews(limit));
    }

    public CompletableFuture<Integer> pruneLogins(java.time.Duration keep) {
        return submit(() -> store.pruneLogins(keep));
    }

    public CompletableFuture<EconomyReport> economy(java.time.Duration window) {
        return submit(() -> store.economy(window));
    }

    public CompletableFuture<Optional<PlayerStats>> stats(String name, String season) {
        return submit(() -> store.stats(name, season));
    }

    public CompletableFuture<Integer> addPlaytime(UUID player, java.time.Duration played, java.time.Duration every) {
        return submit(() -> store.addPlaytime(player, played, every));
    }

    public boolean isSqlite() {
        return store.isSqlite();
    }

    public CompletableFuture<Void> backUpTo(java.nio.file.Path file) {
        return submit(() -> {
            store.backUpTo(file);
            return null;
        });
    }

    public CompletableFuture<Optional<PlayerHearts>> heartsInBackup(java.nio.file.Path file, String name) {
        return submit(() -> store.heartsInBackup(file, name));
    }

    public CompletableFuture<Integer> exportLedger(java.nio.file.Path file, String playerName) {
        return submit(() -> store.exportLedger(file, playerName));
    }

    public CompletableFuture<Integer> exportAudit(java.nio.file.Path file) {
        return submit(() -> store.exportAudit(file));
    }

    public CompletableFuture<HeartChange> applyStartingHearts(UUID player, int starting) {
        return submit(() -> store.applyStartingHearts(player, starting, rules()));
    }

    public CompletableFuture<Void> addAudit(String actor, String action, String detail) {
        return submit(() -> {
            store.addAudit(actor, action, detail);
            return null;
        });
    }

    public CompletableFuture<List<AuditEntry>> audit(int limit, int offset) {
        return submit(() -> store.audit(limit, offset));
    }

    public CompletableFuture<Integer> auditSize() {
        return submit(store::auditSize);
    }

    public CompletableFuture<Void> setPersonalCap(UUID player, Integer cap) {
        return submit(() -> {
            store.setPersonalCap(player, cap);
            return null;
        });
    }

    public CompletableFuture<Void> markLeft(UUID player) {
        return submit(() -> {
            store.markLeft(player);
            return null;
        });
    }

    public CompletableFuture<Optional<Recap>> recap(UUID player, Duration minAway, int limit) {
        return submit(() -> store.recap(player, minAway, limit));
    }

    public CompletableFuture<Integer> pruneNews(Duration keep) {
        return submit(() -> store.pruneNews(keep));
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

    /** Multiplies the hearts each kill takes while {@code event} runs; 1 removes it. */
    public void setKillMultiplier(String event, int multiplier) {
        if (multiplier <= 1) killMultipliers.remove(event);
        else killMultipliers.put(event, multiplier);
    }

    /** The biggest multiplier any running event gives (they don't stack). */
    public int killMultiplier() {
        return killMultipliers.values().stream().mapToInt(Integer::intValue).max().orElse(1);
    }

    public CompletableFuture<KillOutcome> kill(UUID victim, UUID killer) {
        HeartRules now = rules();
        int killMultiplier = killMultiplier();
        HeartRules applied = killMultiplier == 1 ? now : now.withHeartsPerKill(now.heartsPerKill() * killMultiplier);
        return submit(() -> store.kill(victim, killer, applied));
    }

    /** Fails with {@link BountyException} (wrapped) when the rules don't allow it. */
    public CompletableFuture<BountyPlacement> placeBounty(UUID placer, UUID target, int hearts) {
        return submit(() -> store.placeBounty(placer, target, hearts, rules()));
    }

    public CompletableFuture<BountyUndo> undoBounty(UUID placer, UUID target, java.time.Duration window) {
        return submit(() -> store.undoBounty(placer, target, window, rules()));
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

    public CompletableFuture<List<SeasonStanding>> archiveSeason(String name, java.util.Map<String, String> nextState) {
        return submit(() -> store.archiveSeason(name, nextState));
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

    public CompletableFuture<Void> clearStates(String prefix) {
        return submit(() -> {
            store.clearStates(prefix);
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
        if (!db.awaitTermination(10, TimeUnit.SECONDS)) {
            // Something is stuck: interrupt it, and give it a moment to let go of the connection.
            db.shutdownNow();
            if (!db.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The database worker didn't stop; leaving the database open rather than "
                        + "closing it under a running write. SQLite rolls back whatever didn't finish.");
            }
        }
        store.close();
    }
}
