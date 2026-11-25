package vn.edu.ds03.trip;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "trips")
class Ride {
    @Id public String id;
    public String passengerId, driverId, driverUserId, status, vehicleType, paymentMethod;
    public String paymentId, paymentState, correlationId, cancelReason, demoFault;
    @JsonIgnore public String idempotencyKey;
    public Double fare;
    public boolean driverReleased;
    public Instant requestedAt, acceptedAt, startedAt, completedAt, cancelledAt, demoFaultAt;
    @JdbcTypeCode(SqlTypes.JSON) public Location pickup;
    @JdbcTypeCode(SqlTypes.JSON) public Location dropoff;

    record Location(double lat, double lng, String address) {}
}
