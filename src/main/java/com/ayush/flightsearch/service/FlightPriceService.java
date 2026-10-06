package com.ayush.flightsearch.service;

import com.ayush.flightsearch.dto.PriceUpdateResponse;
import com.ayush.flightsearch.entity.Flight;
import com.ayush.flightsearch.exception.FlightNotFoundException;
import com.ayush.flightsearch.repository.FlightRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Write side for flight prices. Changing a price is responsible for invalidating the
 * search cache entry that price feeds into - that is what stops a DB change from being
 * masked by a stale cached response.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlightPriceService {

    private final FlightRepository flightRepository;
    private final FlightCacheService flightCacheService;

    /**
     * Re-prices the flight with the given id, then invalidates the sector that flight
     * belongs to - source/destination are read off the entity, so the right key always
     * goes even though the caller only supplied an id.
     */
    @Transactional
    public PriceUpdateResponse updateFlightPrice(Long id, BigDecimal newPrice) {
        Flight flight = flightRepository.findById(id)
                .orElseThrow(() -> new FlightNotFoundException(id));

        BigDecimal oldPrice = flight.getPrice();
        log.info("Updating flight id={} ({} on {} -> {}) price {} -> {}", id, flight.getFlightNumber(),
                flight.getSource(), flight.getDestination(), oldPrice, newPrice);

        flight.setPrice(newPrice);
        flightRepository.save(flight);

        String source = flight.getSource();
        String destination = flight.getDestination();
        String cacheKey = flightCacheService.buildCacheKey(source, destination);
        log.info("Scheduling cache invalidation of '{}'", cacheKey);
        flightCacheService.evictAfterCommit(source, destination);

        return new PriceUpdateResponse(id, flight.getFlightNumber(), source, destination,
                oldPrice, newPrice, cacheKey);
    }
}
