package com.rideshare.user_service.service;

import com.rideshare.user_service.dto.request.LoginRequest;
import com.rideshare.user_service.dto.request.RegisterRequest;
import com.rideshare.user_service.dto.response.AuthResponse;
import com.rideshare.user_service.entity.*;
import com.rideshare.user_service.exception.BadRequestException;
import com.rideshare.user_service.exception.DuplicateResourceException;
import com.rideshare.user_service.exception.InvalidCredentialsException;
import com.rideshare.user_service.repository.DriverProfileRepository;
import com.rideshare.user_service.repository.UserRepository;
import com.rideshare.user_service.security.JwtProperties;
import com.rideshare.user_service.security.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final JwtProperties jwtProperties;

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        log.info("Processing registration for email: {}", request.getEmail());

        if (userRepository.existsByEmail(request.getEmail().trim().toLowerCase())) {
            throw new DuplicateResourceException("An account with this email already exists: " + request.getEmail());
        }

        Role role = Role.valueOf("ROLE_" + request.getRole().toUpperCase());

        if (role == Role.ROLE_DRIVER) {
            if (!StringUtils.hasText(request.getVehicleModel()) || !StringUtils.hasText(request.getLicensePlate())) {
                throw new BadRequestException("Driver registration requires both 'vehicleModel' and 'licensePlate'");
            }
            if (driverProfileRepository.existsByLicensePlate(request.getLicensePlate().trim().toUpperCase())) {
                throw new DuplicateResourceException("A driver profile with this license plate already exists: " + request.getLicensePlate());
            }
        }

        String passwordHash = passwordEncoder.encode(request.getPassword());

        User user = User.builder()
                .email(request.getEmail().trim().toLowerCase())
                .passwordHash(passwordHash)
                .firstName(request.getFirstName().trim())
                .lastName(request.getLastName().trim())
                .phoneNumber(request.getPhoneNumber().trim())
                .role(role)
                .status(UserStatus.ACTIVE)
                .build();

        User savedUser = userRepository.save(user);

        if (role == Role.ROLE_DRIVER) {
            DriverProfile driverProfile = DriverProfile.builder()
                    .user(savedUser)
                    .vehicleModel(request.getVehicleModel().trim())
                    .licensePlate(request.getLicensePlate().trim().toUpperCase())
                    .status(DriverStatus.OFFLINE)
                    .build();
            DriverProfile savedProfile = driverProfileRepository.save(driverProfile);
            savedUser.setDriverProfile(savedProfile);
        }

        String accessToken = jwtUtils.generateToken(savedUser.getId(), savedUser.getEmail(), savedUser.getRole().name());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(jwtProperties.getExpirationMs() / 1000)
                .userId(savedUser.getId())
                .email(savedUser.getEmail())
                .role(savedUser.getRole().name())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        log.info("Processing login for email: {}", request.getEmail());

        User user = userRepository.findByEmail(request.getEmail().trim().toLowerCase())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidCredentialsException("User account is " + user.getStatus().name().toLowerCase());
        }

        String accessToken = jwtUtils.generateToken(user.getId(), user.getEmail(), user.getRole().name());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(jwtProperties.getExpirationMs() / 1000)
                .userId(user.getId())
                .email(user.getEmail())
                .role(user.getRole().name())
                .build();
    }
}
