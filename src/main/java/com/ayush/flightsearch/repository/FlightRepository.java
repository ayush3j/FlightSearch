package com.ayush.flightsearch.repository;

import com.ayush.flightsearch.entity.Flight;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FlightRepository extends JpaRepository<Flight, Long> {

    /**
     * Derived query: SELECT * FROM flight WHERE UPPER(source) = UPPER(?) AND UPPER(destination) = UPPER(?)
     * ordered by cheapest first.
     */
    List<Flight> findBySourceIgnoreCaseAndDestinationIgnoreCaseOrderByPriceAsc(String source, String destination);

    /** Every distinct sector that has at least one flight - what FlightBloomFilterInitializer loads at startup. */
    @Query("SELECT DISTINCT f.source AS source, f.destination AS destination FROM Flight f")
    List<SectorProjection> findDistinctSectors();

    /** Interface-based projection: Spring Data fills this from the query's "source"/"destination" aliases. */
    interface SectorProjection {
        String getSource();

        String getDestination();
    }
}
