package vn.edu.ds03.payment.repository;

import vn.edu.ds03.payment.model.PaymentOperation;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface OperationRepository extends JpaRepository<PaymentOperation, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PaymentOperation o where o.id = :id")
    Optional<PaymentOperation> lockById(@Param("id") String id);

    java.util.List<PaymentOperation> findAllByPaymentId(String paymentId);
    Optional<PaymentOperation> findByPaymentIdAndOperationType(String paymentId, String operationType);
}
