package vn.edu.ds03.driver.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.ds03.driver.model.Driver;
import vn.edu.ds03.driver.model.VehicleType;

public interface DriverRepository extends JpaRepository<Driver, String> {
    boolean existsByUserId(String userId);

    @EntityGraph(attributePaths = "vehicle")
    Optional<Driver> findWithVehicleByUserId(String userId);

    @Query("""
            SELECT d FROM Driver d
            JOIN FETCH d.vehicle v
            WHERE d.status = vn.edu.ds03.driver.model.DriverStatus.AVAILABLE
              AND d.acceptingTrips = true
              AND d.currentTripId IS NULL
              AND d.latitude IS NOT NULL
              AND d.longitude IS NOT NULL
              AND v.vehicleType = :vehicleType
            """)
    List<Driver> findAvailableCandidatesByVehicleType(@Param("vehicleType") VehicleType vehicleType);
}
