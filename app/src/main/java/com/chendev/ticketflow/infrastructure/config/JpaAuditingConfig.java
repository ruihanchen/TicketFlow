package com.chendev.ticketflow.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
//required for @CreatedDate / @LastModifiedDate to actually work.
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {

}
