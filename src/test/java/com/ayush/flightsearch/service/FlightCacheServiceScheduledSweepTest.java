package com.ayush.flightsearch.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Proves the sweep is a real, automatically-firing scheduler, not just a method that
 * happens to exist. FlightCacheServiceLocalCacheLeakTest calls evictExpiredLocalEntries()
 * directly, which says nothing about whether Spring ever calls it on its own - @Scheduled
 * is inert without @EnableScheduling and a live ApplicationContext driving it.
 *
 * <p>Uses the real FlightCacheService singleton and the real background scheduler; only
 * waits and asserts, never calls evictExpiredLocalEntries() or reads any key back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FlightCacheServiceScheduledSweepTest {

    // Must stay comfortably under FlightCacheService's LFU capacity bound (see
    // FlightCacheServiceLfuEvictionTest) - otherwise inserting this many distinct keys
    // would itself trigger capacity eviction before the scheduled TTL sweep ever gets a
    // chance to run, confounding the two mechanisms this test is trying to tell apart.
    private static final int UNIQUE_KEY_COUNT = FlightCacheService.maxLocalCacheSize() / 2;

    @Autowired
    private FlightCacheService flightCacheService;

    @Test
    @Timeout(30)
    void scheduledSweep_reclaimsExpiredEntries_withoutBeingCalledDirectly() {
        for (int i = 0; i < UNIQUE_KEY_COUNT; i++) {
            String key = flightCacheService.buildCacheKey("SCHEDSRC" + i, "SCHEDDST" + i);
            flightCacheService.put(key, "{\"flights\":[]}", Duration.ofMinutes(5));
        }
        int sizeAfterInsert = flightCacheService.localCacheSize();
        int floorOnceSwept = sizeAfterInsert - UNIQUE_KEY_COUNT;

        System.out.printf("%n=== L1, real Spring scheduler, zero manual sweep calls ===%n");
        System.out.printf("  map size right after insert : %d%n", sizeAfterInsert);

        // LOCAL_TTL and SWEEP_INTERVAL_MS are both 5s, so worst case is one full TTL to
        // expire plus one full interval before the next tick catches it - about 10s.
        // Double that for headroom on a loaded CI box; poll often enough to see it land
        // without needing to guess the exact tick.
        await("background sweep reclaims all expired, never-re-read entries")
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(flightCacheService.localCacheSize())
                        .as("the real scheduler must have reclaimed this test's entries on its own")
                        .isLessThanOrEqualTo(floorOnceSwept));

        System.out.printf("  map size after scheduler swept it, unprompted: %d%n%n",
                flightCacheService.localCacheSize());
    }
}
