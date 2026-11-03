package com.rideshare.pricing_service.service;

import com.rideshare.pricing_service.dto.request.FareEstimateRequest;
import com.rideshare.pricing_service.dto.response.FareEstimateResponse;
import com.rideshare.pricing_service.dto.response.FareQuoteResponse;

public interface PricingService {

    FareEstimateResponse estimateFare(FareEstimateRequest request);

    FareQuoteResponse verifyQuote(String quoteToken);
}
