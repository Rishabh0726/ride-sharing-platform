package com.rideshare.user_service.service;

import com.rideshare.user_service.dto.request.UpdateProfileRequest;
import com.rideshare.user_service.dto.response.UserProfileResponse;
import com.rideshare.user_service.entity.DriverProfile;
import com.rideshare.user_service.entity.DriverStatus;
import com.rideshare.user_service.entity.Role;
import com.rideshare.user_service.entity.User;
import com.rideshare.user_service.entity.UserStatus;
import com.rideshare.user_service.exception.ResourceNotFoundException;
import com.rideshare.user_service.repository.DriverProfileRepository;
import com.rideshare.user_service.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private DriverProfileRepository driverProfileRepository;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(userRepository, driverProfileRepository);
    }

    @Test
    @DisplayName("Should successfully retrieve passenger profile")
    void shouldRetrievePassengerProfile() {
        User user = User.builder()
                .id(1L)
                .email("passenger@example.com")
                .firstName("Alice")
                .lastName("Smith")
                .phoneNumber("+919876543210")
                .role(Role.ROLE_PASSENGER)
                .status(UserStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(driverProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());

        UserProfileResponse response = userService.getCurrentUserProfile(1L);

        assertThat(response).isNotNull();
        assertThat(response.getUserId()).isEqualTo(1L);
        assertThat(response.getEmail()).isEqualTo("passenger@example.com");
        assertThat(response.getRole()).isEqualTo("ROLE_PASSENGER");
        assertThat(response.getDriverProfile()).isNull();
    }

    @Test
    @DisplayName("Should successfully retrieve driver profile with vehicle details")
    void shouldRetrieveDriverProfileWithDetails() {
        User user = User.builder()
                .id(2L)
                .email("driver@example.com")
                .firstName("Bob")
                .lastName("Jones")
                .phoneNumber("+919876543211")
                .role(Role.ROLE_DRIVER)
                .status(UserStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();

        DriverProfile profile = DriverProfile.builder()
                .id(10L)
                .user(user)
                .vehicleModel("Toyota Camry")
                .licensePlate("KA-01-AB-1234")
                .status(DriverStatus.OFFLINE)
                .ratingAvg(new BigDecimal("4.85"))
                .build();

        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(driverProfileRepository.findByUserId(2L)).thenReturn(Optional.of(profile));

        UserProfileResponse response = userService.getCurrentUserProfile(2L);

        assertThat(response).isNotNull();
        assertThat(response.getDriverProfile()).isNotNull();
        assertThat(response.getDriverProfile().getVehicleModel()).isEqualTo("Toyota Camry");
        assertThat(response.getDriverProfile().getLicensePlate()).isEqualTo("KA-01-AB-1234");
    }

    @Test
    @DisplayName("Should update user profile allowed fields without altering credentials or role")
    void shouldUpdateUserProfileFields() {
        User user = User.builder()
                .id(1L)
                .email("passenger@example.com")
                .passwordHash("OriginalPasswordHash")
                .firstName("Alice")
                .lastName("Smith")
                .phoneNumber("+919876543210")
                .role(Role.ROLE_PASSENGER)
                .status(UserStatus.ACTIVE)
                .build();

        UpdateProfileRequest request = UpdateProfileRequest.builder()
                .firstName("Alicia")
                .lastName("Williams")
                .phoneNumber("+919876543299")
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserProfileResponse response = userService.updateCurrentUserProfile(1L, request);

        assertThat(response.getFirstName()).isEqualTo("Alicia");
        assertThat(response.getLastName()).isEqualTo("Williams");
        assertThat(response.getPhoneNumber()).isEqualTo("+919876543299");
        assertThat(user.getPasswordHash()).isEqualTo("OriginalPasswordHash"); // Untouched!
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException for unknown user")
    void shouldThrowWhenUserNotFound() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getCurrentUserProfile(999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User not found with id: 999");
    }
}
