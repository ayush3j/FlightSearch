package com.ayush.flightsearch.mapper;

import com.ayush.flightsearch.dto.FlightResponse;
import com.ayush.flightsearch.entity.Flight;
import org.mapstruct.Mapper;

import java.util.List;

/**
 * MapStruct generates the implementation at build time (target/generated-sources).
 * componentModel = "spring" makes it an injectable @Component.
 */
@Mapper(componentModel = "spring")
public interface FlightMapper {

    /** id / source / destination are not part of FlightResponse, so they are simply dropped. */
    FlightResponse toResponse(Flight flight);

    List<FlightResponse> toResponseList(List<Flight> flights);
}
