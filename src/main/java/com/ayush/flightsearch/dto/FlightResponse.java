package com.ayush.flightsearch.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class FlightResponse {
    private String flightNumber;
    private String airline;
    private double price;
}
