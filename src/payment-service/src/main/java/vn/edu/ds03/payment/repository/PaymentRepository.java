package vn.edu.ds03.payment.repository;

import vn.edu.ds03.payment.model.Payment;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, String> {
    Optional<Payment> findByTripId(String tripId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.tripId = :tripId")
    Optional<Payment> lockByTripId(@Param("tripId") String tripId);
}
