CREATE TABLE pet (
    id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    species_code VARCHAR(32) NOT NULL,
    breed VARCHAR(64) NULL,
    sex VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    birth_date DATE NULL,
    neutered VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    avatar_file_id BIGINT NULL,
    owner_health_notes TEXT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_pet_owner_status (owner_user_id, status),
    KEY ix_pet_species_birth (species_code, birth_date),
    CONSTRAINT ck_pet_sex CHECK (sex IN ('MALE', 'FEMALE', 'UNKNOWN')),
    CONSTRAINT ck_pet_neutered CHECK (neutered IN ('YES', 'NO', 'UNKNOWN')),
    CONSTRAINT ck_pet_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_pet_version CHECK (version >= 0),
    CONSTRAINT fk_pet_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_pet_avatar_file FOREIGN KEY (avatar_file_id) REFERENCES file_object (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pet_health_log (
    id BIGINT NOT NULL,
    pet_id BIGINT NOT NULL,
    author_user_id BIGINT NOT NULL,
    observed_at DATETIME(3) NOT NULL,
    weight_kg DECIMAL(6,2) NULL,
    diet_note TEXT NULL,
    stool_note TEXT NULL,
    activity_note TEXT NULL,
    symptom_note TEXT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_pet_health_log_pet_observed (pet_id, observed_at, id),
    CONSTRAINT ck_pet_health_log_weight CHECK (weight_kg IS NULL OR weight_kg > 0),
    CONSTRAINT ck_pet_health_log_content CHECK (
        weight_kg IS NOT NULL
        OR NULLIF(TRIM(diet_note), '') IS NOT NULL
        OR NULLIF(TRIM(stool_note), '') IS NOT NULL
        OR NULLIF(TRIM(activity_note), '') IS NOT NULL
        OR NULLIF(TRIM(symptom_note), '') IS NOT NULL
    ),
    CONSTRAINT ck_pet_health_log_version CHECK (version >= 0),
    CONSTRAINT fk_pet_health_log_pet FOREIGN KEY (pet_id) REFERENCES pet (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_pet_health_log_author FOREIGN KEY (author_user_id) REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE doctor_slot (
    id BIGINT NOT NULL,
    doctor_id BIGINT NOT NULL,
    slot_date DATE NOT NULL,
    start_at DATETIME(3) NOT NULL,
    end_at DATETIME(3) NOT NULL,
    capacity INT NOT NULL DEFAULT 1,
    occupied_count INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_doctor_slot_start (doctor_id, start_at),
    KEY ix_doctor_slot_status_start (doctor_id, status, start_at),
    KEY ix_doctor_slot_date (slot_date),
    CONSTRAINT ck_doctor_slot_time CHECK (end_at > start_at),
    CONSTRAINT ck_doctor_slot_capacity CHECK (capacity > 0),
    CONSTRAINT ck_doctor_slot_occupied CHECK (occupied_count >= 0 AND occupied_count <= capacity),
    CONSTRAINT ck_doctor_slot_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_doctor_slot_version CHECK (version >= 0),
    CONSTRAINT fk_doctor_slot_doctor FOREIGN KEY (doctor_id) REFERENCES doctor_profile (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE appointment (
    id BIGINT NOT NULL,
    appointment_no VARCHAR(40) COLLATE utf8mb4_0900_bin NOT NULL,
    user_id BIGINT NOT NULL,
    pet_id BIGINT NOT NULL,
    doctor_id BIGINT NOT NULL,
    slot_id BIGINT NOT NULL,
    scheduled_start_at DATETIME(3) NOT NULL,
    scheduled_end_at DATETIME(3) NOT NULL,
    initial_start_at DATETIME(3) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    chief_complaint VARCHAR(1000) NOT NULL,
    ai_session_id BIGINT NULL,
    confirm_deadline_at DATETIME(3) NOT NULL,
    confirmed_at DATETIME(3) NULL,
    started_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    cancelled_by VARCHAR(16) NULL,
    cancelled_at DATETIME(3) NULL,
    terminal_reason VARCHAR(500) NULL,
    idempotency_key VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_appointment_no (appointment_no),
    UNIQUE KEY uk_appointment_user_idempotency (user_id, idempotency_key),
    KEY ix_appointment_user_created (user_id, created_at, id),
    KEY ix_appointment_doctor_status_start (doctor_id, status, scheduled_start_at),
    KEY ix_appointment_pet_status_start (pet_id, status, scheduled_start_at),
    KEY ix_appointment_status_deadline (status, confirm_deadline_at),
    KEY ix_appointment_ai_session (ai_session_id),
    KEY ix_appointment_completed (completed_at),
    CONSTRAINT ck_appointment_status CHECK (
        status IN ('PENDING', 'CONFIRMED', 'IN_CONSULTATION', 'COMPLETED', 'REJECTED', 'CANCELLED', 'NO_SHOW')
    ),
    CONSTRAINT ck_appointment_schedule CHECK (scheduled_end_at > scheduled_start_at),
    CONSTRAINT ck_appointment_confirm_deadline CHECK (confirm_deadline_at <= scheduled_start_at),
    CONSTRAINT ck_appointment_cancelled_by CHECK (
        cancelled_by IS NULL OR cancelled_by IN ('USER', 'DOCTOR', 'ADMIN', 'SYSTEM')
    ),
    CONSTRAINT ck_appointment_cancel_fields CHECK (
        (status = 'CANCELLED' AND cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL)
        OR (status <> 'CANCELLED' AND cancelled_by IS NULL AND cancelled_at IS NULL)
    ),
    CONSTRAINT ck_appointment_terminal_reason CHECK (
        status NOT IN ('REJECTED', 'CANCELLED', 'NO_SHOW')
        OR NULLIF(TRIM(terminal_reason), '') IS NOT NULL
    ),
    CONSTRAINT ck_appointment_completed CHECK (
        (status = 'COMPLETED' AND completed_at IS NOT NULL)
        OR (status <> 'COMPLETED' AND completed_at IS NULL)
    ),
    CONSTRAINT ck_appointment_version CHECK (version >= 0),
    CONSTRAINT fk_appointment_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_appointment_pet FOREIGN KEY (pet_id) REFERENCES pet (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_appointment_doctor FOREIGN KEY (doctor_id) REFERENCES doctor_profile (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_appointment_slot FOREIGN KEY (slot_id) REFERENCES doctor_slot (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE appointment_status_log (
    id BIGINT NOT NULL,
    appointment_id BIGINT NOT NULL,
    from_status VARCHAR(24) NULL,
    to_status VARCHAR(24) NOT NULL,
    actor_type VARCHAR(16) NOT NULL,
    actor_user_id BIGINT NULL,
    reason VARCHAR(500) NULL,
    operation_key VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_appointment_status_operation (appointment_id, operation_key),
    KEY ix_appointment_status_log_created (appointment_id, created_at, id),
    CONSTRAINT ck_appointment_status_log_from CHECK (
        from_status IS NULL
        OR from_status IN ('PENDING', 'CONFIRMED', 'IN_CONSULTATION', 'COMPLETED', 'REJECTED', 'CANCELLED', 'NO_SHOW')
    ),
    CONSTRAINT ck_appointment_status_log_to CHECK (
        to_status IN ('PENDING', 'CONFIRMED', 'IN_CONSULTATION', 'COMPLETED', 'REJECTED', 'CANCELLED', 'NO_SHOW')
    ),
    CONSTRAINT ck_appointment_status_log_transition CHECK (
        (from_status IS NULL AND to_status = 'PENDING')
        OR (from_status IS NOT NULL AND from_status <> to_status)
    ),
    CONSTRAINT ck_appointment_status_log_actor CHECK (actor_type IN ('USER', 'DOCTOR', 'ADMIN', 'SYSTEM')),
    CONSTRAINT ck_appointment_status_log_actor_user CHECK (
        (actor_type = 'SYSTEM' AND actor_user_id IS NULL)
        OR (actor_type <> 'SYSTEM' AND actor_user_id IS NOT NULL)
    ),
    CONSTRAINT ck_appointment_status_log_reason CHECK (
        to_status NOT IN ('REJECTED', 'CANCELLED', 'NO_SHOW')
        OR NULLIF(TRIM(reason), '') IS NOT NULL
    ),
    CONSTRAINT fk_appointment_status_log_appointment FOREIGN KEY (appointment_id) REFERENCES appointment (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_appointment_status_log_actor FOREIGN KEY (actor_user_id) REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE appointment_change_request (
    id BIGINT NOT NULL,
    appointment_id BIGINT NOT NULL,
    proposer_user_id BIGINT NOT NULL,
    proposer_type VARCHAR(16) NOT NULL,
    old_slot_id BIGINT NOT NULL,
    new_slot_id BIGINT NOT NULL,
    base_appointment_version INT NOT NULL,
    old_start_at DATETIME(3) NOT NULL,
    new_start_at DATETIME(3) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    open_guard TINYINT NULL,
    reason VARCHAR(500) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    responder_user_id BIGINT NULL,
    responded_at DATETIME(3) NULL,
    response_reason VARCHAR(500) NULL,
    idempotency_key VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_change_request_open_guard (appointment_id, open_guard),
    UNIQUE KEY uk_change_request_idempotency (appointment_id, proposer_user_id, idempotency_key),
    KEY ix_change_request_status_expires (status, expires_at),
    CONSTRAINT ck_change_request_proposer CHECK (proposer_type IN ('USER', 'DOCTOR')),
    CONSTRAINT ck_change_request_status CHECK (status IN ('OPEN', 'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED')),
    CONSTRAINT ck_change_request_guard CHECK (
        (status = 'OPEN' AND open_guard IS NOT NULL AND open_guard = 1)
        OR (status <> 'OPEN' AND open_guard IS NULL)
    ),
    CONSTRAINT ck_change_request_slots CHECK (old_slot_id <> new_slot_id),
    CONSTRAINT ck_change_request_base_version CHECK (base_appointment_version >= 0),
    CONSTRAINT ck_change_request_reason CHECK (NULLIF(TRIM(reason), '') IS NOT NULL),
    CONSTRAINT ck_change_request_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_change_request_version CHECK (version >= 0),
    CONSTRAINT fk_change_request_appointment FOREIGN KEY (appointment_id) REFERENCES appointment (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_change_request_proposer FOREIGN KEY (proposer_user_id) REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_change_request_old_slot FOREIGN KEY (old_slot_id) REFERENCES doctor_slot (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_change_request_new_slot FOREIGN KEY (new_slot_id) REFERENCES doctor_slot (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_change_request_responder FOREIGN KEY (responder_user_id) REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE medical_record (
    id BIGINT NOT NULL,
    appointment_id BIGINT NOT NULL,
    pet_id BIGINT NOT NULL,
    doctor_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    content_json JSON NOT NULL,
    revision_no INT NOT NULL DEFAULT 0,
    submitted_at DATETIME(3) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_medical_record_appointment (appointment_id),
    KEY ix_medical_record_pet_submitted (pet_id, submitted_at),
    KEY ix_medical_record_doctor_submitted (doctor_id, submitted_at),
    CONSTRAINT ck_medical_record_status CHECK (status IN ('DRAFT', 'SUBMITTED')),
    CONSTRAINT ck_medical_record_submission CHECK (
        (status = 'DRAFT' AND revision_no = 0 AND submitted_at IS NULL)
        OR (status = 'SUBMITTED' AND revision_no >= 1 AND submitted_at IS NOT NULL)
    ),
    CONSTRAINT ck_medical_record_version CHECK (version >= 0),
    CONSTRAINT fk_medical_record_appointment FOREIGN KEY (appointment_id) REFERENCES appointment (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_medical_record_pet FOREIGN KEY (pet_id) REFERENCES pet (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_medical_record_doctor FOREIGN KEY (doctor_id) REFERENCES doctor_profile (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE medical_record_version (
    id BIGINT NOT NULL,
    record_id BIGINT NOT NULL,
    revision_no INT NOT NULL,
    before_content_json JSON NULL,
    after_content_json JSON NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_medical_record_version_revision (record_id, revision_no),
    UNIQUE KEY uk_medical_record_version_idempotency (record_id, idempotency_key),
    CONSTRAINT ck_medical_record_version_revision CHECK (revision_no >= 1),
    CONSTRAINT ck_medical_record_version_before CHECK (
        (revision_no = 1 AND before_content_json IS NULL)
        OR (revision_no > 1 AND before_content_json IS NOT NULL)
    ),
    CONSTRAINT ck_medical_record_version_reason CHECK (NULLIF(TRIM(reason), '') IS NOT NULL),
    CONSTRAINT fk_medical_record_version_record FOREIGN KEY (record_id) REFERENCES medical_record (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_medical_record_version_actor FOREIGN KEY (actor_user_id) REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
