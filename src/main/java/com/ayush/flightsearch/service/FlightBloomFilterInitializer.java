package com.ayush.flightsearch.service;

import com.ayush.flightsearch.repository.FlightRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Loads every distinct source/destination sector currently in the database into
 * FlightBloomFilter, once, on application startup - so the filter can answer
 * mightContain() for the app's whole real route table from the very first search,
 * rather than only learning routes as they happen to get searched.
 *
 * <p>Runs as an ApplicationRunner, after the context (and so the database connection
 * pool) is fully up, rather than earlier in the bean lifecycle via @PostConstruct.
 *
 * <p>Routes added after this point (e.g. a brand-new sector introduced by a later
 * data change) are not covered until the app restarts - there is no add-on-write
 * hook here, since new routes are not part of this app's current write path
 * (FlightPriceService only changes a flight's price, never its source/destination).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlightBloomFilterInitializer implements ApplicationRunner {

    private final FlightRepository flightRepository;
    private final FlightBloomFilter flightBloomFilter;

    @Override
    public void run(ApplicationArguments args) {
        List<FlightRepository.SectorProjection> sectors = flightRepository.findDistinctSectors();
        for (FlightRepository.SectorProjection sector : sectors) {
            flightBloomFilter.add(flightBloomFilter.buildKey(sector.getSource(), sector.getDestination()));
        }
        log.info("Bloom filter initialized with {} distinct sector(s) from the database", sectors.size());
    }
}
