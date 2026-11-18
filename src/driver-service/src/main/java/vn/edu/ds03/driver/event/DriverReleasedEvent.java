package vn.edu.ds03.driver.event;

import java.time.Instant;
import vn.edu.ds03.driver.model.DriverStatus;

public record DriverReleasedEvent(
        String eventId,
        String eventType,
        String correlationId,
        Instant timestamp,
        Payload payload) {

    public static final String EVENT_TYPE = "DriverReleased";

    public record Payload(
            String tripId,
            String driverId,
            boolean released,
            DriverStatus driverStatus,
            Instant releasedAt) {
    }
}
