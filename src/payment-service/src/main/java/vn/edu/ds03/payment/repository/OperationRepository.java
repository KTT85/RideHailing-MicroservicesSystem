package vn.edu.ds03.payment.repository;

import vn.edu.ds03.payment.model.PaymentOperation;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationRepository extends JpaRepository<PaymentOperation, String> {
    java.util.List<PaymentOperation> findAllByPaymentId(String paymentId);
    Optional<PaymentOperation> findByPaymentIdAndOperationType(String paymentId, String operationType);
}
