package com.ayush.flightsearch.service;

import com.ayush.flightsearch.dto.FlightSearchResponse;
import com.ayush.flightsearch.mapper.FlightMapper;
import com.ayush.flightsearch.repository.FlightRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Exercises FlightService's bloom-filter guard against cache penetration: a sector the
 * filter has never seen must short-circuit before touching L1, L2, the lock, or the
 * database. All collaborators are mocked - this is about FlightService's own branching,
 * not the real cache/lock/DB behaviour, which the other test classes already cover.
 */
class FlightServiceBloomFilterTest {

    @Test
    void sectorNotInBloomFilter_returnsEmptyImmediately_withoutTouchingCacheLockOrDatabase() {
        FlightRepository flightRepository = mock(FlightRepository.class);
        FlightMapper flightMapper = mock(FlightMapper.class);
        FlightCacheService flightCacheService = mock(FlightCacheService.class);
        RedisLockService redisLockService = mock(RedisLockService.class);
        FlightBloomFilter flightBloomFilter = mock(FlightBloomFilter.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);

        when(flightBloomFilter.buildKey("DEL", "XXX")).thenReturn("DEL:XXX");
        when(flightBloomFilter.mightContain("DEL:XXX")).thenReturn(false);

        FlightService flightService = new FlightService(
                flightRepository, flightMapper, flightCacheService, redisLockService, flightBloomFilter, objectMapper);

        FlightSearchResponse response = flightService.searchResponse("DEL", "XXX");

        assertThat(response.getFrom()).isEqualTo("DEL");
        assertThat(response.getTo()).isEqualTo("XXX");
        assertThat(response.getFlights())
                .as("a sector the bloom filter has never seen cannot have any flights")
                .isEmpty();

        verifyNoInteractions(flightCacheService, redisLockService, flightRepository);
    }

    @Test
    void sectorInBloomFilter_stillGoesOnToCheckTheCacheAndDatabase() {
        FlightRepository flightRepository = mock(FlightRepository.class);
        FlightMapper flightMapper = mock(FlightMapper.class);
        FlightCacheService flightCacheService = mock(FlightCacheService.class);
        RedisLockService redisLockService = mock(RedisLockService.class);
        FlightBloomFilter flightBloomFilter = mock(FlightBloomFilter.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        RedisLockService.LockLease lease = mock(RedisLockService.LockLease.class);

        when(flightBloomFilter.buildKey("DEL", "BOM")).thenReturn("DEL:BOM");
        when(flightBloomFilter.mightContain("DEL:BOM")).thenReturn(true);
        when(flightCacheService.buildCacheKey("DEL", "BOM")).thenReturn("flight-search:DEL:BOM");
        when(flightCacheService.buildLockCacheKey("DEL", "BOM")).thenReturn("flight-search:lock:DEL:BOM");
        // Both cache reads miss (before and after acquiring the lock), so execution
        // falls all the way through to the database - proof that the bloom filter
        // passing lets the rest of the normal flow run rather than short-circuiting.
        when(flightCacheService.get("flight-search:DEL:BOM")).thenReturn(Optional.empty());
        when(redisLockService.tryAcquire(eq("flight-search:lock:DEL:BOM"), any(Duration.class), any(Duration.class)))
                .thenReturn(Optional.of(lease));
        when(flightRepository.findBySourceIgnoreCaseAndDestinationIgnoreCaseOrderByPriceAsc("DEL", "BOM"))
                .thenReturn(List.of());
        when(flightMapper.toResponseList(List.of())).thenReturn(List.of());
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        FlightService flightService = new FlightService(
                flightRepository, flightMapper, flightCacheService, redisLockService, flightBloomFilter, objectMapper);

        FlightSearchResponse response = flightService.searchResponse("DEL", "BOM");

        assertThat(response.getFlights()).isEmpty();
        verify(flightRepository).findBySourceIgnoreCaseAndDestinationIgnoreCaseOrderByPriceAsc("DEL", "BOM");
    }
}
