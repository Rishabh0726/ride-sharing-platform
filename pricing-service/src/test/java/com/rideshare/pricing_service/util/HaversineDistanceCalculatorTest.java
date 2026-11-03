package com.rideshare.pricing_service.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HaversineDistanceCalculatorTest {

    private HaversineDistanceCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new HaversineDistanceCalculator();
    }

    @Test
    @DisplayName("Identical coordinates return 0.0 km")
    void shouldReturnZeroForIdenticalCoordinates() {
        BigDecimal lat = BigDecimal.valueOf(12.9716);
        BigDecimal lon = BigDecimal.valueOf(77.5946);

        double distance = calculator.calculateDistance(lat, lon, lat, lon);
        assertThat(distance).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Known Bangalore coordinates (MG Road to Koramangala) return full-precision spherical distance (~5.184 km) rounding to 5.18 km")
    void shouldCalculateAccurateDistanceBetweenKnownPoints() {
        // MG Road, Bangalore: 12.9716, 77.5946
        // Koramangala, Bangalore: 12.9352, 77.6245
        BigDecimal mgRoadLat = BigDecimal.valueOf(12.9716);
        BigDecimal mgRoadLon = BigDecimal.valueOf(77.5946);
        BigDecimal koraLat = BigDecimal.valueOf(12.9352);
        BigDecimal koraLon = BigDecimal.valueOf(77.6245);

        double distance = calculator.calculateDistance(mgRoadLat, mgRoadLon, koraLat, koraLon);
        // Full precision distance is ~5.184321...
        assertThat(distance).isGreaterThan(5.18).isLessThan(5.19);
        assertThat(BigDecimal.valueOf(distance).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue())
                .isEqualTo(5.18);
    }

    @Test
    @DisplayName("Null coordinates throw IllegalArgumentException")
    void shouldThrowExceptionForNullCoordinates() {
        BigDecimal lat = BigDecimal.valueOf(12.9716);
        BigDecimal lon = BigDecimal.valueOf(77.5946);

        assertThatThrownBy(() -> calculator.calculateDistance(null, lon, lat, lon))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> calculator.calculateDistance(lat, null, lat, lon))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Antipodal points return approximately half earth circumference (~20015 km)")
    void shouldHandleAntipodalPointsWithoutNaN() {
        BigDecimal lat1 = BigDecimal.valueOf(0.0);
        BigDecimal lon1 = BigDecimal.valueOf(0.0);
        BigDecimal lat2 = BigDecimal.valueOf(0.0);
        BigDecimal lon2 = BigDecimal.valueOf(180.0);

        double distance = calculator.calculateDistance(lat1, lon1, lat2, lon2);
        assertThat(distance).isGreaterThan(20000.0).isLessThan(20050.0);
    }
}
