package com.rideshare.user_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rideshare.user_service.config.JacksonConfig;
import com.rideshare.user_service.dto.request.UpdateProfileRequest;
import com.rideshare.user_service.dto.response.UserProfileResponse;
import com.rideshare.user_service.exception.GlobalExceptionHandler;
import com.rideshare.user_service.security.AuthEntryPointJwt;
import com.rideshare.user_service.security.JwtAuthenticationFilter;
import com.rideshare.user_service.security.JwtUtils;
import com.rideshare.user_service.security.SecurityConfig;
import com.rideshare.user_service.security.UserPrincipal;
import com.rideshare.user_service.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, AuthEntryPointJwt.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class, JacksonConfig.class})
@ActiveProfiles("test")
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private UserDetailsService userDetailsService;

    private UserPrincipal testPrincipal;
    private UserProfileResponse profileResponse;

    @BeforeEach
    void setUp() {
        testPrincipal = new UserPrincipal(
                1L,
                "alice@example.com",
                "hashedPassword",
                List.of(new SimpleGrantedAuthority("ROLE_PASSENGER")),
                true
        );

        profileResponse = UserProfileResponse.builder()
                .userId(1L)
                .email("alice@example.com")
                .firstName("Alice")
                .lastName("Smith")
                .phoneNumber("+919876543210")
                .role("ROLE_PASSENGER")
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("GET /api/v1/users/me - returns 200 OK when authenticated")
    void shouldReturnProfileWhenAuthenticated() throws Exception {
        when(userService.getCurrentUserProfile(1L)).thenReturn(profileResponse);

        mockMvc.perform(get("/api/v1/users/me")
                        .with(user(testPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.firstName").value("Alice"))
                .andExpect(jsonPath("$.role").value("ROLE_PASSENGER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("GET /api/v1/users/me - returns 401 Unauthorized when unauthenticated")
    void shouldReturnUnauthorizedWhenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("PATCH /api/v1/users/me - returns 200 OK with updated profile")
    void shouldUpdateProfileSuccessfully() throws Exception {
        UpdateProfileRequest request = UpdateProfileRequest.builder()
                .firstName("Alice Updated")
                .lastName("Smith")
                .phoneNumber("+919123456789")
                .build();

        UserProfileResponse updatedProfile = UserProfileResponse.builder()
                .userId(1L)
                .email("alice@example.com")
                .firstName("Alice Updated")
                .lastName("Smith")
                .phoneNumber("+919123456789")
                .role("ROLE_PASSENGER")
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .build();

        when(userService.updateCurrentUserProfile(eq(1L), any(UpdateProfileRequest.class)))
                .thenReturn(updatedProfile);

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(user(testPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Alice Updated"))
                .andExpect(jsonPath("$.phoneNumber").value("+919123456789"));
    }

    @Test
    @DisplayName("PATCH /api/v1/users/me - returns 400 Bad Request when validation fails")
    void shouldReturnBadRequestOnValidationFailure() throws Exception {
        // phoneNumber exceeds 20 characters max limit
        UpdateProfileRequest invalidRequest = UpdateProfileRequest.builder()
                .phoneNumber("123456789012345678901")
                .build();

        mockMvc.perform(patch("/api/v1/users/me")
                        .with(user(testPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors.phoneNumber").exists());
    }

    @Test
    @DisplayName("GET /api/v1/users/me - returns 200 OK when valid Bearer token for active user is provided")
    void shouldReturnProfileWithValidJwtForActiveUser() throws Exception {
        String token = "valid.jwt.token.active";
        when(jwtUtils.validateToken(token)).thenReturn(true);
        when(jwtUtils.getEmailFromToken(token)).thenReturn("alice@example.com");
        when(userDetailsService.loadUserByUsername("alice@example.com")).thenReturn(testPrincipal);
        when(userService.getCurrentUserProfile(1L)).thenReturn(profileResponse);

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.firstName").value("Alice"))
                .andExpect(jsonPath("$.role").value("ROLE_PASSENGER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("GET /api/v1/users/me - returns 401 Unauthorized when valid unexpired JWT belongs to a suspended user")
    void shouldRejectValidJwtForSuspendedUser() throws Exception {
        String token = "valid.jwt.token.suspended";
        UserPrincipal suspendedPrincipal = new UserPrincipal(
                2L,
                "suspended@example.com",
                "hashedPassword",
                List.of(new SimpleGrantedAuthority("ROLE_PASSENGER")),
                false // active = false (SUSPENDED)
        );

        when(jwtUtils.validateToken(token)).thenReturn(true);
        when(jwtUtils.getEmailFromToken(token)).thenReturn("suspended@example.com");
        when(userDetailsService.loadUserByUsername("suspended@example.com")).thenReturn(suspendedPrincipal);

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("GET /api/v1/users/me - returns 401 Unauthorized when token is expired")
    void shouldRejectExpiredJwt() throws Exception {
        String expiredToken = "expired.jwt.token";
        when(jwtUtils.validateToken(expiredToken)).thenReturn(false);

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("GET /api/v1/users/me - returns 401 Unauthorized when token is malformed")
    void shouldRejectMalformedJwt() throws Exception {
        String malformedToken = "not.a.valid.token";
        when(jwtUtils.validateToken(malformedToken)).thenReturn(false);

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + malformedToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401));
    }
}
