package com.rideshare.ride_service.repository;

import com.rideshare.ride_service.entity.AuditStatus;
import com.rideshare.ride_service.entity.RideReconciliationAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RideReconciliationAuditRepository extends JpaRepository<RideReconciliationAudit, Long> {

    List<RideReconciliationAudit> findByStatusAndRetryCountLessThan(AuditStatus status, int maxRetries);
}
