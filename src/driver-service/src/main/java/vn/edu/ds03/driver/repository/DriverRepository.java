package vn.edu.ds03.driver.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.ds03.driver.model.Driver;
import vn.edu.ds03.driver.model.VehicleType;

public interface DriverRepository extends JpaRepository<Driver, String> {
    boolean existsByUserId(String userId);

    @EntityGraph(attributePaths = "vehicle")
    Optional<Driver> findWithVehicleByUserId(String userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "vehicle")
    @Query("SELECT d FROM Driver d WHERE d.userId = :userId")
    Optional<Driver> findWithVehicleForUpdateByUserId(@Param("userId") String userId);

    @EntityGraph(attributePaths = "vehicle")
    Optional<Driver> findWithVehicleById(String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Driver d WHERE d.currentTripId = :tripId")
    Optional<Driver> findForUpdateByCurrentTripId(@Param("tripId") String tripId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Driver d
            SET d.status = vn.edu.ds03.driver.model.DriverStatus.BUSY
            WHERE d.currentTripId = :tripId
              AND d.status = vn.edu.ds03.driver.model.DriverStatus.RESERVED
            """)
    int startReservedTripByTripId(@Param("tripId") String tripId);

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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Driver d
            SET d.status = vn.edu.ds03.driver.model.DriverStatus.RESERVED,
                d.currentTripId = :tripId
            WHERE d.id = :driverId
              AND d.status = vn.edu.ds03.driver.model.DriverStatus.AVAILABLE
              AND d.acceptingTrips = true
              AND d.currentTripId IS NULL
              AND d.latitude IS NOT NULL
              AND d.longitude IS NOT NULL
            """)
    int reserveAvailableDriver(@Param("driverId") String driverId, @Param("tripId") String tripId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Driver d
            SET d.status = vn.edu.ds03.driver.model.DriverStatus.BUSY
            WHERE d.id = :driverId
              AND d.status = vn.edu.ds03.driver.model.DriverStatus.RESERVED
              AND d.currentTripId = :tripId
            """)
    int startReservedTrip(@Param("driverId") String driverId, @Param("tripId") String tripId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Driver d
            SET d.status = CASE
                    WHEN d.acceptingTrips = true AND d.latitude IS NOT NULL AND d.longitude IS NOT NULL
                    THEN vn.edu.ds03.driver.model.DriverStatus.AVAILABLE
                    ELSE vn.edu.ds03.driver.model.DriverStatus.OFFLINE
                END,
                d.currentTripId = null
            WHERE d.id = :driverId
              AND d.currentTripId = :tripId
              AND d.status IN (
                  vn.edu.ds03.driver.model.DriverStatus.RESERVED,
                  vn.edu.ds03.driver.model.DriverStatus.BUSY)
            """)
    int releaseDriverFromTrip(@Param("driverId") String driverId, @Param("tripId") String tripId);
}
