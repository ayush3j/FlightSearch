package com.ayush.flightsearch.controller;

import com.ayush.flightsearch.dto.CreateFlightRequest;
import com.ayush.flightsearch.dto.FlightCreateResponse;
import com.ayush.flightsearch.dto.FlightSearchResponse;
import com.ayush.flightsearch.dto.PriceUpdateResponse;
import com.ayush.flightsearch.service.FlightCreationService;
import com.ayush.flightsearch.service.FlightPriceService;
import com.ayush.flightsearch.service.FlightService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@Slf4j
@RestController
@RequestMapping("/flights")
@RequiredArgsConstructor
public class FlightController {

    private final FlightService flightService;
    private final FlightPriceService flightPriceService;
    private final FlightCreationService flightCreationService;

    @GetMapping("/search")
    public FlightSearchResponse search(@RequestParam String from, @RequestParam String to) {
        log.info("GET /flights/search?from={}&to={}", from, to);
        return flightService.searchResponse(from, to);
    }

    /**
     * Adds a new flight, invalidating any cached search result for its sector and
     * making sure the bloom filter that guards search knows the sector is real.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FlightCreateResponse createFlight(@Valid @RequestBody CreateFlightRequest request) {
        log.info("POST /flights body={}", request);
        return flightCreationService.addFlight(request.getFlightNumber(), request.getSource(),
                request.getDestination(), request.getAirline(), request.getPrice());
    }

    /** Re-prices one flight by id and drops the cached result for the sector it flies. */
    @PutMapping("/{id}/price")
    public PriceUpdateResponse updateFlightPrice(@PathVariable Long id,
                                                 @RequestParam @Positive BigDecimal price) {
        log.info("PUT /flights/{}/price?price={}", id, price);
        return flightPriceService.updateFlightPrice(id, price);
    }
}
