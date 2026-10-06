package com.ayush.flightsearch;

import com.ayush.flightsearch.repository.FlightRepository;
import com.ayush.flightsearch.service.FlightCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Turns DEL -> BOM into a hot key by firing 1000 concurrent searches at it, and measures
 * what that costs at each layer.
 *
 * <p>Needs the real Postgres and Redis running - the same ones the app uses - because the
 * whole point is to observe contention on an actual Redis key and an actual DB.
 *
 * <p>Note: the flight table is truncated and reseeded from data.sql on context startup,
 * and these tests delete their own flight-search:* keys from Redis.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HotKeyReproductionTest {

    private static final int REQUEST_COUNT = 1000;
    private static final String SOURCE = "DEL";
    private static final String DESTINATION = "BOM";

    @LocalServerPort
    private int port;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private FlightCacheService flightCacheService;

    /** Spied, not mocked: real queries still run, we just count how many reached the DB. */
    @MockitoSpyBean
    private FlightRepository flightRepository;

    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        clearHotKey();
        Mockito.clearInvocations(flightRepository);
    }

    @Test
    @DisplayName("cold hot key: 1000 concurrent requests reach the database exactly once")
    void coldHotKey_thousandConcurrentRequests_hitDatabaseOnce() {
        long redisGetsBefore = redisGetCalls();

        LoadResult result = fireConcurrently(REQUEST_COUNT);

        result.print("SCENARIO 1 - cold hot key, cache and lock both empty");
        System.out.printf("  redis GET calls during run : %d%n", redisGetCalls() - redisGetsBefore);

        // The lock is what makes this 1 instead of 1000: one request loads, the rest wait.
        verify(flightRepository, times(1))
                .findBySourceIgnoreCaseAndDestinationIgnoreCaseOrderByPriceAsc(SOURCE, DESTINATION);

        assertThat(result.statusCounts()).containsOnlyKeys(200);
        assertThat(result.distinctBodies())
                .as("every caller must see the same payload")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("warm hot key: 1000 concurrent requests served from L1, Redis untouched")
    void warmHotKey_thousandConcurrentRequests_neverHitRedisOrDatabase() {
        get();                                            // warm L1 and Redis
        Mockito.clearInvocations(flightRepository);
        long redisGetsBefore = redisGetCalls();

        LoadResult result = fireConcurrently(REQUEST_COUNT);

        result.print("SCENARIO 2 - warm hot key, served entirely from this instance's L1");
        long redisGets = redisGetCalls() - redisGetsBefore;
        System.out.printf("  redis GET calls during run : %d  <- L1 absorbed the burst%n", redisGets);

        verify(flightRepository, never())
                .findBySourceIgnoreCaseAndDestinationIgnoreCaseOrderByPriceAsc(anyString(), anyString());

        assertThat(result.statusCounts()).containsOnlyKeys(200);
        // The single warmup call above already populated L1, and the whole burst finishes
        // in well under LOCAL_TTL (5s) - every one of the 1000 requests should be an L1
        // hit on this instance, so Redis should not see a single GET for this key.
        assertThat(redisGets).isZero();
    }

    @Test
    @DisplayName("expiring hot key: DB loads track TTL expiries, not request count")
    void expiringHotKey_reloadsOncePerExpiry() throws Exception {
        int rounds = 3;
        int perRound = REQUEST_COUNT / rounds;

        for (int round = 1; round <= rounds; round++) {
            // Force the expiry that CACHE_TTL/LOCAL_TTL would eventually produce, without
            // waiting for it. Must clear L1 as well as Redis - L1 sits in front of Redis,
            // so a Redis-only delete would leave this instance serving round 2 and 3
            // straight from L1 and the DB-load assertion below would fail.
            flightCacheService.evict(SOURCE, DESTINATION);

            LoadResult result = fireConcurrently(perRound);
            result.print("SCENARIO 3 - round " + round + " of " + rounds + ", cache expired at round start");
            assertThat(result.statusCounts()).containsOnlyKeys(200);

            TimeUnit.MILLISECONDS.sleep(200);
        }

        // One reload per expiry - the thing that must NOT scale with traffic.
        verify(flightRepository, times(rounds))
                .findBySourceIgnoreCaseAndDestinationIgnoreCaseOrderByPriceAsc(SOURCE, DESTINATION);
        System.out.printf("%n  %d requests across %d expiries -> %d DB loads%n%n",
                perRound * rounds, rounds, rounds);
    }

    @Test
    @DisplayName("L1 expires after 5s but Redis stays warm: 100 concurrent requests still avoid the database")
    void l1ExpiredButRedisWarm_hundredConcurrentRequests_neverHitDatabase() throws Exception {
        int requestCount = 100;

        get();                                             // one seed request warms L1 and Redis
        Mockito.clearInvocations(flightRepository);

        // LOCAL_TTL (L1) is 5s, CACHE_TTL (Redis) is 15s - sleeping 6s clears L1 while
        // Redis is still well within its own TTL, so this burst starts with an empty L1
        // and a warm L2.
        TimeUnit.SECONDS.sleep(6);

        long redisGetsBefore = redisGetCalls();

        LoadResult result = fireConcurrently(requestCount);

        result.print("SCENARIO 4 - L1 expired after 5s, Redis still warm, " + requestCount + " concurrent requests");
        long redisGets = redisGetCalls() - redisGetsBefore;
        System.out.printf("  redis GET calls during run : %d  <- L1 started empty, so at least the leading "
                + "requests fell through to L2%n", redisGets);

        // Whichever tier actually answers any one request - L1 or L2 - is a race: the
        // first responder repopulates L1 for whoever runs after it, so not every one of
        // the 100 is guaranteed to reach Redis itself. What IS guaranteed regardless of
        // that race is that the database is never reached, since the data was already
        // sitting in Redis the whole time.
        verify(flightRepository, never())
                .findBySourceIgnoreCaseAndDestinationIgnoreCaseOrderByPriceAsc(SOURCE, DESTINATION);

        assertThat(result.statusCounts()).containsOnlyKeys(200);
        assertThat(result.distinctBodies())
                .as("every caller must see the same payload for this sector")
                .isEqualTo(1);
        assertThat(redisGets)
                .as("L1 started empty for this burst, so L2 must be consulted at least once")
                .isGreaterThan(0);
    }

    /** Releases all N requests at the same instant, so they genuinely collide. */
    private LoadResult fireConcurrently(int count) {
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Call>> futures = new ArrayList<>(count);

        long startedAt = System.nanoTime();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return get();
                }));
            }
            startGate.countDown();
        }
        long wallClockMs = (System.nanoTime() - startedAt) / 1_000_000;

        List<Call> calls = new ArrayList<>(count);
        for (Future<Call> future : futures) {
            try {
                calls.add(future.get());
            } catch (Exception e) {
                calls.add(new Call(-1, "", 0));
            }
        }
        return new LoadResult(calls, wallClockMs);
    }

    private Call get() {
        long startedAt = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:%d/flights/search?from=%s&to=%s"
                            .formatted(port, SOURCE, DESTINATION)))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new Call(response.statusCode(), response.body(), elapsedMs(startedAt));
        } catch (Exception e) {
            return new Call(-1, e.getClass().getSimpleName(), elapsedMs(startedAt));
        }
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private void clearHotKey() {
        // evict() clears both cache tiers (L1 heap map + Redis) for the search key.
        // FlightCacheService is a Spring singleton, so its L1 map outlives any one test
        // method - without this, a previous test's warm L1 entry would leak into the
        // next test's "cold" assumptions.
        flightCacheService.evict(SOURCE, DESTINATION);
        redisTemplate.delete(flightCacheService.buildLockCacheKey(SOURCE, DESTINATION));
    }

    /** Redis' own counter for GET, so the hot-key read volume is measured, not estimated. */
    private long redisGetCalls() {
        Properties stats = redisTemplate.execute(
                (RedisCallback<Properties>) connection -> connection.serverCommands().info("commandstats"));
        if (stats == null) {
            return -1;
        }
        String line = stats.getProperty("cmdstat_get");   // e.g. calls=42,usec=123,...
        if (line == null) {
            return 0;
        }
        for (String part : line.split(",")) {
            if (part.startsWith("calls=")) {
                return Long.parseLong(part.substring("calls=".length()));
            }
        }
        return 0;
    }

    private record Call(int status, String body, long durationMs) {
    }

    private record LoadResult(List<Call> calls, long wallClockMs) {

        Map<Integer, Integer> statusCounts() {
            Map<Integer, Integer> counts = new HashMap<>();
            calls.forEach(c -> counts.merge(c.status(), 1, Integer::sum));
            return counts;
        }

        int distinctBodies() {
            return (int) calls.stream().map(Call::body).distinct().count();
        }

        void print(String title) {
            List<Long> sorted = calls.stream().map(Call::durationMs).sorted().toList();
            System.out.printf("%n=== %s ===%n", title);
            System.out.printf("  requests                   : %d%n", calls.size());
            System.out.printf("  statuses                   : %s%n", statusCounts());
            System.out.printf("  wall clock                 : %d ms%n", wallClockMs);
            System.out.printf("  latency p50 / p95 / max    : %d / %d / %d ms%n",
                    percentile(sorted, 50), percentile(sorted, 95), sorted.getLast());
        }

        private static long percentile(List<Long> sorted, int p) {
            return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * p / 100.0) - 1));
        }
    }
}
