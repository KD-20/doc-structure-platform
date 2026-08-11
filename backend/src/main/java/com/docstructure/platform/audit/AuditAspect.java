package com.docstructure.platform.audit;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

@Aspect
@Component
public class AuditAspect {

    private final AuditService auditService;

    public AuditAspect(AuditService auditService) {
        this.auditService = auditService;
    }

    @AfterReturning(pointcut = "@annotation(audited)", returning = "result")
    public void audit(JoinPoint joinPoint, Audited audited, Object result) {
        UUID entityId = audited.entityIdArgIndex() >= 0
                ? asUuid(joinPoint.getArgs()[audited.entityIdArgIndex()])
                : extractIdFromResult(result);
        auditService.record(audited.action(), audited.entityType(), entityId, Map.of());
    }

    private UUID asUuid(Object arg) {
        return arg instanceof UUID uuid ? uuid : null;
    }

    /**
     * Tries getId() (JPA entities) then id() (records, e.g. DocumentSummaryResponse).
     * setAccessible(true) is required here: most response records are package-private
     * ("only the controller needs this DTO"), and plain reflection across packages throws
     * IllegalAccessException rather than finding the method — this bypasses that access
     * check, safe since it only ever touches our own DTOs, never caller-supplied data.
     */
    private UUID extractIdFromResult(Object result) {
        if (result == null) {
            return null;
        }
        for (String accessor : new String[]{"getId", "id"}) {
            try {
                Method method = result.getClass().getMethod(accessor);
                method.setAccessible(true);
                Object id = method.invoke(result);
                if (id instanceof UUID uuid) {
                    return uuid;
                }
            } catch (ReflectiveOperationException ignored) {
                // try the next accessor name
            }
        }
        return null;
    }
}
