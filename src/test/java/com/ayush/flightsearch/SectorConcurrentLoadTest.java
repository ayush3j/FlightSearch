package com.ayush.flightsearch;

import com.ayush.flightsearch.repository.FlightRepository;
import com.ayush.flightsearch.service.FlightCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;

/**
 * Fires 100 concurrent searches at a single sector (DEL -> BLR - kept distinct from
 * HotKeyReproductionTest's DEL -> BOM so the two suites never share cache state) and
 * checks that the lock in FlightService collapses them into one database read, with
 * every caller still getting back the same 200 response.
 *
 * <p>Needs the real Postgres and Redis the app uses, same as HotKeyReproductionTest -
 * the point is to observe real lock contention, not a mocked stand-in for it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SectorConcurrentLoadTest {

    private static final int REQUEST_COUNT = 100;
    private static final String SOURCE = "DEL";
    private static final String DESTINATION = "BLR";

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

        // FlightCacheService is a Spring singleton, so its L1 map and Redis both outlive
        // any one test method - clear both so this sector starts genuinely cold.
        flightCacheService.evict(SOURCE, DESTINATION);
        redisTemplate.delete(flightCacheService.buildLockCacheKey(SOURCE, DESTINATION));
        Mockito.clearInvocations(flightRepository);
    }

    @Test
    @Timeout(30)
    @DisplayName("100 concurrent requests for one sector hit the database exactly once")
    void hundredConcurrentRequests_forOneSector_hitDatabaseOnce() {
        LoadResult result = fireConcurrently(REQUEST_COUNT);

        result.print("100 concurrent requests, one sector, cold cache and lock");

        // The lock is what makes this 1 instead of 100: one request loads, the rest wait.
        Mockito.verify(flightRepository, times(1))
                .findBySourceIgnoreCaseAndDestinationIgnoreCaseOrderByPriceAsc(SOURCE, DESTINATION);

        assertThat(result.statusCounts())
                .as("every one of the %d concurrent requests must succeed", REQUEST_COUNT)
                .containsOnlyKeys(200);
        assertThat(result.distinctBodies())
                .as("every caller must see the same payload for this sector")
                .isEqualTo(1);
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
