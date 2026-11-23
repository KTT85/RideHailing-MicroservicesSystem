package vn.edu.ds03.driver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.ds03.driver.model.ProcessedEvent;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
    boolean existsByConsumerNameAndEventId(String consumerName, String eventId);
}
