package com.ayush.flightsearch.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Maps straight to a 404 instead of a 500 stack trace. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class FlightNotFoundException extends RuntimeException {

    public FlightNotFoundException(Long id) {
        super("No flight found with id: " + id);
    }
}
