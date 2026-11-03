package com.rideshare.user_service.service;

import com.rideshare.user_service.dto.request.UpdateProfileRequest;
import com.rideshare.user_service.dto.response.DriverProfileResponse;
import com.rideshare.user_service.dto.response.UserProfileResponse;
import com.rideshare.user_service.entity.DriverProfile;
import com.rideshare.user_service.entity.User;
import com.rideshare.user_service.exception.ResourceNotFoundException;
import com.rideshare.user_service.repository.DriverProfileRepository;
import com.rideshare.user_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final DriverProfileRepository driverProfileRepository;

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse getCurrentUserProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        return mapToUserProfileResponse(user);
    }

    @Override
    @Transactional
    public UserProfileResponse updateCurrentUserProfile(Long userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        if (StringUtils.hasText(request.getFirstName())) {
            user.setFirstName(request.getFirstName().trim());
        }
        if (StringUtils.hasText(request.getLastName())) {
            user.setLastName(request.getLastName().trim());
        }
        if (StringUtils.hasText(request.getPhoneNumber())) {
            user.setPhoneNumber(request.getPhoneNumber().trim());
        }

        User updatedUser = userRepository.save(user);
        return mapToUserProfileResponse(updatedUser);
    }

    private UserProfileResponse mapToUserProfileResponse(User user) {
        DriverProfileResponse driverResponse = null;

        DriverProfile profile = driverProfileRepository.findByUserId(user.getId()).orElse(null);
        if (profile != null) {
            driverResponse = DriverProfileResponse.builder()
                    .driverId(profile.getId())
                    .vehicleModel(profile.getVehicleModel())
                    .licensePlate(profile.getLicensePlate())
                    .status(profile.getStatus().name())
                    .ratingAvg(profile.getRatingAvg())
                    .build();
        }

        return UserProfileResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .phoneNumber(user.getPhoneNumber())
                .role(user.getRole().name())
                .status(user.getStatus().name())
                .createdAt(user.getCreatedAt())
                .driverProfile(driverResponse)
                .build();
    }
}
