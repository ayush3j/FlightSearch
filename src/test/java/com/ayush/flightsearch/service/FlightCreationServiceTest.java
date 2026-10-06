package com.ayush.flightsearch.service;

import com.ayush.flightsearch.dto.FlightCreateResponse;
import com.ayush.flightsearch.entity.Flight;
import com.ayush.flightsearch.repository.FlightRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises FlightCreationService's own branching - repository save, bloom filter
 * update, and conditional cache invalidation - with all collaborators mocked. The real
 * cache/bloom-filter/DB behaviour is covered by their own test classes.
 */
class FlightCreationServiceTest {

    @Test
    void addFlight_savesNormalizedFlight_andAlwaysAddsTheSectorToTheBloomFilter() {
        FlightRepository flightRepository = mock(FlightRepository.class);
        FlightCacheService flightCacheService = mock(FlightCacheService.class);
        FlightBloomFilter flightBloomFilter = mock(FlightBloomFilter.class);

        when(flightRepository.save(any(Flight.class))).thenAnswer(invocation -> {
            Flight saved = invocation.getArgument(0);
            saved.setId(42L);
            return saved;
        });
        when(flightBloomFilter.buildKey("DEL", "BOM")).thenReturn("DEL:BOM");
        when(flightCacheService.buildCacheKey("DEL", "BOM")).thenReturn("flight-search:DEL:BOM");
        when(flightCacheService.get("flight-search:DEL:BOM")).thenReturn(Optional.empty());

        FlightCreationService service = new FlightCreationService(flightRepository, flightCacheService, flightBloomFilter);

        // Deliberately lower-case, to prove normalisation happens before it reaches
        // the repository, the bloom filter key, and the cache key - all three, or a
        // flight added as "del"/"bom" would be invisible to a search for "DEL"/"BOM".
        FlightCreateResponse response = service.addFlight("AI-1", "del", "bom", "Air India", new BigDecimal("4999.00"));

        ArgumentCaptor<Flight> savedFlight = ArgumentCaptor.forClass(Flight.class);
        verify(flightRepository).save(savedFlight.capture());
        assertThat(savedFlight.getValue().getSource()).isEqualTo("DEL");
        assertThat(savedFlight.getValue().getDestination()).isEqualTo("BOM");

        verify(flightBloomFilter).add("DEL:BOM");

        assertThat(response.getId()).isEqualTo(42L);
        assertThat(response.getSource()).isEqualTo("DEL");
        assertThat(response.getDestination()).isEqualTo("BOM");
        assertThat(response.getCacheKey()).isEqualTo("flight-search:DEL:BOM");
        assertThat(response.isCacheInvalidated())
                .as("nothing was cached for this sector, so there was nothing to invalidate")
                .isFalse();

        verify(flightCacheService, never()).evictAfterCommit(any(), any());
    }

    @Test
    void addFlight_whenASearchResultIsAlreadyCached_invalidatesIt() {
        FlightRepository flightRepository = mock(FlightRepository.class);
        FlightCacheService flightCacheService = mock(FlightCacheService.class);
        FlightBloomFilter flightBloomFilter = mock(FlightBloomFilter.class);

        when(flightRepository.save(any(Flight.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(flightBloomFilter.buildKey("DEL", "BOM")).thenReturn("DEL:BOM");
        when(flightCacheService.buildCacheKey("DEL", "BOM")).thenReturn("flight-search:DEL:BOM");
        when(flightCacheService.get("flight-search:DEL:BOM"))
                .thenReturn(Optional.of("{\"from\":\"DEL\",\"to\":\"BOM\",\"flights\":[]}"));

        FlightCreationService service = new FlightCreationService(flightRepository, flightCacheService, flightBloomFilter);

        FlightCreateResponse response = service.addFlight("AI-2", "DEL", "BOM", "Air India", new BigDecimal("3999.00"));

        assertThat(response.isCacheInvalidated()).isTrue();
        verify(flightCacheService).evictAfterCommit("DEL", "BOM");
    }
}
