package vn.edu.ds03.matching.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.edu.ds03.matching.config.MatchingProperties;
import vn.edu.ds03.matching.dto.DriverCandidateDto;
import vn.edu.ds03.matching.dto.ReserveDriverRequest;
import vn.edu.ds03.matching.dto.ReservedDriverDto;
import vn.edu.ds03.matching.event.TripRequestedEvent;

@Service
public class DriverServiceClient {

    public static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
    private static final int MIN_TOKEN_LENGTH = 32;
    private static final ParameterizedTypeReference<List<DriverCandidateDto>> CANDIDATE_LIST_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final MatchingProperties properties;

    public DriverServiceClient(RestClient restClient, MatchingProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public List<DriverCandidateDto> fetchCandidates(
            BigDecimal pickupLatitude,
            BigDecimal pickupLongitude,
            String vehicleType,
            int limit) {
        String token = requireInternalToken();
        try {
            List<DriverCandidateDto> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/internal/drivers/candidates")
                            .queryParam("pickupLatitude", pickupLatitude.toPlainString())
                            .queryParam("pickupLongitude", pickupLongitude.toPlainString())
                            .queryParam("vehicleType", vehicleType)
                            .queryParam("limit", Math.min(Math.max(limit, 1), 20))
                            .build())
                    .header(SERVICE_TOKEN_HEADER, token)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(CANDIDATE_LIST_TYPE);
            return response != null ? List.copyOf(response) : List.of();
        } catch (RestClientException ex) {
            throw new IllegalStateException("Failed to fetch driver candidates from driver-service", ex);
        }
    }

    public Optional<ReservedDriverDto> tryReserveDriver(String driverId, String tripId) {
        TripRequestedEvent.requireCanonicalUuid(driverId, "driverId");
        TripRequestedEvent.requireCanonicalUuid(tripId, "tripId");
        String token = requireInternalToken();
        try {
            ReservedDriverDto reserved = restClient.post()
                    .uri("/internal/drivers/{driverId}/reserve", driverId)
                    .header(SERVICE_TOKEN_HEADER, token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(new ReserveDriverRequest(tripId))
                    .retrieve()
                    .body(ReservedDriverDto.class);
            return Optional.ofNullable(reserved);
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.CONFLICT
                    || ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw new IllegalStateException("Driver reservation rejected with unexpected status", ex);
        } catch (RestClientException ex) {
            throw new IllegalStateException("Failed to call driver-service reserve endpoint", ex);
        }
    }

    private String requireInternalToken() {
        String token = properties.driverInternalToken();
        if (token == null || token.isBlank() || token.length() < MIN_TOKEN_LENGTH) {
            throw new IllegalStateException(
                    "DRIVER_INTERNAL_TOKEN must be configured with at least 32 characters");
        }
        return token;
    }
}
