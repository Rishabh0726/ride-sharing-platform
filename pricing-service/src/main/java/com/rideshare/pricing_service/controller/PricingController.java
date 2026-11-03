package com.rideshare.pricing_service.controller;

import com.rideshare.pricing_service.dto.request.FareEstimateRequest;
import com.rideshare.pricing_service.dto.response.FareEstimateResponse;
import com.rideshare.pricing_service.dto.response.FareQuoteResponse;
import com.rideshare.pricing_service.service.PricingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/pricing")
@RequiredArgsConstructor
@Tag(name = "Pricing & Fare Estimation", description = "Endpoints for generating guaranteed fare quotes and quote verification")
@SecurityRequirement(name = "bearerAuth")
public class PricingController {

    private final PricingService pricingService;

    @PostMapping("/estimate")
    @PreAuthorize("hasAnyAuthority('ROLE_PASSENGER', 'ROLE_DRIVER', 'ROLE_INTERNAL_SERVICE')")
    @Operation(summary = "Generate a guaranteed 10-minute fare quote based on pickup/destination coordinates and vehicle type")
    public ResponseEntity<FareEstimateResponse> estimateFare(@Valid @RequestBody FareEstimateRequest request) {
        FareEstimateResponse response = pricingService.estimateFare(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/quotes/{quoteToken}")
    @PreAuthorize("hasAuthority('ROLE_INTERNAL_SERVICE')")
    @Operation(summary = "Verify quote validity and retrieve original fare snapshot (reusable until 10-minute expiry)")
    public ResponseEntity<FareQuoteResponse> verifyQuote(@PathVariable("quoteToken") String quoteToken) {
        FareQuoteResponse response = pricingService.verifyQuote(quoteToken);
        return ResponseEntity.ok(response);
    }
}
