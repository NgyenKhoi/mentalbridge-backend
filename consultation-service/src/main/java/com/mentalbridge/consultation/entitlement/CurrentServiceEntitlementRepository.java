package com.mentalbridge.consultation.entitlement;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CurrentServiceEntitlementRepository extends JpaRepository<CurrentServiceEntitlementEntity, UUID> {
}
