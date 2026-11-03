package com.rideshare.user_service.service;

import com.rideshare.user_service.dto.request.UpdateProfileRequest;
import com.rideshare.user_service.dto.response.UserProfileResponse;

public interface UserService {
    UserProfileResponse getCurrentUserProfile(Long userId);
    UserProfileResponse updateCurrentUserProfile(Long userId, UpdateProfileRequest request);
}
