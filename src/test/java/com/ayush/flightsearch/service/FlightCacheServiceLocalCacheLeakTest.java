package com.ayush.flightsearch.service;

// Superseded by the Caffeine-backed L1 in FlightCacheService: there is no manual sweep
// method to call directly anymore (Caffeine purges expired entries itself, proactively
// via the attached Scheduler). Kept commented out, not deleted, for reference against
// the old manual implementation (also commented out at the bottom of
// FlightCacheService.java).
//
// package com.ayush.flightsearch.service;
//
// import org.junit.jupiter.api.Test;
// import org.junit.jupiter.api.Timeout;
// import org.springframework.data.redis.core.StringRedisTemplate;
// import org.springframework.data.redis.core.ValueOperations;
// 
// import java.time.Duration;
// import java.util.concurrent.TimeUnit;
// 
// import static org.assertj.core.api.Assertions.assertThat;
// import static org.mockito.Mockito.mock;
// import static org.mockito.Mockito.when;
// 
// /**
//  * Exercises FlightCacheService's L1 tier in isolation - no Spring context, Redis (L2)
//  * mocked out - so these are purely about the map's own bookkeeping:
//  *
//  * <ol>
//  *   <li>left alone, an expired-but-unread entry is not reclaimed (get() only reclaims
//  *       the exact key it is asked for, and nothing here ever asks for one again);</li>
//  *   <li>the sweep method itself, invoked directly, correctly reclaims every expired
//  *       entry regardless of whether it was ever read.</li>
//  * </ol>
//  *
//  * <p>Neither test proves the sweep actually runs on its own in the app - that needs a
//  * real Spring context with scheduling enabled, since @Scheduled does nothing without
//  * it. See FlightCacheServiceScheduledSweepTest for that.
//  */
// class FlightCacheServiceLocalCacheLeakTest {
// 
//     // Must stay comfortably under FlightCacheService's LFU capacity bound (see
//     // FlightCacheServiceLfuEvictionTest) - otherwise inserting this many distinct keys
//     // would itself trigger capacity eviction, confounding what these tests are actually
//     // about: TTL expiry with no capacity pressure involved.
//     private static final int UNIQUE_KEY_COUNT = FlightCacheService.maxLocalCacheSize() / 2;
// 
//     /** Must clear LOCAL_TTL (5s) with margin; the test sleeps past it once, for every key at once. */
//     private static final Duration PAST_LOCAL_TTL = Duration.ofSeconds(6);
// 
//     @Test
//     @Timeout(30)
//     void expiredEntriesThatAreNeverReRead_areNotReclaimedByThemselves() throws InterruptedException {
//         FlightCacheService cacheService = newCacheServiceWithMockedRedis();
// 
//         insertUniqueKeys(cacheService, UNIQUE_KEY_COUNT);
//         int sizeAfterInsert = cacheService.localCacheSize();
// 
//         // Outlive LOCAL_TTL by a comfortable margin. Nothing below re-reads a single one
//         // of the keys written above, and nothing sweeps the map - that is the scenario.
//         TimeUnit.MILLISECONDS.sleep(PAST_LOCAL_TTL.toMillis());
//         int sizeAfterExpiry = cacheService.localCacheSize();
// 
//         System.out.printf("%n=== L1, no sweep invoked, zero re-reads ===%n");
//         System.out.printf("  map size right after insert : %d%n", sizeAfterInsert);
//         System.out.printf("  map size after TTL expiry    : %d  <- unchanged%n%n", sizeAfterExpiry);
// 
//         assertThat(sizeAfterInsert)
//                 .as("every unique key gets its own L1 entry")
//                 .isEqualTo(UNIQUE_KEY_COUNT);
//         assertThat(sizeAfterExpiry)
//                 .as("nothing read or swept these keys, so nothing reclaimed them")
//                 .isEqualTo(sizeAfterInsert);
//     }
// 
//     @Test
//     @Timeout(30)
//     void sweep_reclaimsExpiredEntries_evenThoughNoneWereEverReRead() throws InterruptedException {
//         FlightCacheService cacheService = newCacheServiceWithMockedRedis();
// 
//         insertUniqueKeys(cacheService, UNIQUE_KEY_COUNT);
//         TimeUnit.MILLISECONDS.sleep(PAST_LOCAL_TTL.toMillis());
// 
//         // Same setup as the test above, but this time the sweep itself is called - the
//         // exact method @Scheduled runs on a timer in the real application.
//         cacheService.evictExpiredLocalEntries();
//         int sizeAfterSweep = cacheService.localCacheSize();
// 
//         System.out.printf("%n=== L1, one sweep invoked, zero re-reads ===%n");
//         System.out.printf("  map size after one sweep call: %d  <- reclaimed without a single read%n%n",
//                 sizeAfterSweep);
// 
//         assertThat(sizeAfterSweep)
//                 .as("the sweep must reclaim an expired entry on its own, with no read ever asking for it")
//                 .isZero();
//     }
// 
//     @SuppressWarnings("unchecked")
//     private static FlightCacheService newCacheServiceWithMockedRedis() {
//         StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
//         ValueOperations<String, String> valueOps = mock(ValueOperations.class);
//         when(redisTemplate.opsForValue()).thenReturn(valueOps);
//         return new FlightCacheService(redisTemplate);
//     }
// 
//     private static void insertUniqueKeys(FlightCacheService cacheService, int count) {
//         for (int i = 0; i < count; i++) {
//             String key = cacheService.buildCacheKey("SRC" + i, "DST" + i);
//             cacheService.put(key, "{\"flights\":[]}", Duration.ofMinutes(5));
//         }
//     }
// }
