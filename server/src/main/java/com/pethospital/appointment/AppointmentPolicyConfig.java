package com.pethospital.appointment;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AppointmentPolicyConfig {
    @Bean
    AppointmentStateMachine appointmentStateMachine(
        @Value("${appointment.early-start-minutes:15}") long earlyStartMinutes,
        @Value("${appointment.no-show-grace-minutes:30}") long noShowGraceMinutes
    ) {
        return new AppointmentStateMachine(Duration.ofMinutes(earlyStartMinutes), Duration.ofMinutes(noShowGraceMinutes));
    }
}
