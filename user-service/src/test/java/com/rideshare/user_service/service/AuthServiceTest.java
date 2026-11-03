package com.rideshare.user_service.service;

import com.rideshare.user_service.dto.request.LoginRequest;
import com.rideshare.user_service.dto.request.RegisterRequest;
import com.rideshare.user_service.dto.response.AuthResponse;
import com.rideshare.user_service.entity.DriverProfile;
import com.rideshare.user_service.entity.Role;
import com.rideshare.user_service.entity.User;
import com.rideshare.user_service.entity.UserStatus;
import com.rideshare.user_service.exception.BadRequestException;
import com.rideshare.user_service.exception.DuplicateResourceException;
import com.rideshare.user_service.exception.InvalidCredentialsException;
import com.rideshare.user_service.repository.DriverProfileRepository;
import com.rideshare.user_service.repository.UserRepository;
import com.rideshare.user_service.security.JwtProperties;
import com.rideshare.user_service.security.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private DriverProfileRepository driverProfileRepository;

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private JwtProperties jwtProperties;

    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(12);
        authService = new AuthServiceImpl(
                userRepository,
                driverProfileRepository,
                passwordEncoder,
                jwtUtils,
                jwtProperties
        );
    }

    @Test
    @DisplayName("Should successfully register passenger with secure BCrypt password hashing")
    void shouldRegisterPassengerSuccessfully() {
        RegisterRequest request = RegisterRequest.builder()
                .email("passenger@example.com")
                .password("PlaintextPassword123!")
                .firstName("Alice")
                .lastName("Smith")
                .phoneNumber("+919876543210")
                .role("PASSENGER")
                .build();

        when(userRepository.existsByEmail("passenger@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(1L);
            return user;
        });
        when(jwtProperties.getExpirationMs()).thenReturn(86400000L);
        when(jwtUtils.generateToken(any(), anyString(), anyString())).thenReturn("mock.jwt.token");

        AuthResponse response = authService.register(request);

        assertThat(response).isNotNull();
        assertThat(response.getAccessToken()).isEqualTo("mock.jwt.token");
        assertThat(response.getEmail()).isEqualTo("passenger@example.com");
        assertThat(response.getRole()).isEqualTo("ROLE_PASSENGER");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User savedUser = userCaptor.getValue();

        // Security check: Stored password must NOT equal plaintext
        assertThat(savedUser.getPasswordHash()).isNotEqualTo("PlaintextPassword123!");
        // BCrypt verification: Password encoder matches plaintext against hash
        assertThat(passwordEncoder.matches("PlaintextPassword123!", savedUser.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("Should successfully register driver with vehicle details")
    void shouldRegisterDriverSuccessfully() {
        RegisterRequest request = RegisterRequest.builder()
                .email("driver@example.com")
                .password("PlaintextPassword123!")
                .firstName("Bob")
                .lastName("Jones")
                .phoneNumber("+919876543211")
                .role("DRIVER")
                .vehicleModel("Toyota Camry")
                .licensePlate("KA-01-AB-1234")
                .build();

        when(userRepository.existsByEmail("driver@example.com")).thenReturn(false);
        when(driverProfileRepository.existsByLicensePlate("KA-01-AB-1234")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(2L);
            return user;
        });
        when(jwtProperties.getExpirationMs()).thenReturn(86400000L);
        when(jwtUtils.generateToken(any(), anyString(), anyString())).thenReturn("mock.jwt.token");

        AuthResponse response = authService.register(request);

        assertThat(response).isNotNull();
        assertThat(response.getRole()).isEqualTo("ROLE_DRIVER");

        verify(driverProfileRepository).save(any(DriverProfile.class));
    }

    @Test
    @DisplayName("Should reject driver registration if vehicle details are missing")
    void shouldRejectDriverWithoutVehicleDetails() {
        RegisterRequest request = RegisterRequest.builder()
                .email("driver@example.com")
                .password("PlaintextPassword123!")
                .firstName("Bob")
                .lastName("Jones")
                .phoneNumber("+919876543211")
                .role("DRIVER")
                .build();

        when(userRepository.existsByEmail("driver@example.com")).thenReturn(false);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Driver registration requires both 'vehicleModel' and 'licensePlate'");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should reject registration with duplicate email")
    void shouldRejectDuplicateEmailRegistration() {
        RegisterRequest request = RegisterRequest.builder()
                .email("existing@example.com")
                .password("PlaintextPassword123!")
                .firstName("Alice")
                .lastName("Smith")
                .phoneNumber("+919876543210")
                .role("PASSENGER")
                .build();

        when(userRepository.existsByEmail("existing@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("already exists");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should successfully login with valid credentials")
    void shouldLoginSuccessfully() {
        LoginRequest request = LoginRequest.builder()
                .email("user@example.com")
                .password("ValidPassword123!")
                .build();

        User user = User.builder()
                .id(1L)
                .email("user@example.com")
                .passwordHash(passwordEncoder.encode("ValidPassword123!"))
                .role(Role.ROLE_PASSENGER)
                .status(UserStatus.ACTIVE)
                .build();

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(jwtProperties.getExpirationMs()).thenReturn(86400000L);
        when(jwtUtils.generateToken(1L, "user@example.com", "ROLE_PASSENGER")).thenReturn("valid.jwt.token");

        AuthResponse response = authService.login(request);

        assertThat(response).isNotNull();
        assertThat(response.getAccessToken()).isEqualTo("valid.jwt.token");
        assertThat(response.getUserId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should reject login with incorrect password")
    void shouldRejectInvalidPasswordLogin() {
        LoginRequest request = LoginRequest.builder()
                .email("user@example.com")
                .password("WrongPassword123!")
                .build();

        User user = User.builder()
                .id(1L)
                .email("user@example.com")
                .passwordHash(passwordEncoder.encode("ValidPassword123!"))
                .role(Role.ROLE_PASSENGER)
                .status(UserStatus.ACTIVE)
                .build();

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Invalid email or password");
    }

    @Test
    @DisplayName("Should reject login for non-existent email")
    void shouldRejectUnknownEmailLogin() {
        LoginRequest request = LoginRequest.builder()
                .email("unknown@example.com")
                .password("SomePassword123!")
                .build();

        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Invalid email or password");
    }
}
