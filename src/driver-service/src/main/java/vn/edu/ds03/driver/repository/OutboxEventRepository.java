package vn.edu.ds03.driver.repository;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.ds03.driver.model.OutboxEvent;
import vn.edu.ds03.driver.model.OutboxStatus;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    @Query("""
            SELECT e FROM OutboxEvent e
            WHERE e.status = vn.edu.ds03.driver.model.OutboxStatus.PENDING
              AND (e.nextRetryAt IS NULL OR e.nextRetryAt <= :now)
            ORDER BY e.createdAt ASC
            """)
    List<OutboxEvent> findDuePendingEvents(@Param("now") Instant now, Pageable pageable);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE OutboxEvent e
            SET e.status = vn.edu.ds03.driver.model.OutboxStatus.PUBLISHED,
                e.attemptCount = :attemptCount,
                e.nextRetryAt = null,
                e.lastError = null
            WHERE e.eventId = :eventId
              AND e.status = vn.edu.ds03.driver.model.OutboxStatus.PENDING
            """)
    int markPublished(@Param("eventId") String eventId, @Param("attemptCount") int attemptCount);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE OutboxEvent e
            SET e.status = :status,
                e.attemptCount = :attemptCount,
                e.nextRetryAt = :nextRetryAt,
                e.lastError = :lastError
            WHERE e.eventId = :eventId
              AND e.status = vn.edu.ds03.driver.model.OutboxStatus.PENDING
            """)
    int recordPublishFailure(
            @Param("eventId") String eventId,
            @Param("status") OutboxStatus status,
            @Param("attemptCount") int attemptCount,
            @Param("nextRetryAt") Instant nextRetryAt,
            @Param("lastError") String lastError);
}
