package vn.edu.ds03.driver.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.ds03.driver.DriverServiceApplication;
import vn.edu.ds03.driver.dto.CreateDriverProfileRequest;
import vn.edu.ds03.driver.dto.DriverProfileResponse;
import vn.edu.ds03.driver.dto.UpdateDriverLocationRequest;
import vn.edu.ds03.driver.model.Driver;
import vn.edu.ds03.driver.model.DriverStatus;
import vn.edu.ds03.driver.model.Vehicle;
import vn.edu.ds03.driver.model.VehicleType;
import vn.edu.ds03.driver.service.DriverProfileService;

@SpringBootTest(
        classes = DriverServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
                "spring.datasource.url=jdbc:h2:mem:driver_db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop"
        })
class DriverRepositoryPersistenceTest {

    private static final byte[] TEST_KEY_BYTES =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> Base64.getEncoder().encodeToString(TEST_KEY_BYTES));
        registry.add("app.jwt.issuer", () -> "ds03-user-service");
        registry.add("MYSQL_PASSWORD", () -> "");
        registry.add("RABBITMQ_USER", () -> "unused");
        registry.add("RABBITMQ_PASSWORD", () -> "unused");
    }

    @Autowired
    private DriverRepository driverRepository;

    @Autowired
    private DriverProfileService driverProfileService;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void cleanDatabase() {
        driverRepository.deleteAll();
    }

    @Test
    @Transactional
    @DisplayName("saveAndFlush triggers PrePersist UUIDs, cascades Vehicle, and reloads JPA entities")
    void saveAndFlush_driverWithVehicle_persistsCascadesAndReloadsViaJpa() {
        Driver driver = new Driver("550E8400-E29B-41D4-A716-446655440000");
        driver.attachVehicle(new Vehicle(" 59a-888.99 ", VehicleType.BIKE));

        Driver saved = driverRepository.saveAndFlush(driver);
        String driverId = saved.getId();
        entityManager.clear();

        assertThat(driverRepository.existsByUserId("550e8400-e29b-41d4-a716-446655440000")).isTrue();
        assertThat(driverRepository.existsByUserId("00000000-0000-0000-0000-000000000000")).isFalse();

        Driver reloaded = driverRepository.findById(driverId).orElseThrow();
        assertThat(reloaded.getUserId()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(reloaded.getStatus()).isEqualTo(DriverStatus.OFFLINE);
        assertThat(reloaded.isAcceptingTrips()).isFalse();
        assertThat(reloaded.getVehicle().getId()).isNotBlank();
        assertThat(reloaded.getVehicle().getLicensePlate()).isEqualTo("59A-888.99");
        assertThat(reloaded.getVehicle().getVehicleType()).isEqualTo(VehicleType.BIKE);
    }

    @Test
    @DisplayName("Unique constraint on license_plate raises DataIntegrityViolationException and translates to 409 in service")
    void createProfile_duplicateLicensePlateAcrossDifferentUsers_translatesConstraintViolationTo409() {
        DriverProfileResponse created = driverProfileService.createProfile(
                "11111111-2222-3333-4444-555555555555",
                new CreateDriverProfileRequest("51f-123.45", VehicleType.CAR));
        assertThat(created.driverId()).isNotBlank();
        assertThat(created.vehicle().vehicleId()).isNotBlank();

        assertThatThrownBy(() -> driverProfileService.createProfile(
                "99999999-2222-3333-4444-555555555555",
                new CreateDriverProfileRequest(" 51F-123.45 ", VehicleType.BIKE)))
                .asInstanceOf(InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getReason()).isEqualTo("Driver profile or license plate already exists");
                });

        assertThat(driverRepository.existsByUserId("99999999-2222-3333-4444-555555555555")).isFalse();
    }

    @Test
    @DisplayName("Direct repository duplicate user_id insert violates uq_drivers_user_id constraint")
    void saveAndFlush_duplicateUserId_throwsDataIntegrityViolationException() {
        Driver first = new Driver("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        first.attachVehicle(new Vehicle("51A-000.01", VehicleType.BIKE));
        driverRepository.saveAndFlush(first);

        Driver duplicateUser = new Driver("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        duplicateUser.attachVehicle(new Vehicle("51A-000.02", VehicleType.CAR));

        assertThatThrownBy(() -> driverRepository.saveAndFlush(duplicateUser))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("findWithVehicleByUserId eagerly loads vehicle outside transaction and getCurrentProfile returns it")
    void findWithVehicleByUserId_outsideTransaction_loadsVehicleWithoutLazyInitError() {
        DriverProfileResponse created = driverProfileService.createProfile(
                "550E8400-E29B-41D4-A716-446655440000",
                new CreateDriverProfileRequest(" 59c-246.80 ", VehicleType.CAR));

        Driver fetched = driverRepository
                .findWithVehicleByUserId("550e8400-e29b-41d4-a716-446655440000")
                .orElseThrow();
        assertThat(fetched.getId()).isEqualTo(created.driverId());
        assertThat(fetched.getVehicle().getLicensePlate()).isEqualTo("59C-246.80");

        DriverProfileResponse readBack = driverProfileService.getCurrentProfile("550E8400-E29B-41D4-A716-446655440000");
        assertThat(readBack.driverId()).isEqualTo(created.driverId());
        assertThat(readBack.vehicle().licensePlate()).isEqualTo("59C-246.80");
        assertThat(driverRepository.findWithVehicleByUserId("00000000-0000-0000-0000-000000000000")).isEmpty();
    }

    @Test
    @DisplayName("updateLocation persists DECIMAL(10,7) coordinates and reloads them accurately")
    void updateLocation_existingDriver_persistsScaledCoordinatesInDatabase() {
        String userId = "77777777-2222-3333-4444-555555555555";
        driverProfileService.createProfile(userId, new CreateDriverProfileRequest("51k-777.88", VehicleType.BIKE));

        DriverProfileResponse updated = driverProfileService.updateLocation(
                userId,
                new UpdateDriverLocationRequest(
                        new BigDecimal("10.76262236"),
                        new BigDecimal("106.66017244")));
        entityManager.clear();

        DriverProfileResponse reloaded = driverProfileService.getCurrentProfile(userId);
        assertThat(updated.latitude()).isEqualByComparingTo("10.7626224");
        assertThat(reloaded.latitude()).isEqualByComparingTo("10.7626224");
        assertThat(reloaded.longitude()).isEqualByComparingTo("106.6601724");
        assertThat(reloaded.status()).isEqualTo(DriverStatus.OFFLINE);
    }
}
