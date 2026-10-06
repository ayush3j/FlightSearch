package com.ayush.flightsearch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

@Entity
@Table(name = "flight")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class Flight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "flight_number", nullable = false, length = 10)
    private String flightNumber;

    @Column(name = "source", nullable = false, length = 3)
    private String source;

    @Column(name = "destination", nullable = false, length = 3)
    private String destination;

    @Column(name = "airline", nullable = false, length = 50)
    private String airline;

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;
}
