package com.rideshare.user_service.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private UserDetailsService userDetailsService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Should set authentication in SecurityContext for active user with valid token")
    void shouldAuthenticateActiveUser() throws ServletException, IOException {
        String token = "valid.jwt.token";
        String email = "active@example.com";

        UserPrincipal activePrincipal = new UserPrincipal(
                1L,
                email,
                "passwordHash",
                List.of(new SimpleGrantedAuthority("ROLE_PASSENGER")),
                true
        );

        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(jwtUtils.validateToken(token)).thenReturn(true);
        when(jwtUtils.getEmailFromToken(token)).thenReturn(email);
        when(jwtUtils.getRoleFromToken(token)).thenReturn("ROLE_PASSENGER");
        when(userDetailsService.loadUserByUsername(email)).thenReturn(activePrincipal);

        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getPrincipal()).isEqualTo(activePrincipal);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Should reject and clear context for suspended user with valid token")
    void shouldRejectSuspendedUserWithValidToken() throws ServletException, IOException {
        String token = "valid.jwt.token.suspended";
        String email = "suspended@example.com";

        UserPrincipal suspendedPrincipal = new UserPrincipal(
                2L,
                email,
                "passwordHash",
                List.of(new SimpleGrantedAuthority("ROLE_PASSENGER")),
                false // active = false
        );

        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(jwtUtils.validateToken(token)).thenReturn(true);
        when(jwtUtils.getEmailFromToken(token)).thenReturn(email);
        when(jwtUtils.getRoleFromToken(token)).thenReturn("ROLE_PASSENGER");
        when(userDetailsService.loadUserByUsername(email)).thenReturn(suspendedPrincipal);

        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Should clear context when token validation fails (expired or invalid)")
    void shouldClearContextOnInvalidToken() throws ServletException, IOException {
        String token = "expired.or.invalid.token";

        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(jwtUtils.validateToken(token)).thenReturn(false);

        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNull();
        verify(userDetailsService, never()).loadUserByUsername(anyString());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Should proceed without authentication when no Authorization header is present")
    void shouldProceedWhenNoTokenPresent() throws ServletException, IOException {
        when(request.getHeader("Authorization")).thenReturn(null);

        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNull();
        verify(jwtUtils, never()).validateToken(anyString());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Should handle exception during user loading and ensure context is cleared")
    void shouldClearContextOnExceptionDuringUserLoad() throws ServletException, IOException {
        String token = "valid.jwt.token";
        String email = "missing@example.com";

        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(jwtUtils.validateToken(token)).thenReturn(true);
        when(jwtUtils.getEmailFromToken(token)).thenReturn(email);
        when(jwtUtils.getRoleFromToken(token)).thenReturn("ROLE_PASSENGER");
        when(userDetailsService.loadUserByUsername(email)).thenThrow(new UsernameNotFoundException("User not found"));

        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Should authenticate internal service token without loading user from database")
    void shouldAuthenticateInternalServiceTokenWithoutUserLookup() throws ServletException, IOException {
        String token = "valid.internal.token";
        String email = "internal-ride-service@rideshare.internal";

        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(jwtUtils.validateToken(token)).thenReturn(true);
        when(jwtUtils.getEmailFromToken(token)).thenReturn(email);
        when(jwtUtils.getRoleFromToken(token)).thenReturn("ROLE_INTERNAL_SERVICE");

        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_INTERNAL_SERVICE");
        verify(userDetailsService, never()).loadUserByUsername(anyString());
        verify(filterChain).doFilter(request, response);
    }
}
