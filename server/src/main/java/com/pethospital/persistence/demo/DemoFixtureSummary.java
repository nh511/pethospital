package com.pethospital.persistence.demo;

public record DemoFixtureSummary(
    int users,
    int doctors,
    int pets,
    int slots,
    int appointments,
    int aiSessions,
    int healthContents,
    int medicalRecords,
    int medicalVersions
) {
}
