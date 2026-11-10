package com.pethospital.pet;

public interface PetArchiveDependencyQuery {
    ArchiveDependencies lockForPet(long petId);

    record ArchiveDependencies(int unfinishedAppointmentCount, int processingMessageCount) {
        public int totalCount() {
            return unfinishedAppointmentCount + processingMessageCount;
        }
    }
}
