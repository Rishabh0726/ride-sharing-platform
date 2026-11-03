package com.rideshare.ride_service.repository;

import com.rideshare.ride_service.entity.AuditStatus;
import com.rideshare.ride_service.entity.RideReconciliationAudit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class RideReconciliationAuditRepositoryTest {

    @Autowired
    private RideReconciliationAuditRepository auditRepository;

    @Test
    @DisplayName("findByStatusAndRetryCountLessThan returns matching records")
    void testQueryPendingAudits() {
        RideReconciliationAudit task1 = RideReconciliationAudit.builder()
                .rideId(101L)
                .driverId(201L)
                .operation("COMPLETE_RIDE")
                .retryCount(1)
                .status(AuditStatus.PENDING)
                .errorMessage("Read timed out")
                .build();

        RideReconciliationAudit task2 = RideReconciliationAudit.builder()
                .rideId(102L)
                .driverId(202L)
                .operation("RELEASE_RESERVATION")
                .retryCount(5)
                .status(AuditStatus.PENDING)
                .errorMessage("Max retries")
                .build();

        RideReconciliationAudit task3 = RideReconciliationAudit.builder()
                .rideId(103L)
                .driverId(203L)
                .operation("COMPLETE_RIDE")
                .retryCount(0)
                .status(AuditStatus.RESOLVED)
                .build();

        auditRepository.saveAll(List.of(task1, task2, task3));

        List<RideReconciliationAudit> results = auditRepository.findByStatusAndRetryCountLessThan(AuditStatus.PENDING, 5);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getRideId()).isEqualTo(101L);
    }
}
