package com.docstructure.platform.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a service method whose successful completion should write an audit_log row.
 * AuditAspect fires on @AfterReturning (success only) — a method that needs to audit both a
 * success AND a failure outcome (e.g. an extraction run that can SUCCEED or FAIL, both
 * legitimate outcomes to record) should call AuditService.record(...) directly instead; see
 * ExtractionService for that pattern.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {
    String action();

    String entityType();

    /** Index into the annotated method's arguments for the entity id; -1 (default) extracts it from the return value's getId() instead. */
    int entityIdArgIndex() default -1;
}
