package com.history.backend.shared.repository;

import java.util.Optional;
import java.util.UUID;

import com.history.backend.shared.domain.WebhookDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    Optional<WebhookDelivery> findByDeliveryId(String deliveryId);

    boolean existsByDeliveryId(String deliveryId);

    @Modifying
    @Query(
            value = """
                    INSERT INTO webhook_deliveries (delivery_id, project_id, status)
                    VALUES (:deliveryId, :projectId, 'IN_PROGRESS')
                    ON CONFLICT (delivery_id) DO NOTHING
                    """,
            nativeQuery = true
    )
    int tryClaim(@Param("deliveryId") String deliveryId, @Param("projectId") UUID projectId);
}
