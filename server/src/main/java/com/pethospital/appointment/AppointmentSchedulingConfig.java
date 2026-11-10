package com.pethospital.appointment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "appointment.timeout-scan.enabled", matchIfMissing = true)
@EnableScheduling
class AppointmentSchedulingConfig { }
