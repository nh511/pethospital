package com.pethospital.pet;

import com.pethospital.shared.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivePetGuard {
    private final PetMapper pets;

    public ActivePetGuard(PetMapper pets) {
        this.pets = pets;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PetMapper.PetRow lockOwnedActive(long petId, long ownerUserId) {
        PetMapper.PetRow pet = pets.findOwnedRow(petId, ownerUserId, true);
        if (pet == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "PET_NOT_FOUND", "宠物不存在");
        }
        if (!"ACTIVE".equals(pet.status())) {
            throw new BusinessException(HttpStatus.CONFLICT, "PET_ARCHIVED", "宠物已归档，不能创建新的预约或问诊");
        }
        return pet;
    }
}
