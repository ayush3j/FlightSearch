package com.ayush.flightsearch.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the hand-rolled FlightBloomFilter directly - no Spring context needed,
 * it's a plain in-memory data structure.
 */
class FlightBloomFilterTest {

    @Test
    void addedKeys_alwaysAnswerMightContainTrue_neverAFalseNegative() {
        FlightBloomFilter filter = new FlightBloomFilter(1_000, 0.01);

        for (int i = 0; i < 1_000; i++) {
            filter.add("flight-search:SEC" + i + ":DST" + i);
        }

        for (int i = 0; i < 1_000; i++) {
            assertThat(filter.mightContain("flight-search:SEC" + i + ":DST" + i))
                    .as("a Bloom filter must never produce a false negative for a key it was given")
                    .isTrue();
        }
    }

    @Test
    void neverAddedKey_mightContainAnswersFalse_whenNothingHasBeenAdded() {
        FlightBloomFilter filter = new FlightBloomFilter(1_000, 0.01);

        assertThat(filter.mightContain("flight-search:DEL:BOM"))
                .as("an empty filter has no bits set, so nothing can look like a match")
                .isFalse();
    }

    @Test
    void falsePositiveRate_forKeysNeverAdded_staysWithinAGenerousMultipleOfTheTarget() {
        double targetRate = 0.01;
        int expectedInsertions = 1_000;
        FlightBloomFilter filter = new FlightBloomFilter(expectedInsertions, targetRate);

        for (int i = 0; i < expectedInsertions; i++) {
            filter.add("flight-search:REAL" + i + ":SECTOR" + i);
        }

        int probesNeverAdded = 20_000;
        int falsePositives = 0;
        for (int i = 0; i < probesNeverAdded; i++) {
            if (filter.mightContain("flight-search:ABSENT" + i + ":SECTOR" + i)) {
                falsePositives++;
            }
        }

        double observedRate = (double) falsePositives / probesNeverAdded;
        System.out.printf("%n=== Bloom filter false-positive rate ===%n");
        System.out.printf("  target rate   : %.4f%n", targetRate);
        System.out.printf("  observed rate : %.4f (%d/%d probes)%n%n",
                observedRate, falsePositives, probesNeverAdded);

        // Generous multiple of the target, not an exact match: this is a probabilistic
        // structure sized by a formula that assumes an ideal hash, and ours is a
        // hand-rolled FNV-1a + SplitMix64 mix, not a cryptographic hash - some slack
        // keeps this assertion from flaking on an unlucky run while still catching a
        // genuinely broken implementation (e.g. one hash function reused hashCount
        // times, which would blow the rate up far past any reasonable multiple).
        assertThat(observedRate)
                .as("observed false-positive rate should stay in the neighbourhood of the configured target")
                .isLessThan(targetRate * 3);
    }

    @Test
    void hash_isDeterministic_sameKeyAndSeedAlwaysProduceTheSameValue() {
        FlightBloomFilter filter = new FlightBloomFilter(1_000, 0.01);

        long first = filter.hash("flight-search:DEL:BOM", 3);
        long second = filter.hash("flight-search:DEL:BOM", 3);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void hash_variesAcrossSeeds_forTheSameKey() {
        FlightBloomFilter filter = new FlightBloomFilter(1_000, 0.01);

        Set<Long> valuesAcrossSeeds = new HashSet<>();
        for (int seed = 0; seed < filter.hashCount(); seed++) {
            valuesAcrossSeeds.add(filter.hash("flight-search:DEL:BOM", seed));
        }

        assertThat(valuesAcrossSeeds)
                .as("distinct seeds must not collapse onto the same hash value for one key - "
                        + "otherwise every key would only ever set/check one real bit position, "
                        + "not hashCount of them")
                .hasSize(filter.hashCount());
    }

    @Test
    void hash_variesAcrossKeys_forTheSameSeed() {
        FlightBloomFilter filter = new FlightBloomFilter(1_000, 0.01);

        long a = filter.hash("flight-search:DEL:BOM", 0);
        long b = filter.hash("flight-search:BOM:DEL", 0);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void constructor_rejectsNonPositiveExpectedInsertions() {
        assertThatThrownBy(() -> new FlightBloomFilter(0, 0.01))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsFalsePositiveRateOutOfRange() {
        assertThatThrownBy(() -> new FlightBloomFilter(1_000, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FlightBloomFilter(1_000, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
