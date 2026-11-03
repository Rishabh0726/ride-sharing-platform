package com.rideshare.ride_service.config;

import com.rideshare.ride_service.security.InternalTokenService;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InternalFeignClientInterceptor implements RequestInterceptor {

    private final InternalTokenService internalTokenService;

    @Override
    public void apply(RequestTemplate template) {
        String token = internalTokenService.getInternalToken();
        template.header("Authorization", "Bearer " + token);
        template.header("X-Service-Name", "ride-service");
    }
}
