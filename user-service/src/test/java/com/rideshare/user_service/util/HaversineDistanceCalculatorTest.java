package com.rideshare.user_service.util;

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
    @DisplayName("Calculate distance between Bangalore MG Road and Koramangala (~5.18 km straight-line)")
    void shouldCalculateKnownDistanceAccurately() {
        BigDecimal mgRoadLat = BigDecimal.valueOf(12.9716);
        BigDecimal mgRoadLon = BigDecimal.valueOf(77.5946);
        BigDecimal koramangalaLat = BigDecimal.valueOf(12.9352);
        BigDecimal koramangalaLon = BigDecimal.valueOf(77.6245);

        double distance = calculator.calculateDistance(mgRoadLat, mgRoadLon, koramangalaLat, koramangalaLon);

        // Expected great-circle spherical distance is 5.18 km
        assertThat(distance).isBetween(5.0, 5.5);
        assertThat(distance).isEqualTo(5.18);
    }

    @Test
    @DisplayName("Distance between identical coordinates must be 0.0 km")
    void shouldReturnZeroForIdenticalCoordinates() {
        BigDecimal lat = BigDecimal.valueOf(12.9716);
        BigDecimal lon = BigDecimal.valueOf(77.5946);

        double distance = calculator.calculateDistance(lat, lon, lat, lon);

        assertThat(distance).isEqualTo(0.0);
    }

    @Test
    @DisplayName("One degree on the equator is approximately 111.19 km")
    void shouldCalculateEquatorialDegreeDistance() {
        BigDecimal lat1 = BigDecimal.ZERO;
        BigDecimal lon1 = BigDecimal.ZERO;
        BigDecimal lat2 = BigDecimal.ZERO;
        BigDecimal lon2 = BigDecimal.ONE;

        double distance = calculator.calculateDistance(lat1, lon1, lat2, lon2);

        assertThat(distance).isBetween(111.0, 112.0);
    }

    @Test
    @DisplayName("Bounding box near the North Pole clamps latitude and expands longitude across full range")
    void shouldHandleNorthPoleBoundingBox() {
        BigDecimal latNearPole = BigDecimal.valueOf(89.9);
        BigDecimal lon = BigDecimal.valueOf(50.0);
        double radiusKm = 20.0;

        HaversineDistanceCalculator.GeoBoundingBox bbox = calculator.calculateBoundingBox(latNearPole, lon, radiusKm);

        assertThat(bbox.maxLat().doubleValue()).isLessThanOrEqualTo(90.0);
        assertThat(bbox.minLon().doubleValue()).isEqualTo(-180.0);
        assertThat(bbox.maxLon().doubleValue()).isEqualTo(180.0);
        assertThat(bbox.crossesAntimeridian()).isFalse();
    }

    @Test
    @DisplayName("Bounding box crossing the 180th antimeridian sets crossesAntimeridian to true")
    void shouldDetectAntimeridianCrossing() {
        BigDecimal lat = BigDecimal.valueOf(0.0);
        BigDecimal lonNearAntimeridian = BigDecimal.valueOf(179.9);
        double radiusKm = 20.0; // ~0.18 degrees longitude delta

        HaversineDistanceCalculator.GeoBoundingBox bbox = calculator.calculateBoundingBox(lat, lonNearAntimeridian, radiusKm);

        assertThat(bbox.crossesAntimeridian()).isTrue();
    }

    @Test
    @DisplayName("Throw IllegalArgumentException when coordinates are null or radius is non-positive")
    void shouldThrowExceptionOnInvalidInputs() {
        assertThatThrownBy(() -> calculator.calculateDistance(null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> calculator.calculateBoundingBox(BigDecimal.ZERO, BigDecimal.ZERO, 0.0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> calculator.calculateBoundingBox(BigDecimal.ZERO, BigDecimal.ZERO, -5.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
