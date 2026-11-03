package com.rideshare.user_service.repository;

import com.rideshare.user_service.entity.DriverProfile;
import com.rideshare.user_service.entity.DriverStatus;
import com.rideshare.user_service.entity.Role;
import com.rideshare.user_service.entity.User;
import com.rideshare.user_service.entity.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DriverProfileRepository driverProfileRepository;

    @Test
    @DisplayName("Save and find user by email successfully")
    void shouldSaveAndFindUserByEmail() {
        User user = User.builder()
                .email("test.rider@example.com")
                .passwordHash("$2a$12$sampleHashedPassword")
                .firstName("John")
                .lastName("Doe")
                .phoneNumber("+919876543210")
                .role(Role.ROLE_PASSENGER)
                .status(UserStatus.ACTIVE)
                .build();

        User savedUser = userRepository.save(user);

        assertThat(savedUser.getId()).isNotNull();
        assertThat(savedUser.getCreatedAt()).isNotNull();
        assertThat(savedUser.getUpdatedAt()).isNotNull();

        Optional<User> found = userRepository.findByEmail("test.rider@example.com");
        assertThat(found).isPresent();
        assertThat(found.get().getFirstName()).isEqualTo("John");
        assertThat(found.get().getRole()).isEqualTo(Role.ROLE_PASSENGER);
    }

    @Test
    @DisplayName("Verify existsByEmail returns true for existing and false for non-existing")
    void shouldCheckEmailExistence() {
        User user = User.builder()
                .email("exists@example.com")
                .passwordHash("$2a$12$sampleHashedPassword")
                .firstName("Jane")
                .lastName("Doe")
                .phoneNumber("+919876543211")
                .role(Role.ROLE_PASSENGER)
                .status(UserStatus.ACTIVE)
                .build();

        userRepository.save(user);

        assertThat(userRepository.existsByEmail("exists@example.com")).isTrue();
        assertThat(userRepository.existsByEmail("unknown@example.com")).isFalse();
    }

    @Test
    @DisplayName("Unique email constraint throws DataIntegrityViolationException")
    void shouldThrowExceptionOnDuplicateEmail() {
        User user1 = User.builder()
                .email("duplicate@example.com")
                .passwordHash("$2a$12$sampleHashedPassword")
                .firstName("User1")
                .lastName("Test")
                .phoneNumber("+919876543212")
                .role(Role.ROLE_PASSENGER)
                .status(UserStatus.ACTIVE)
                .build();

        userRepository.saveAndFlush(user1);

        User user2 = User.builder()
                .email("duplicate@example.com")
                .passwordHash("$2a$12$anotherHashedPassword")
                .firstName("User2")
                .lastName("Test")
                .phoneNumber("+919876543213")
                .role(Role.ROLE_PASSENGER)
                .status(UserStatus.ACTIVE)
                .build();

        assertThatThrownBy(() -> userRepository.saveAndFlush(user2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Save user with driver profile and verify relations")
    void shouldPersistUserWithDriverProfile() {
        User driverUser = User.builder()
                .email("driver@example.com")
                .passwordHash("$2a$12$sampleHashedPassword")
                .firstName("David")
                .lastName("Driver")
                .phoneNumber("+919876543214")
                .role(Role.ROLE_DRIVER)
                .status(UserStatus.ACTIVE)
                .build();

        User savedUser = userRepository.save(driverUser);

        DriverProfile profile = DriverProfile.builder()
                .user(savedUser)
                .vehicleModel("Toyota Prius")
                .licensePlate("DL-01-AB-1234")
                .status(DriverStatus.OFFLINE)
                .build();

        driverProfileRepository.save(profile);

        Optional<DriverProfile> foundProfile = driverProfileRepository.findByUserId(savedUser.getId());
        assertThat(foundProfile).isPresent();
        assertThat(foundProfile.get().getVehicleModel()).isEqualTo("Toyota Prius");
        assertThat(foundProfile.get().getLicensePlate()).isEqualTo("DL-01-AB-1234");
        assertThat(foundProfile.get().getStatus()).isEqualTo(DriverStatus.OFFLINE);
        assertThat(foundProfile.get().getVersion()).isEqualTo(0L);
    }
}
