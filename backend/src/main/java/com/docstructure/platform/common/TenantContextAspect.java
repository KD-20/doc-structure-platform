package com.docstructure.platform.common;

import jakarta.persistence.EntityManager;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Binds TenantContext's tenant id to the current transaction's Postgres connection via
 * set_config('app.current_tenant_id', ...), which every row-level security policy in
 * V1__init_schema.sql reads. Must run AFTER the @Transactional advice has opened the
 * transaction (so this set_config lands on the same connection/transaction as the rest of
 * the method), hence @Order(1) against TransactionConfig's @EnableTransactionManagement(order = 0).
 *
 * If no tenant id is bound (e.g. platform-level bootstrap code), this is a no-op and RLS
 * policies fall back to their default-deny behavior for tenant-scoped tables.
 */
@Aspect
@Component
@Order(1)
public class TenantContextAspect {

    private final EntityManager entityManager;

    public TenantContextAspect(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Around("@annotation(com.docstructure.platform.common.TenantScoped) || @within(com.docstructure.platform.common.TenantScoped)")
    public Object bindTenantContext(ProceedingJoinPoint pjp) throws Throwable {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            entityManager.createNativeQuery("SELECT set_config('app.current_tenant_id', :tenantId, true)")
                    .setParameter("tenantId", tenantId.toString())
                    .getSingleResult();
        }
        return pjp.proceed();
    }
}
