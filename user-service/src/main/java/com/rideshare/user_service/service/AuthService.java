package com.rideshare.user_service.service;

import com.rideshare.user_service.dto.request.LoginRequest;
import com.rideshare.user_service.dto.request.RegisterRequest;
import com.rideshare.user_service.dto.response.AuthResponse;

public interface AuthService {
    AuthResponse register(RegisterRequest request);
    AuthResponse login(LoginRequest request);
}
