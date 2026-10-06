package com.ayush.flightsearch.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
public class FlightSearchResponse {
    private String from;
    private String to;
    private List<FlightResponse> flights;
}
