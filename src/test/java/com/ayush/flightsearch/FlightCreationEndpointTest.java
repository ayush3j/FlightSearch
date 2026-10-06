package com.ayush.flightsearch;

import com.ayush.flightsearch.dto.FlightCreateResponse;
import com.ayush.flightsearch.dto.FlightSearchResponse;
import com.ayush.flightsearch.repository.FlightRepository;
import com.ayush.flightsearch.service.FlightBloomFilter;
import com.ayush.flightsearch.service.FlightCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for POST /flights, against the real Postgres and Redis the app
 * uses (no auto-reseeding anymore - see application.yaml - so anything this test
 * inserts is cleaned up explicitly in tearDown rather than relying on a restart to
 * wipe it).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FlightCreationEndpointTest {

    @LocalServerPort
    private int port;

    @Autowired
    private FlightBloomFilter flightBloomFilter;

    @Autowired
    private FlightCacheService flightCacheService;

    @Autowired
    private FlightRepository flightRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private HttpClient httpClient;
    private final List<Long> createdFlightIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        createdFlightIds.forEach(flightRepository::deleteById);
        createdFlightIds.clear();
        flightCacheService.evict("ZZZ", "YYY");
        flightCacheService.evict("DEL", "BOM");
    }

    @Test
    @DisplayName("a brand-new sector is unsearchable until POST /flights adds it, then immediately searchable")
    void newSector_isRejectedByBloomFilterUntilAFlightIsAdded_thenBecomesSearchable() throws Exception {
        String source = "ZZZ";
        String destination = "YYY";

        assertThat(flightBloomFilter.mightContain(flightBloomFilter.buildKey(source, destination)))
                .as("a sector never inserted must not already be known to the filter")
                .isFalse();

        HttpResponse<String> beforeSearch = search(source, destination);
        assertThat(beforeSearch.statusCode()).isEqualTo(200);
        FlightSearchResponse before = objectMapper.readValue(beforeSearch.body(), FlightSearchResponse.class);
        assertThat(before.getFlights())
                .as("a sector the bloom filter has never seen must short-circuit to an empty result")
                .isEmpty();

        HttpResponse<String> createHttpResponse = createFlight("ZZ-1", source, destination, "Zed Airlines", "1234.00");
        assertThat(createHttpResponse.statusCode()).isEqualTo(201);
        FlightCreateResponse createResponse =
                objectMapper.readValue(createHttpResponse.body(), FlightCreateResponse.class);
        createdFlightIds.add(createResponse.getId());
        assertThat(createResponse.isCacheInvalidated())
                .as("nothing was ever cached for this brand-new sector")
                .isFalse();

        assertThat(flightBloomFilter.mightContain(flightBloomFilter.buildKey(source, destination)))
                .as("the bloom filter must know about this sector immediately after the insert")
                .isTrue();

        HttpResponse<String> afterSearch = search(source, destination);
        assertThat(afterSearch.statusCode()).isEqualTo(200);
        FlightSearchResponse after = objectMapper.readValue(afterSearch.body(), FlightSearchResponse.class);
        assertThat(after.getFlights())
                .as("the sector must now be searchable and return the flight just added")
                .extracting("flightNumber")
                .containsExactly("ZZ-1");
    }

    @Test
    @DisplayName("adding a flight to an already-cached sector invalidates that cached search result")
    void existingCachedSector_isInvalidatedWhenANewFlightIsAdded() throws Exception {
        String source = "DEL";
        String destination = "BOM";

        HttpResponse<String> warmup = search(source, destination);
        assertThat(warmup.statusCode()).isEqualTo(200);
        String cacheKey = flightCacheService.buildCacheKey(source, destination);
        assertThat(flightCacheService.get(cacheKey))
                .as("the search above must have populated the cache")
                .isPresent();

        HttpResponse<String> createHttpResponse = createFlight("AI-9999", source, destination, "Air India", "1.00");
        assertThat(createHttpResponse.statusCode()).isEqualTo(201);
        FlightCreateResponse createResponse =
                objectMapper.readValue(createHttpResponse.body(), FlightCreateResponse.class);
        createdFlightIds.add(createResponse.getId());

        assertThat(createResponse.isCacheInvalidated())
                .as("a search result already existed for this sector, so it must be invalidated")
                .isTrue();
    }

    private HttpResponse<String> search(String from, String to) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/flights/search?from=%s&to=%s".formatted(port, from, to)))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> createFlight(String flightNumber, String source, String destination,
                                               String airline, String price) throws Exception {
        String body = """
                {"flightNumber":"%s","source":"%s","destination":"%s","airline":"%s","price":%s}""".formatted(
                flightNumber, source, destination, airline, price);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/flights".formatted(port)))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
