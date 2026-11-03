package com.rideshare.user_service.util;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class HaversineDistanceCalculator {

    public static final double EARTH_RADIUS_KM = 6371.0088;

    public record GeoBoundingBox(
            BigDecimal minLat,
            BigDecimal maxLat,
            BigDecimal minLon,
            BigDecimal maxLon,
            boolean crossesAntimeridian
    ) {}

    public double calculateDistance(BigDecimal lat1, BigDecimal lon1, BigDecimal lat2, BigDecimal lon2) {
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) {
            throw new IllegalArgumentException("Coordinates must not be null");
        }

        if (lat1.compareTo(lat2) == 0 && lon1.compareTo(lon2) == 0) {
            return 0.0;
        }

        double dLat = Math.toRadians(lat2.doubleValue() - lat1.doubleValue());
        double dLon = Math.toRadians(lon2.doubleValue() - lon1.doubleValue());

        double rLat1 = Math.toRadians(lat1.doubleValue());
        double rLat2 = Math.toRadians(lat2.doubleValue());

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(rLat1) * Math.cos(rLat2) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);

        // Clamp to avoid rounding domain errors in sqrt / asin
        a = Math.max(0.0, Math.min(1.0, a));

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        double distance = EARTH_RADIUS_KM * c;

        return BigDecimal.valueOf(distance)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    public GeoBoundingBox calculateBoundingBox(BigDecimal lat, BigDecimal lon, double radiusKm) {
        if (lat == null || lon == null) {
            throw new IllegalArgumentException("Coordinates must not be null");
        }
        if (radiusKm <= 0) {
            throw new IllegalArgumentException("Radius must be strictly positive");
        }

        double dLatDeg = Math.toDegrees(radiusKm / EARTH_RADIUS_KM);
        double minLatVal = Math.max(-90.0, lat.doubleValue() - dLatDeg);
        double maxLatVal = Math.min(90.0, lat.doubleValue() + dLatDeg);

        // Pole proximity check: meridians converge at the poles
        if (lat.doubleValue() + dLatDeg >= 90.0 || lat.doubleValue() - dLatDeg <= -90.0) {
            return new GeoBoundingBox(
                    BigDecimal.valueOf(minLatVal).setScale(8, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(maxLatVal).setScale(8, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(-180.0).setScale(8, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(180.0).setScale(8, RoundingMode.HALF_UP),
                    false
            );
        }

        double rLat = Math.toRadians(lat.doubleValue());
        double cosLat = Math.cos(rLat);
        double dLonDeg = Math.toDegrees(radiusKm / (EARTH_RADIUS_KM * cosLat));

        double rawMinLon = lon.doubleValue() - dLonDeg;
        double rawMaxLon = lon.doubleValue() + dLonDeg;

        boolean crossesAntimeridian = false;
        double finalMinLon;
        double finalMaxLon;

        if (rawMinLon < -180.0 || rawMaxLon > 180.0) {
            crossesAntimeridian = true;
            // Wrapped interval: [rawMinLon + 360, 180] U [-180, rawMaxLon - 360]
            finalMinLon = rawMinLon < -180.0 ? rawMinLon + 360.0 : rawMinLon;
            finalMaxLon = rawMaxLon > 180.0 ? rawMaxLon - 360.0 : rawMaxLon;
        } else {
            finalMinLon = rawMinLon;
            finalMaxLon = rawMaxLon;
        }

        return new GeoBoundingBox(
                BigDecimal.valueOf(minLatVal).setScale(8, RoundingMode.HALF_UP),
                BigDecimal.valueOf(maxLatVal).setScale(8, RoundingMode.HALF_UP),
                BigDecimal.valueOf(finalMinLon).setScale(8, RoundingMode.HALF_UP),
                BigDecimal.valueOf(finalMaxLon).setScale(8, RoundingMode.HALF_UP),
                crossesAntimeridian
        );
    }
}
