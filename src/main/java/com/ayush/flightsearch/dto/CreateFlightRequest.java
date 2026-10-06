package com.ayush.flightsearch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

@Getter
@Setter
@ToString
public class CreateFlightRequest {

    @NotBlank
    private String flightNumber;

    /** Matches the flight table's source VARCHAR(3) column - a 3-letter IATA code. */
    @NotBlank
    @Size(min = 3, max = 3, message = "source must be a 3-letter airport code")
    private String source;

    /** Matches the flight table's destination VARCHAR(3) column. */
    @NotBlank
    @Size(min = 3, max = 3, message = "destination must be a 3-letter airport code")
    private String destination;

    @NotBlank
    private String airline;

    @Positive
    private BigDecimal price;
}
