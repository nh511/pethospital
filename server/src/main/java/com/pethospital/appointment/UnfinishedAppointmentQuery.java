package com.pethospital.appointment;

public interface UnfinishedAppointmentQuery {
    UnfinishedAppointments lockForAccount(long userId);

    int lockForDoctor(long doctorId);

    record UnfinishedAppointments(int ownerCount, int doctorCount, int totalCount) {
    }
}
