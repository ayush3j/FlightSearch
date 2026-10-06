package com.ayush.flightsearch.service;

import com.ayush.flightsearch.repository.FlightRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises FlightBloomFilterInitializer against a mocked repository and a real
 * FlightBloomFilter, so the assertions are about actual mightContain() answers rather
 * than mocked ones.
 */
class FlightBloomFilterInitializerTest {

    @Test
    void run_addsEveryDistinctSectorFromTheRepository_intoTheBloomFilter() {
        FlightRepository flightRepository = mock(FlightRepository.class);
        when(flightRepository.findDistinctSectors()).thenReturn(List.of(
                sector("DEL", "BOM"),
                sector("BOM", "DEL"),
                sector("del", "blr"))); // lower-case on purpose: normalisation must still match
        FlightBloomFilter flightBloomFilter = new FlightBloomFilter(1_000, 0.01);

        new FlightBloomFilterInitializer(flightRepository, flightBloomFilter).run(mock(ApplicationArguments.class));

        assertThat(flightBloomFilter.mightContain(flightBloomFilter.buildKey("DEL", "BOM"))).isTrue();
        assertThat(flightBloomFilter.mightContain(flightBloomFilter.buildKey("BOM", "DEL"))).isTrue();
        assertThat(flightBloomFilter.mightContain(flightBloomFilter.buildKey("DEL", "BLR")))
                .as("a sector loaded in lower case must still be found by an upper-case lookup")
                .isTrue();
        assertThat(flightBloomFilter.mightContain(flightBloomFilter.buildKey("XXX", "YYY")))
                .as("a sector that was never in the repository should not have been added")
                .isFalse();
    }

    @Test
    void run_withNoSectorsInTheRepository_leavesTheFilterEmpty() {
        FlightRepository flightRepository = mock(FlightRepository.class);
        when(flightRepository.findDistinctSectors()).thenReturn(List.of());
        FlightBloomFilter flightBloomFilter = new FlightBloomFilter(1_000, 0.01);

        new FlightBloomFilterInitializer(flightRepository, flightBloomFilter).run(mock(ApplicationArguments.class));

        assertThat(flightBloomFilter.mightContain(flightBloomFilter.buildKey("DEL", "BOM"))).isFalse();
    }

    private static FlightRepository.SectorProjection sector(String source, String destination) {
        return new FlightRepository.SectorProjection() {
            @Override
            public String getSource() {
                return source;
            }

            @Override
            public String getDestination() {
                return destination;
            }
        };
    }
}
