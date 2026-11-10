package com.pethospital.pet;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.pethospital.pet.PetDtos.ClinicalSummary;
import java.time.LocalDate;
import java.util.List;

public final class StaffWorkspaceDtos {
    private StaffWorkspaceDtos() { }

    public record AppointmentCard(
        String id, String petId, String petName, String status, String chiefComplaint,
        String scheduledStartAt, String scheduledEndAt
    ) { }

    public record StaffDashboard(
        LocalDate date, String timezone, List<AppointmentCard> today, List<AppointmentCard> pending,
        List<AppointmentCard> recentCompleted
    ) { }

    public record StaffPetSummary(
        String id, String name, String speciesCode, String breed, String sex,
        LocalDate birthDate, String neutered, String status
    ) { }

    public record StaffPetDetail(StaffPetSummary pet, ClinicalSummary clinicalSummary) { }

    public record StaffPetPage(
        List<StaffPetSummary> items, int page, int pageSize, @JsonSerialize(as = Number.class) Long total
    ) { }
}
