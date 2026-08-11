package com.docstructure.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Explicitly orders the @Transactional advice to wrap OUTSIDE TenantContextAspect
 * (order 0 = highest precedence = outermost). Spring Boot's default auto-configured
 * transaction advice uses Ordered.LOWEST_PRECEDENCE, which would make it impossible for
 * any other aspect to run "inside" it — but TenantContextAspect's set_config call must run
 * after the transaction (and its connection) has started, so it needs a higher order value
 * (further from zero) than this. See TenantContextAspect and docs/DECISIONS.md.
 */
@Configuration
@EnableTransactionManagement(order = 0)
public class TransactionConfig {
}
