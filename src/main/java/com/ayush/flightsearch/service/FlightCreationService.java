package com.ayush.flightsearch.service;

import com.ayush.flightsearch.dto.FlightCreateResponse;
import com.ayush.flightsearch.entity.Flight;
import com.ayush.flightsearch.repository.FlightRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Write side for adding a new flight (and, when it's the first one on that route, a
 * new sector) to the flight table. Two things must stay correct whenever a flight is
 * inserted:
 *
 * <ol>
 *   <li>a cached search result for this sector, if one exists, was computed before
 *       this flight existed and is now stale - invalidated the same way
 *       FlightPriceService invalidates one after a price change;</li>
 *   <li>FlightBloomFilter, which guards FlightService against cache penetration, must
 *       know this sector is real - otherwise every future search for it would be
 *       wrongly short-circuited as "definitely doesn't exist" before ever reaching the
 *       cache or the database.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlightCreationService {

    private final FlightRepository flightRepository;
    private final FlightCacheService flightCacheService;
    private final FlightBloomFilter flightBloomFilter;

    @Transactional
    public FlightCreateResponse addFlight(String flightNumber, String source, String destination,
                                           String airline, BigDecimal price) {
        String normalizedSource = normalize(source);
        String normalizedDestination = normalize(destination);

        Flight flight = new Flight();
        flight.setFlightNumber(flightNumber);
        flight.setSource(normalizedSource);
        flight.setDestination(normalizedDestination);
        flight.setAirline(airline);
        flight.setPrice(price);
        flight = flightRepository.save(flight);

        log.info("Added flight id={} ({} on {} -> {}, {})", flight.getId(), flightNumber,
                normalizedSource, normalizedDestination, price);

        // Idempotent whether or not this sector already existed - an already-known
        // sector's bits just get set again, a no-op. Safe to do here rather than after
        // commit: worst case, a later rollback leaves a bit set for a sector that
        // isn't real yet, which is exactly the false-positive behaviour this filter
        // already tolerates by design - unlike the cache invalidation below, it is not
        // a correctness break.
        flightBloomFilter.add(flightBloomFilter.buildKey(normalizedSource, normalizedDestination));

        String cacheKey = flightCacheService.buildCacheKey(normalizedSource, normalizedDestination);
        boolean cacheInvalidated = flightCacheService.get(cacheKey).isPresent();
        if (cacheInvalidated) {
            log.info("Scheduling cache invalidation of '{}' - it was computed before this flight existed",
                    cacheKey);
            flightCacheService.evictAfterCommit(normalizedSource, normalizedDestination);
        }

        return new FlightCreateResponse(flight.getId(), flightNumber, normalizedSource, normalizedDestination,
                airline, price, cacheKey, cacheInvalidated);
    }

    private static String normalize(String airportCode) {
        return airportCode.trim().toUpperCase();
    }
}
