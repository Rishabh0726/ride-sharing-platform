package com.rideshare.ride_service.client;

import com.rideshare.ride_service.client.dto.FareQuoteResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "pricing-service", url = "${pricing-service.url:http://localhost:8083}")
public interface PricingClient {

    @GetMapping("/api/v1/pricing/quotes/{quoteToken}")
    ResponseEntity<FareQuoteResponse> verifyQuote(@PathVariable("quoteToken") String quoteToken);
}
