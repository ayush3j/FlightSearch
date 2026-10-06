package com.ayush.flightsearch.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@AllArgsConstructor
public class FlightCreateResponse {
    private Long id;
    private String flightNumber;
    private String source;
    private String destination;
    private String airline;
    private BigDecimal price;
    /** The sector's search-cache key, so you can see which entry was checked. */
    private String cacheKey;
    /** True if a cached search result existed for this sector and was invalidated. */
    private boolean cacheInvalidated;
}
