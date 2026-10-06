package com.ayush.flightsearch.service;

// Superseded by the Caffeine-backed L1 in FlightCacheService: Caffeine's W-TinyLFU
// eviction is approximate and gives no per-key frequency count to assert on, so this
// test's exact "evict precisely the lowest-frequency entry" assertions no longer apply.
// Kept commented out, not deleted, for reference against the old manual implementation
// (also commented out at the bottom of FlightCacheService.java).
//
// package com.ayush.flightsearch.service;
//
// import org.junit.jupiter.api.Test;
// import org.junit.jupiter.api.Timeout;
// import org.springframework.data.redis.core.StringRedisTemplate;
// import org.springframework.data.redis.core.ValueOperations;
// 
// import java.time.Duration;
// 
// import static org.assertj.core.api.Assertions.assertThat;
// import static org.mockito.Mockito.mock;
// import static org.mockito.Mockito.when;
// 
// /**
//  * Exercises FlightCacheService's L1 size bound and its LFU eviction on insert - no
//  * Spring context, Redis (L2) mocked out, so these are purely about L1's own bookkeeping.
//  */
// class FlightCacheServiceLfuEvictionTest {
// 
//     private static final int MAX_SIZE = FlightCacheService.maxLocalCacheSize();
// 
//     @Test
//     @Timeout(30)
//     void size_neverExceedsTheConfiguredBound_regardlessOfHowManyDistinctKeysAreWritten() {
//         FlightCacheService cacheService = newCacheServiceWithMockedRedis();
//         int keysWritten = MAX_SIZE * 3;
// 
//         for (int i = 0; i < keysWritten; i++) {
//             String key = cacheService.buildCacheKey("BOUND" + i, "BOUND" + i);
//             cacheService.put(key, "{}", Duration.ofMinutes(5));
//         }
// 
//         System.out.printf("%n=== L1 size bound ===%n");
//         System.out.printf("  distinct keys written : %d%n", keysWritten);
//         System.out.printf("  final L1 size          : %d  (bound: %d)%n%n",
//                 cacheService.localCacheSize(), MAX_SIZE);
// 
//         assertThat(cacheService.localCacheSize())
//                 .as("L1 must never grow past its configured bound")
//                 .isEqualTo(MAX_SIZE);
//     }
// 
//     @Test
//     @Timeout(30)
//     void insertPastCapacity_evictsTheLeastFrequentEntry_neverAMoreFrequentOne() {
//         FlightCacheService cacheService = newCacheServiceWithMockedRedis();
// 
//         // Fill to exactly the bound, each key at a distinct frequency: FILL0 sits at the
//         // baseline (1, never separately read), FILL1 at 2 (one extra read), ...,
//         // FILL(n-1) at n (n-1 extra reads). No ties among these MAX_SIZE keys.
//         for (int i = 0; i < MAX_SIZE; i++) {
//             String key = cacheService.buildCacheKey("FILL" + i, "FILL" + i);
//             cacheService.put(key, "{}", Duration.ofMinutes(5));
//             for (int extraRead = 0; extraRead < i; extraRead++) {
//                 cacheService.get(key);
//             }
//         }
//         assertThat(cacheService.localCacheSize()).isEqualTo(MAX_SIZE);
//         for (int i = 0; i < MAX_SIZE; i++) {
//             assertThat(cacheService.localFrequencyOf(cacheService.buildCacheKey("FILL" + i, "FILL" + i)))
//                     .as("FILL%d should be at frequency %d before the triggering insert", i, i + 1)
//                     .isEqualTo(i + 1);
//         }
// 
//         // One more distinct key. New entries start at frequency 1 (see putLocal's
//         // javadoc), so it ties ONLY with FILL0 - every other FILL key is strictly higher
//         // and must be untouchable by this eviction.
//         String newKey = cacheService.buildCacheKey("NEWSRC", "NEWDST");
//         cacheService.put(newKey, "{}", Duration.ofMinutes(5));
// 
//         System.out.printf("%n=== LFU eviction on insert past capacity ===%n");
//         System.out.printf("  size before triggering insert : %d%n", MAX_SIZE);
//         System.out.printf("  size after                    : %d%n", cacheService.localCacheSize());
// 
//         assertThat(cacheService.localCacheSize())
//                 .as("capacity must be restored to the bound after the new insert")
//                 .isEqualTo(MAX_SIZE);
// 
//         for (int i = 1; i < MAX_SIZE; i++) {
//             assertThat(cacheService.localFrequencyOf(cacheService.buildCacheKey("FILL" + i, "FILL" + i)))
//                     .as("FILL%d (frequency %d) must never be evicted in favour of a frequency-1 key", i, i + 1)
//                     .isEqualTo(i + 1);
//         }
// 
//         boolean fill0Survived = cacheService.localFrequencyOf(cacheService.buildCacheKey("FILL0", "FILL0")) >= 0;
//         boolean newKeySurvived = cacheService.localFrequencyOf(newKey) >= 0;
//         System.out.printf("  FILL0 (freq 1) survived : %b%n", fill0Survived);
//         System.out.printf("  new key (freq 1) survived: %b%n%n", newKeySurvived);
// 
//         assertThat(fill0Survived ^ newKeySurvived)
//                 .as("exactly one of the two frequency-1 keys must have been evicted - a tie, "
//                         + "broken by scan order, but never resolved by touching a higher-frequency key")
//                 .isTrue();
//     }
// 
//     @Test
//     @Timeout(30)
//     void hotKeys_surviveContinuousChurnFromManyColdInsertsThatAreNeverReRead() {
//         FlightCacheService cacheService = newCacheServiceWithMockedRedis();
// 
//         int hotKeyCount = 5;
//         String[] hotKeys = new String[hotKeyCount];
//         for (int i = 0; i < hotKeyCount; i++) {
//             hotKeys[i] = cacheService.buildCacheKey("HOT" + i, "HOT" + i);
//             cacheService.put(hotKeys[i], "{}", Duration.ofMinutes(5));
//         }
// 
//         // Far more distinct cold keys than the bound - each written once and never read
//         // again - while the hot keys keep getting genuine repeat reads throughout, the
//         // way a handful of popular routes would under real traffic.
//         int coldKeyCount = MAX_SIZE * 5;
//         for (int i = 0; i < coldKeyCount; i++) {
//             String coldKey = cacheService.buildCacheKey("COLD" + i, "COLD" + i);
//             cacheService.put(coldKey, "{}", Duration.ofMinutes(5));
//             for (String hotKey : hotKeys) {
//                 cacheService.get(hotKey);
//             }
//         }
// 
//         System.out.printf("%n=== hot keys under sustained cold-key churn ===%n");
//         System.out.printf("  cold keys written (never re-read) : %d%n", coldKeyCount);
//         System.out.printf("  final L1 size                      : %d (bound: %d)%n",
//                 cacheService.localCacheSize(), MAX_SIZE);
//         for (String hotKey : hotKeys) {
//             System.out.printf("  %-24s frequency: %d%n", hotKey, cacheService.localFrequencyOf(hotKey));
//         }
//         System.out.println();
// 
//         assertThat(cacheService.localCacheSize())
//                 .as("must never exceed the configured bound even under sustained churn")
//                 .isEqualTo(MAX_SIZE);
// 
//         for (String hotKey : hotKeys) {
//             assertThat(cacheService.localFrequencyOf(hotKey))
//                     .as("a repeatedly-read hot key must still be resident after %d cold insertions",
//                             coldKeyCount)
//                     .isGreaterThanOrEqualTo(coldKeyCount);
//         }
//     }
// 
//     @SuppressWarnings("unchecked")
//     private static FlightCacheService newCacheServiceWithMockedRedis() {
//         StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
//         ValueOperations<String, String> valueOps = mock(ValueOperations.class);
//         when(redisTemplate.opsForValue()).thenReturn(valueOps);
//         return new FlightCacheService(redisTemplate);
//     }
// }
