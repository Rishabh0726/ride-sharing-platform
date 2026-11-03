package com.rideshare.pricing_service.repository;

import com.rideshare.pricing_service.entity.PricingConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PricingConfigurationRepository extends JpaRepository<PricingConfiguration, Long> {

    Optional<PricingConfiguration> findByVehicleTypeIgnoreCaseAndActiveTrue(String vehicleType);

    boolean existsByVehicleTypeIgnoreCaseAndActiveTrue(String vehicleType);
}
