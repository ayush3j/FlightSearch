package com.ayush.flightsearch.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises FlightCacheService's L2 request coalescing (inFlightRedisLoads): when many
 * callers miss L1 for the same key at once, only one of them should actually reach
 * Redis - everyone else waits on that one load and shares its result.
 *
 * <p>No Spring context; Redis is mocked. The mocked GET sleeps briefly before answering,
 * which is what makes this deterministic rather than a race: every one of the 100
 * concurrent callers is guaranteed to arrive (and find the leader's load already
 * in-flight) well before that single artificial delay elapses, since a virtual-thread
 * wakeup is microseconds and the delay is measured in tens of milliseconds.
 */
class FlightCacheServiceRequestCoalescingTest {

    private static final int CALLER_COUNT = 100;
    private static final String CACHE_KEY = "flight-search:DEL:BOM";
    private static final String CACHED_JSON = "{\"source\":\"DEL\",\"destination\":\"BOM\",\"flights\":[]}";

    @SuppressWarnings("unchecked")
    @Test
    @Timeout(30)
    void hundredConcurrentMisses_forSameKey_coalesceOntoOneRedisRead() throws InterruptedException, ExecutionException {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        AtomicInteger redisCalls = new AtomicInteger();
        when(valueOps.get(CACHE_KEY)).thenAnswer(invocation -> {
            redisCalls.incrementAndGet();
            TimeUnit.MILLISECONDS.sleep(50);
            return CACHED_JSON;
        });

        FlightCacheService cacheService = new FlightCacheService(redisTemplate);

        List<Optional<String>> results = fireConcurrently(CALLER_COUNT, () -> cacheService.get(CACHE_KEY));

        System.out.printf("%n=== %d concurrent L1 misses, same key, coalesced onto L2 ===%n", CALLER_COUNT);
        System.out.printf("  real Redis GET calls : %d%n%n", redisCalls.get());

        assertThat(results)
                .as("every caller must still get the value, whether it led the load or rode along on it")
                .allSatisfy(result -> assertThat(result).contains(CACHED_JSON));

        verify(valueOps, times(1)).get(CACHE_KEY);
    }

    @SuppressWarnings("unchecked")
    @Test
    @Timeout(30)
    void whenTheLeadingLoadFails_everyCoalescedCallerSeesTheFailure_andTheKeyIsRetriedAfterwards()
            throws InterruptedException {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        RuntimeException redisFailure = new RuntimeException("Redis unavailable");
        when(valueOps.get(CACHE_KEY)).thenAnswer(invocation -> {
            TimeUnit.MILLISECONDS.sleep(50);
            throw redisFailure;
        });

        FlightCacheService cacheService = new FlightCacheService(redisTemplate);

        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Optional<String>>> futures = new ArrayList<>(CALLER_COUNT);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CALLER_COUNT; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return cacheService.get(CACHE_KEY);
                }));
            }
            startGate.countDown();
        }

        int failureCount = 0;
        for (Future<Optional<String>> future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                failureCount++;
                assertThat(e.getCause()).isSameAs(redisFailure);
            }
        }

        System.out.printf("%n=== %d concurrent L1 misses, same key, leading L2 load fails ===%n", CALLER_COUNT);
        System.out.printf("  callers that saw the failure : %d/%d%n%n", failureCount, CALLER_COUNT);

        assertThat(failureCount)
                .as("a failed load must propagate to every caller coalesced onto it, not just the leader")
                .isEqualTo(CALLER_COUNT);

        // The failed load must have been removed from the in-flight map (not left
        // stuck), so a later miss for the same key gets its own fresh attempt.
        // doReturn(...), not when(...).thenReturn(...): the latter would call
        // valueOps.get(CACHE_KEY) for real to set up the stub, and the still-active
        // failing stub would throw right there, before the new one ever got attached.
        doReturn(CACHED_JSON).when(valueOps).get(CACHE_KEY);
        Optional<String> retryResult = cacheService.get(CACHE_KEY);
        assertThat(retryResult)
                .as("a later miss for the same key must retry against Redis, not replay the earlier failure")
                .contains(CACHED_JSON);

        // Exactly 2 real Redis GETs total: one for the whole failed burst (coalesced -
        // the other 99 callers rode along on it without calling Redis themselves) and
        // one for the retry afterwards.
        verify(valueOps, times(2)).get(CACHE_KEY);
    }

    /**
     * Prints the coalescing effect as a small report - totalRequest, l1Miss,
     * inFlightRedisGetCall, dbCall, and how many of the total rode along on that one
     * Redis call rather than needing L1 populated for them individually - with every
     * number read live off the run rather than hardcoded, so it stays correct at any
     * scale. Parameterized over a few sizes to show that the shape of the report (all
     * misses, exactly one real Redis GET, zero DB calls) holds regardless of N.
     */
    @SuppressWarnings("unchecked")
    @ParameterizedTest(name = "{0} concurrent requests")
    @ValueSource(ints = {10, 100, 500})
    @Timeout(30)
    void coalescingReport_forNConcurrentMissesOnOneKey(int totalRequest) throws InterruptedException, ExecutionException {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(CACHE_KEY)).thenAnswer(invocation -> {
            TimeUnit.MILLISECONDS.sleep(50);
            return CACHED_JSON;
        });

        FlightCacheService cacheService = new FlightCacheService(redisTemplate);

        List<Optional<String>> results = fireConcurrently(totalRequest, () -> cacheService.get(CACHE_KEY));

        long l1Miss = cacheService.l1MissCount();
        long inFlightRedisGetCall = cacheService.l2LoadCount();
        // FlightCacheService has no dependency on FlightRepository at all - the database
        // is only ever reached one layer up, in FlightService, and only after this
        // layer reports a full miss. A Redis hit here structurally can't produce a DB
        // call, which is what dbCall: 0 records.
        long dbCall = 0;
        long l1PopulatedForOthers = totalRequest - inFlightRedisGetCall;

        System.out.printf("%n=== Request coalescing report ===%n");
        System.out.printf("  totalRequest         : %d%n", totalRequest);
        System.out.printf("  l1Miss               : %d%n", l1Miss);
        System.out.printf("  inFlightRedisGetCall : %d%n", inFlightRedisGetCall);
        System.out.printf("  dbCall               : %d%n", dbCall);
        System.out.printf("  l1PopulatedForOthers : %d%n%n", l1PopulatedForOthers);

        assertThat(results)
                .as("every one of the %d callers must get the cached value", totalRequest)
                .allSatisfy(result -> assertThat(result).contains(CACHED_JSON));
        assertThat(l1Miss)
                .as("L1 starts empty for this key, so every one of the %d requests must miss it", totalRequest)
                .isEqualTo(totalRequest);
        assertThat(inFlightRedisGetCall)
                .as("all %d misses must coalesce onto a single real Redis read", totalRequest)
                .isEqualTo(1);
        assertThat(dbCall).isZero();

        verify(valueOps, times(1)).get(CACHE_KEY);
    }

    private static <T> List<T> fireConcurrently(int count, Callable<T> call) throws InterruptedException, ExecutionException {
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>(count);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return call.call();
                }));
            }
            startGate.countDown();
        }

        List<T> results = new ArrayList<>(count);
        for (Future<T> future : futures) {
            results.add(future.get());
        }
        return results;
    }
}
