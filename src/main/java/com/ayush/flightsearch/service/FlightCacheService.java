package com.ayush.flightsearch.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single owner of the flight-search key formats, and of the two read tiers in front of
 * the database: L1 (this JVM's heap, backed by Caffeine) and L2 (Redis, shared across
 * instances). Both the read path (FlightService) and the write path (FlightPriceService)
 * go through here, so the key written can never drift from the key invalidated.
 *
 * <p>L1 is a Caffeine {@link Cache}: size-bounded ({@link #MAX_LOCAL_CACHE_SIZE}, evicted
 * under a W-TinyLFU admission policy) and time-bounded ({@link #LOCAL_TTL} via
 * expireAfterWrite). A {@link Scheduler} is attached so expired entries are proactively
 * purged on a timer rather than only on the next access to that exact key - the same
 * property the old manual sweep gave us. This replaces the hand-rolled
 * ConcurrentHashMap + manual LFU + scheduled-sweep implementation kept, commented, at the
 * bottom of this file for reference.
 *
 * <p>L1 is local to one instance. Evicting here clears it on the instance handling this
 * request only - a price update on instance A leaves stale prices in instance B's L1
 * until that entry's TTL expires. L2 (Redis) has no such gap, since every instance reads
 * and evicts the same key. {@link #LOCAL_TTL} is kept short specifically to bound how
 * long that instance-local staleness can last.
 *
 * <p>Reads into L2 are coalesced per instance: a burst of concurrent L1 misses on the
 * same key shares one Redis round trip rather than each firing its own GET. See get()
 * and {@link #inFlightRedisLoads}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlightCacheService {

    private static final String KEY_PREFIX = "flight-search:";
    private static final String LOCK_KEY_PREFIX = "flight-search:lock:";

    /** How long an L1 entry is trusted before falling back to L2 (Redis). */
    private static final Duration LOCAL_TTL = Duration.ofSeconds(5);

    /**
     * Upper bound on distinct L1 keys. Caffeine evicts under this bound using its own
     * W-TinyLFU admission/eviction policy - approximate LFU, not the exact linear-scan
     * LFU the old manual implementation did, but with the same purpose: protect against
     * an unbounded key space while favouring frequently-read keys.
     */
    private static final int MAX_LOCAL_CACHE_SIZE = 100;

    private final StringRedisTemplate redisTemplate;

    private final Cache<String, String> localCache = Caffeine.newBuilder()
            .expireAfterWrite(LOCAL_TTL)
            .maximumSize(MAX_LOCAL_CACHE_SIZE)
            .scheduler(Scheduler.systemScheduler())
            .build();

    /**
     * Request coalescing for the L2 read: keyed by cache key, holds the in-progress
     * Redis load for that key, if any. Lets a burst of concurrent L1 misses on the same
     * key - e.g. right after that key's L1 entry expires under real traffic - share one
     * Redis round trip instead of each firing its own GET. See get().
     */
    private final ConcurrentHashMap<String, CompletableFuture<Optional<String>>> inFlightRedisLoads =
            new ConcurrentHashMap<>();

    /**
     * Observability counters for the L1/L2 read path - see get(). Package-private,
     * same rationale as localCacheSize(): visibility into this class's own documented
     * behaviour for tests, not something the rest of the app should depend on.
     */
    private final AtomicLong l1MissCounter = new AtomicLong();
    private final AtomicLong l2LoadCounter = new AtomicLong();

    /**
     * Airport codes are upper-cased because the repository lookup is case-insensitive.
     * Without normalising, "del" and "DEL" would cache under two different keys and
     * invalidating one would leave the other serving stale prices forever.
     */
    public String buildCacheKey(String source, String destination) {
        return KEY_PREFIX + normalize(source) + ":" + normalize(destination);
    }

    /** Same normalisation, so the lock guards exactly the key it is protecting. */
    public String buildLockCacheKey(String source, String destination) {
        return LOCK_KEY_PREFIX + normalize(source) + ":" + normalize(destination);
    }

    /**
     * L1 first, then L2. Empty means both missed and the caller must load from the DB.
     *
     * <p>The L2 step is coalesced: on an L1 miss, a caller first tries to become the
     * one that actually queries Redis for this key, by claiming inFlightRedisLoads via
     * putIfAbsent. Whoever wins that race does the real Redis GET and populates L1;
     * everyone else who missed L1 for the same key while that GET was in flight just
     * waits on the winner's future and shares its result. The entry is removed once the
     * load completes, successfully or not, so the next miss for this key - once nobody
     * is already loading it - starts a fresh, uncoalesced attempt rather than replaying
     * a stale one.
     */
    public Optional<String> get(String cacheKey) {
        String local = localCache.getIfPresent(cacheKey);
        if (local != null) {
            log.debug("L1 hit for '{}'", cacheKey);
            return Optional.of(local);
        }
        l1MissCounter.incrementAndGet();

        CompletableFuture<Optional<String>> myLoad = new CompletableFuture<>();
        CompletableFuture<Optional<String>> inFlight = inFlightRedisLoads.putIfAbsent(cacheKey, myLoad);
        if (inFlight != null) {
            log.debug("L1 miss for '{}', coalescing onto an in-flight L2 load", cacheKey);
            return awaitInFlightLoad(inFlight);
        }

        l2LoadCounter.incrementAndGet();
        try {
            String fromRedis = redisTemplate.opsForValue().get(cacheKey);
            Optional<String> result = Optional.ofNullable(fromRedis);
            if (fromRedis != null) {
                log.debug("L1 miss, L2 hit for '{}'", cacheKey);
                localCache.put(cacheKey, fromRedis);
            }
            myLoad.complete(result);
            return result;
        } catch (RuntimeException e) {
            myLoad.completeExceptionally(e);
            throw e;
        } finally {
            inFlightRedisLoads.remove(cacheKey, myLoad);
        }
    }

    /**
     * Unwraps CompletableFuture's CompletionException back to the original
     * RuntimeException the load leader threw, so a coalesced waiter sees the same
     * exception type it would have gotten from Redis directly, not a wrapper type.
     */
    private static Optional<String> awaitInFlightLoad(CompletableFuture<Optional<String>> inFlight) {
        try {
            return inFlight.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    /** Writes through to both tiers - L2 carries the real (longer) TTL. */
    public void put(String cacheKey, String json, Duration redisTtl) {
        redisTemplate.opsForValue().set(cacheKey, json, redisTtl);
        localCache.put(cacheKey, json);
    }

    public void evict(String source, String destination) {
        String key = buildCacheKey(source, destination);
        localCache.invalidate(key);
        Boolean existed = redisTemplate.delete(key);
        log.info("Evicted '{}' from L1 and Redis (was present in Redis: {})", key, existed);
    }

    /**
     * Evicting while the transaction is still open is a classic staleness bug: a
     * concurrent search could miss the cache, read the old uncommitted-elsewhere rows
     * and re-populate the cache with them. Deferring the delete to afterCommit
     * guarantees the DB already holds the new price by the time the key disappears.
     * Falls back to an immediate evict when called outside a transaction.
     */
    public void evictAfterCommit(String source, String destination) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evict(source, destination);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evict(source, destination);
            }
        });
    }

    private static String normalize(String airportCode) {
        return airportCode == null ? "" : airportCode.trim().toUpperCase();
    }

    /**
     * Current L1 entry count (Caffeine's estimate - may lag actual size slightly since
     * maintenance is amortised, not synchronous on every call). Package-private:
     * observability for this class's own documented behaviour, not something the rest
     * of the app should depend on.
     */
    int localCacheSize() {
        return (int) localCache.estimatedSize();
    }

    /** The configured bound itself, so tests don't hardcode a second copy of it. */
    static int maxLocalCacheSize() {
        return MAX_LOCAL_CACHE_SIZE;
    }

    /**
     * Count of L1 misses observed so far by this instance - incremented once per get()
     * call that found nothing in L1, regardless of whether that call went on to lead a
     * real Redis load or coalesce onto someone else's. Monotonic for the instance's
     * lifetime; tests read it before and after a burst and diff the two.
     */
    long l1MissCount() {
        return l1MissCounter.get();
    }

    /**
     * Count of real Redis GETs this instance has actually issued - i.e. excludes
     * callers who coalesced onto someone else's in-flight load. Monotonic for the
     * instance's lifetime; tests read it before and after a burst and diff the two.
     */
    long l2LoadCount() {
        return l2LoadCounter.get();
    }

    // ========================================================================================
    // OLD L1 IMPLEMENTATION - manual ConcurrentHashMap + hand-rolled LFU + scheduled sweep.
    // Replaced by Caffeine above. Kept commented out, not deleted, for reference.
    // ========================================================================================
    //
    // /**
    //  * How often the background sweep looks for expired-but-unread L1 entries (see
    //  * evictExpiredLocalEntries below). Kept equal to LOCAL_TTL: an unread entry then
    //  * lingers at most one sweep past its own expiry, never indefinitely. @Scheduled
    //  * needs a compile-time constant, so this can't be derived from LOCAL_TTL directly -
    //  * keep the two in step by hand if either changes.
    //  */
    // private static final long SWEEP_INTERVAL_MS = 5_000;
    //
    // private final ConcurrentHashMap<String, LocalEntry> localCache = new ConcurrentHashMap<>();
    //
    // /**
    //  * L1 first, then L2. Empty means both missed and the caller must load from the DB.
    //  * A repeat L1 hit bumps that entry's frequency - the signal enforceCapacity() evicts
    //  * by. A fresh L2 backfill does not bump it again here: putLocal already seeds a new
    //  * entry at frequency 1, which already counts this delivery, so a second increment
    //  * would double-count the very read that created the entry.
    //  */
    // public Optional<String> get(String cacheKey) {
    //     LocalEntry local = localCache.get(cacheKey);
    //     if (local != null) {
    //         if (!local.isExpired()) {
    //             int frequency = local.frequency().incrementAndGet();
    //             log.debug("L1 hit for '{}' (frequency now {})", cacheKey, frequency);
    //             return Optional.of(local.json());
    //         }
    //         localCache.remove(cacheKey, local);
    //     }
    //
    //     String fromRedis = redisTemplate.opsForValue().get(cacheKey);
    //     if (fromRedis == null) {
    //         return Optional.empty();
    //     }
    //
    //     log.debug("L1 miss, L2 hit for '{}'", cacheKey);
    //     putLocal(cacheKey, fromRedis);
    //     return Optional.of(fromRedis);
    // }
    //
    // /** Writes through to both tiers - L2 carries the real (longer) TTL. */
    // public void put(String cacheKey, String json, Duration redisTtl) {
    //     redisTemplate.opsForValue().set(cacheKey, json, redisTtl);
    //     putLocal(cacheKey, json);
    // }
    //
    // public void evict(String source, String destination) {
    //     String key = buildCacheKey(source, destination);
    //     localCache.remove(key);
    //     Boolean existed = redisTemplate.delete(key);
    //     log.info("Evicted '{}' from L1 and Redis (was present in Redis: {})", key, existed);
    // }
    //
    // /**
    //  * get() only reclaims an entry when that exact key is read again, so a key written
    //  * once and never re-read would otherwise sit in the map forever, expired or not -
    //  * demonstrated by FlightCacheServiceLocalCacheLeakTest. This sweep is what makes
    //  * that not true in a running app: on a timer, independent of whether anyone ever
    //  * reads a given key again, it drops entries whose TTL has already lapsed.
    //  *
    //  * fixedDelay (not fixedRate): waits for one sweep to finish before counting down
    //  * to the next, so a slow sweep can never overlap itself.
    //  */
    // @Scheduled(initialDelay = SWEEP_INTERVAL_MS, fixedDelay = SWEEP_INTERVAL_MS)
    // void evictExpiredLocalEntries() {
    //     int sizeBefore = localCache.size();
    //     localCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    //
    //     int removed = sizeBefore - localCache.size();
    //     if (removed > 0) {
    //         log.debug("L1 sweep evicted {} expired entrie(s), {} remain", removed, localCache.size());
    //     }
    // }
    //
    // /**
    //  * Stores the entry at frequency 1, not 0: this call only ever happens because a
    //  * caller needs the value right now (a DB load about to answer the current request,
    //  * or a get() that just missed L1 and fell through to L2) - so the entry's very first
    //  * delivery is already one access, not zero. A 0 baseline would make every brand-new
    //  * key the global minimum the instant the map is full and every resident key has been
    //  * read at least once, so nothing new could ever be admitted again; seeding at 1
    //  * instead gives a new key the same standing as any other once-read entry. Frequency
    //  * still only ever rises from here through get()'s repeat-hit branch.
    //  *
    //  * <p>Only a key that did not already exist can grow the map past the bound, so
    //  * enforceCapacity() runs only then - refreshing an existing key's value/expiry does
    //  * not change how many distinct keys are resident.
    //  */
    // private LocalEntry putLocal(String cacheKey, String json) {
    //     LocalEntry entry = new LocalEntry(json, System.nanoTime() + LOCAL_TTL.toNanos(), new AtomicInteger(1));
    //     LocalEntry previous = localCache.put(cacheKey, entry);
    //
    //     if (previous == null) {
    //         enforceCapacity();
    //     }
    //     return entry;
    // }
    //
    // /**
    //  * Keeps L1 at or under MAX_LOCAL_CACHE_SIZE by repeatedly dropping the current
    //  * least-frequently-used entry. A plain linear scan for the minimum - simple, and
    //  * fast enough at this bound (MAX_LOCAL_CACHE_SIZE entries, no I/O). Ties (e.g. two
    //  * entries neither one has ever been read) are broken by map iteration order, which
    //  * is arbitrary. Not perfectly race-free under heavy concurrent inserts of brand-new
    //  * keys - two threads can pick the same victim, or the map can transiently sit a
    //  * few entries over the bound - but the while loop always converges back to the
    //  * bound, which is the property that actually matters here.
    //  */
    // private void enforceCapacity() {
    //     while (localCache.size() > MAX_LOCAL_CACHE_SIZE) {
    //         String victim = null;
    //         int lowestFrequency = Integer.MAX_VALUE;
    //
    //         for (Map.Entry<String, LocalEntry> entry : localCache.entrySet()) {
    //             int frequency = entry.getValue().frequency().get();
    //             if (frequency < lowestFrequency) {
    //                 lowestFrequency = frequency;
    //                 victim = entry.getKey();
    //             }
    //         }
    //
    //         if (victim != null && localCache.remove(victim) != null) {
    //             log.debug("L1 at capacity ({}), evicted least-frequently-used key '{}' (frequency {})",
    //                     MAX_LOCAL_CACHE_SIZE, victim, lowestFrequency);
    //         }
    //         // A null victim (map emptied concurrently) or a no-op remove (someone else
    //         // already evicted that exact key) just falls through to the while condition
    //         // re-checking the current size, rather than a separate break/retry branch.
    //     }
    // }
    //
    // /**
    //  * Current access count for a resident key, or -1 if it is not in L1 right now.
    //  * Package-private, same rationale as localCacheSize(): test observability only.
    //  */
    // int localFrequencyOf(String cacheKey) {
    //     LocalEntry entry = localCache.get(cacheKey);
    //     return entry == null ? -1 : entry.frequency().get();
    // }
    //
    // /**
    //  * frequency is mutable (an AtomicInteger, not an int) so a hit can bump it without
    //  * replacing the whole record - replacing would race with a concurrent isExpired()
    //  * check or capacity scan reading the very entry being swapped out. json and
    //  * expiresAtNanos stay plain final fields; only the access count needs to change
    //  * after construction.
    //  */
    // private record LocalEntry(String json, long expiresAtNanos, AtomicInteger frequency) {
    //     boolean isExpired() {
    //         return System.nanoTime() >= expiresAtNanos;
    //     }
    // }
}
