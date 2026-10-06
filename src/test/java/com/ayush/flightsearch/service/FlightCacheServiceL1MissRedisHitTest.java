package com.ayush.flightsearch.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises FlightCacheService.get() for the "L1 empty, L2 (Redis) already holds the
 * result" case: the state a freshly-started instance, or one whose L1 entry has simply
 * expired, is in when it sits in front of an already-warm shared Redis.
 *
 * <p>A single shared FlightCacheService can't prove "every concurrent caller misses
 * L1" - the first successful read repopulates that instance's L1, so most of a
 * concurrent burst racing in afterwards would actually hit L1, not L2 (confirmed while
 * building this test: firing 100 concurrent HTTP requests at one shared instance after
 * letting its L1 expire produced only a handful of Redis reads, not 100 - the rest won
 * the race against their own predecessors' L1 writes). To make every caller miss L1
 * with certainty rather than by luck of scheduling, each concurrent caller here gets
 * its OWN FlightCacheService - its own empty L1 - all pointed at the same (mocked)
 * Redis already holding the value. That is the same shape as N separate app instances
 * sharing one Redis, and an L1 hit is impossible by construction: every single call
 * must fall through to L2.
 *
 * <p>No Spring context; Redis is mocked so the read is deterministic and doesn't depend
 * on real network I/O or timing.
 */
class FlightCacheServiceL1MissRedisHitTest {

    private static final int CALLER_COUNT = 100;
    private static final String CACHE_KEY = "flight-search:DEL:BLR";
    private static final String CACHED_JSON = "{\"source\":\"DEL\",\"destination\":\"BLR\",\"flights\":[]}";

    @SuppressWarnings("unchecked")
    @Test
    @Timeout(30)
    void hundredConcurrentCallers_eachWithEmptyL1_allMissL1AndHitRedis()
            throws InterruptedException, ExecutionException {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(CACHE_KEY)).thenReturn(CACHED_JSON);

        List<FlightCacheService> callers = new ArrayList<>(CALLER_COUNT);
        for (int i = 0; i < CALLER_COUNT; i++) {
            callers.add(new FlightCacheService(redisTemplate));
        }

        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Optional<String>>> futures = new ArrayList<>(CALLER_COUNT);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (FlightCacheService caller : callers) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return caller.get(CACHE_KEY);
                }));
            }
            startGate.countDown();
        }

        List<Optional<String>> results = new ArrayList<>(CALLER_COUNT);
        for (Future<Optional<String>> future : futures) {
            results.add(future.get());
        }

        long hitCount = results.stream().filter(r -> r.isPresent() && CACHED_JSON.equals(r.get())).count();
        System.out.printf("%n=== %d separate instances, empty L1, shared warm Redis ===%n", CALLER_COUNT);
        System.out.printf("  callers that got the cached value : %d/%d%n%n", hitCount, CALLER_COUNT);

        assertThat(results)
                .as("every caller's L1 is empty by construction, so every one must fall through to L2 and get the cached value")
                .allSatisfy(result -> assertThat(result).contains(CACHED_JSON));

        verify(valueOps, times(CALLER_COUNT)).get(CACHE_KEY);
    }
}
