package vn.edu.ds03.driver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.ds03.driver.model.Driver;

public interface DriverRepository extends JpaRepository<Driver, String> {
    boolean existsByUserId(String userId);
}
