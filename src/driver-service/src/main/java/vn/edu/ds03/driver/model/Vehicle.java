package vn.edu.ds03.driver.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(
        name = "vehicles",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_vehicles_driver_id", columnNames = "driver_id"),
                @UniqueConstraint(name = "uq_vehicles_license_plate", columnNames = "license_plate")
        })
public class Vehicle {
    @Id
    @Column(name = "id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "driver_id",
            nullable = false,
            unique = true,
            foreignKey = @ForeignKey(name = "fk_vehicles_driver"))
    private Driver driver;

    @Column(name = "license_plate", nullable = false, length = 20)
    private String licensePlate;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 16)
    private VehicleType vehicleType;

    protected Vehicle() {
    }

    public Vehicle(String licensePlate, VehicleType vehicleType) {
        this.licensePlate = licensePlate == null ? null : licensePlate.strip().toUpperCase(Locale.ROOT);
        this.vehicleType = vehicleType;
    }

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
        }
    }

    void attachTo(Driver driver) {
        this.driver = driver;
    }

    public String getId() {
        return id;
    }

    public String getLicensePlate() {
        return licensePlate;
    }

    public VehicleType getVehicleType() {
        return vehicleType;
    }
}
