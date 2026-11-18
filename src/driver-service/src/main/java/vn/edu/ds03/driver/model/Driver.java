package vn.edu.ds03.driver.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "drivers",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_drivers_user_id", columnNames = "user_id"),
                @UniqueConstraint(name = "uq_drivers_current_trip_id", columnNames = "current_trip_id")
        },
        indexes = @Index(name = "idx_drivers_status_accepting", columnList = "status, accepting_trips"))
public class Driver {

    private static final BigDecimal MIN_LATITUDE = new BigDecimal("-90");
    private static final BigDecimal MAX_LATITUDE = new BigDecimal("90");
    private static final BigDecimal MIN_LONGITUDE = new BigDecimal("-180");
    private static final BigDecimal MAX_LONGITUDE = new BigDecimal("180");
    private static final int COORDINATE_SCALE = 7;
    private static final int MAX_INPUT_SCALE = 20;

    @Id
    @Column(name = "id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String id;

    @Column(name = "user_id", nullable = false, unique = true, length = 36, columnDefinition = "CHAR(36)")
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DriverStatus status = DriverStatus.OFFLINE;

    @Column(name = "accepting_trips", nullable = false)
    private boolean acceptingTrips;

    @Column(name = "current_trip_id", unique = true, length = 36, columnDefinition = "CHAR(36)")
    private String currentTripId;

    @Column(name = "latitude", precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 10, scale = 7)
    private BigDecimal longitude;

    @OneToOne(
            mappedBy = "driver",
            fetch = FetchType.LAZY,
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            optional = false)
    private Vehicle vehicle;

    protected Driver() {
    }

    public Driver(String userId) {
        this.userId = userId == null ? null : userId.toLowerCase(Locale.ROOT);
        this.status = DriverStatus.OFFLINE;
        this.acceptingTrips = false;
    }

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
        }
    }

    public void attachVehicle(Vehicle vehicle) {
        Objects.requireNonNull(vehicle, "vehicle must not be null");
        if (this.vehicle != null && this.vehicle != vehicle) {
            this.vehicle.detach();
        }
        this.vehicle = vehicle;
        vehicle.attachTo(this);
    }

    public void updateLocation(BigDecimal latitude, BigDecimal longitude) {
        if (latitude == null || longitude == null) {
            throw new IllegalArgumentException("Latitude and longitude are required");
        }
        if (Math.abs((long) latitude.scale()) > MAX_INPUT_SCALE
                || Math.abs((long) longitude.scale()) > MAX_INPUT_SCALE) {
            throw new IllegalArgumentException("Coordinate scale exceeds supported precision");
        }
        if (latitude.compareTo(MIN_LATITUDE) < 0 || latitude.compareTo(MAX_LATITUDE) > 0) {
            throw new IllegalArgumentException("Latitude must be between -90 and 90");
        }
        if (longitude.compareTo(MIN_LONGITUDE) < 0 || longitude.compareTo(MAX_LONGITUDE) > 0) {
            throw new IllegalArgumentException("Longitude must be between -180 and 180");
        }
        this.latitude = latitude.setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
        this.longitude = longitude.setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public DriverStatus getStatus() {
        return status;
    }

    public boolean isAcceptingTrips() {
        return acceptingTrips;
    }

    public String getCurrentTripId() {
        return currentTripId;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public Vehicle getVehicle() {
        return vehicle;
    }
}
