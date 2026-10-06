package com.ayush.flightsearch.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@AllArgsConstructor
public class PriceUpdateResponse {
    private Long id;
    private String flightNumber;
    private String source;
    private String destination;
    private BigDecimal oldPrice;
    private BigDecimal newPrice;
    /** Which cache key was dropped, so you can see the invalidation actually happened. */
    private String evictedCacheKey;
}
