package com.ayush.flightsearch.service;

import com.ayush.flightsearch.dto.FlightSearchResponse;
import com.ayush.flightsearch.entity.Flight;
import com.ayush.flightsearch.mapper.FlightMapper;
import com.ayush.flightsearch.repository.FlightRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Cache-aside search over two cache tiers (FlightCacheService: L1 heap map, L2 Redis),
 * with a Redis lock in front of the DB read so a full miss on a hot sector sends one
 * query to the database instead of one per concurrent request.
 *
 * <p>A FlightBloomFilter sits in front of all of that: a sector it has never seen
 * (populated at startup by FlightBloomFilterInitializer from every route actually in
 * the database) cannot be real, so those requests are answered immediately with no
 * flights, without ever touching L1, L2, the lock, or the database - the guard
 * against cache penetration from searches for sectors that don't exist.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlightService {

    /** How long a cached search result stays valid. */
    private static final Duration CACHE_TTL = Duration.ofSeconds(15);

    /** Lock TTL between renewals - how long before a silent holder is presumed dead. */
    private static final Duration LOCK_TTL = Duration.ofSeconds(5);

    /** Ceiling on lease renewal, so a stuck holder cannot keep the lock alive forever. */
    private static final Duration MAX_LEASE = Duration.ofSeconds(30);

    /**
     * How long a waiting request polls for the lock holder to publish its result.
     * Derived from MAX_LEASE rather than picked independently: the lease lets the holder
     * work for up to MAX_LEASE, so a waiter that gives up sooner would fail while the
     * holder is still legitimately working - the lease would help the holder and break
     * everyone behind it. Reaching this limit therefore means the holder blew its own cap.
     */
    private static final long WAIT_INTERVAL_MS = 500;
    private static final int MAX_WAIT_ATTEMPTS = (int) (MAX_LEASE.toMillis() / WAIT_INTERVAL_MS) + 1;

    private final FlightRepository flightRepository;
    private final FlightMapper flightMapper;
    private final FlightCacheService flightCacheService;
    private final RedisLockService redisLockService;
    private final FlightBloomFilter flightBloomFilter;
    private final ObjectMapper objectMapper;

    public FlightSearchResponse searchResponse(String from, String to) {
        // Cache-penetration guard: a sector the bloom filter has never seen cannot be
        // a real route (no false negatives), so there is nothing L1, L2, or the
        // database could ever return for it - skip all three entirely rather than
        // let a flood of requests for made-up sectors hammer the database every time.
        if (!flightBloomFilter.mightContain(flightBloomFilter.buildKey(from, to))) {
            log.info("Sector {} -> {} not in the bloom filter - skipping cache and database", from, to);
            return new FlightSearchResponse(from, to, List.of());
        }

        String cacheKey = flightCacheService.buildCacheKey(from, to);

        Optional<String> cached = flightCacheService.get(cacheKey);
        if (cached.isPresent()) {
            return objectMapper.readValue(cached.get(), FlightSearchResponse.class);
        }

        String lockKey = flightCacheService.buildLockCacheKey(from, to);
        Optional<RedisLockService.LockLease> lease = redisLockService.tryAcquire(lockKey, LOCK_TTL, MAX_LEASE);
        if (lease.isEmpty()) {
            return awaitCachePopulation(from, to, cacheKey);
        }

        // try-with-resources: the lease stops renewing and releases the lock on the way
        // out, including when the DB read throws - otherwise every waiting request would
        // sit out the lock's full TTL for nothing.
        try (RedisLockService.LockLease held = lease.get()) {
//            Thread.sleep(10000);

            // Double-check before querying. Between our cache miss and our lock acquisition,
            // the previous holder may have finished and published - in which case loading
            // again is pure waste. Without this, every request that missed while a holder
            // was working, but took the lock after it released, fires a redundant query.
            Optional<String> publishedWhileWeQueued = flightCacheService.get(cacheKey);
            if (publishedWhileWeQueued.isPresent()) {
                return objectMapper.readValue(publishedWhileWeQueued.get(), FlightSearchResponse.class);
            }

            log.info("Cache miss for {} -> {}, lock acquired - loading from DB", from, to);
            FlightSearchResponse response = loadFromDb(from, to);

            if (held.isExclusivityLost()) {
                log.warn("Lock protection for {} -> {} ended before the load finished - another request "
                        + "may have run the same query", from, to);
            }

            flightCacheService.put(cacheKey, objectMapper.writeValueAsString(response), CACHE_TTL);
            return response;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private FlightSearchResponse loadFromDb(String from, String to) {
        List<Flight> flights = flightRepository
                .findBySourceIgnoreCaseAndDestinationIgnoreCaseOrderByPriceAsc(from, to);

        if (flights.isEmpty()) {
            log.warn("No flights in DB for route {} -> {}", from, to);
        }
        return new FlightSearchResponse(from, to, flightMapper.toResponseList(flights));
    }

    /**
     * Someone else holds the lock and is already querying this sector, so poll the
     * cache for their result rather than firing a duplicate query at the DB.
     */
    private FlightSearchResponse awaitCachePopulation(String from, String to, String cacheKey) {
        log.info("Cache miss for {} -> {}, lock held elsewhere - waiting for that result", from, to);

        for (int attempt = 1; attempt <= MAX_WAIT_ATTEMPTS; attempt++) {
            sleep(WAIT_INTERVAL_MS);

            Optional<String> cached = flightCacheService.get(cacheKey);
            if (cached.isPresent()) {
                log.info("Cache populated by lock holder after {} attempt(s) for {} -> {}", attempt, from, to);
                return objectMapper.readValue(cached.get(), FlightSearchResponse.class);
            }
        }
        throw new IllegalStateException("Cache was not populated for %s -> %s after %d attempts"
                .formatted(from, to, MAX_WAIT_ATTEMPTS));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the cache to be populated", e);
        }
    }
}
