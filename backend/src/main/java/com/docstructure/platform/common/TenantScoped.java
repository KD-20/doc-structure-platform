package com.docstructure.platform.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a @Transactional service method (or type) whose queries rely on Postgres row-level
 * security for tenant isolation. TenantContextAspect binds {@link TenantContext}'s tenant id
 * to the current transaction's connection via set_config before the method body runs.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantScoped {
}
