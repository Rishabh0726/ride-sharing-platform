package com.rideshare.user_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rideshare.user_service.config.JacksonConfig;
import com.rideshare.user_service.dto.request.LoginRequest;
import com.rideshare.user_service.dto.request.RegisterRequest;
import com.rideshare.user_service.dto.response.AuthResponse;
import com.rideshare.user_service.exception.GlobalExceptionHandler;
import com.rideshare.user_service.security.JwtUtils;
import com.rideshare.user_service.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, JacksonConfig.class})
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @Test
    @DisplayName("POST /api/v1/auth/register - returns 201 Created on valid input")
    void shouldRegisterSuccessfully() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .email("alice@example.com")
                .password("StrongPassword123!")
                .firstName("Alice")
                .lastName("Smith")
                .phoneNumber("+919876543210")
                .role("PASSENGER")
                .build();

        AuthResponse response = AuthResponse.builder()
                .accessToken("mock.jwt.token")
                .tokenType("Bearer")
                .expiresIn(86400)
                .userId(1L)
                .email("alice@example.com")
                .role("ROLE_PASSENGER")
                .build();

        when(authService.register(any(RegisterRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("mock.jwt.token"))
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.role").value("ROLE_PASSENGER"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/register - returns 400 Bad Request on invalid email and weak password")
    void shouldReturnBadRequestOnValidationFailure() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .email("invalid-email-format")
                .password("short") // less than 8 chars
                .firstName("")
                .lastName("")
                .phoneNumber("")
                .role("PASSENGER")
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    @DisplayName("POST /api/v1/auth/register - returns 400 Bad Request when role is not PASSENGER or DRIVER")
    void shouldRejectInvalidRole() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .email("admin@example.com")
                .password("StrongPassword123!")
                .firstName("Super")
                .lastName("Admin")
                .phoneNumber("+919876543210")
                .role("ADMIN") // Unapproved role
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.role").exists());
    }

    @Test
    @DisplayName("POST /api/v1/auth/login - returns 200 OK on valid credentials")
    void shouldLoginSuccessfully() throws Exception {
        LoginRequest request = LoginRequest.builder()
                .email("alice@example.com")
                .password("StrongPassword123!")
                .build();

        AuthResponse response = AuthResponse.builder()
                .accessToken("mock.jwt.token")
                .tokenType("Bearer")
                .expiresIn(86400)
                .userId(1L)
                .email("alice@example.com")
                .role("ROLE_PASSENGER")
                .build();

        when(authService.login(any(LoginRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("mock.jwt.token"))
                .andExpect(jsonPath("$.userId").value(1));
    }
}
