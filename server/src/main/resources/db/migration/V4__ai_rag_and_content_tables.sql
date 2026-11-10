CREATE TABLE ai_session (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    pet_id BIGINT NOT NULL,
    title VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    pet_snapshot_json JSON NOT NULL,
    summary_text TEXT NULL,
    summary_through_seq INT NOT NULL DEFAULT 0,
    summary_version INT NOT NULL DEFAULT 0,
    last_message_at DATETIME(3) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_ai_session_user_last_message (user_id, last_message_at, id),
    KEY ix_ai_session_pet_created (pet_id, created_at),
    CONSTRAINT ck_ai_session_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_ai_session_summary_seq CHECK (summary_through_seq >= 0),
    CONSTRAINT ck_ai_session_summary_version CHECK (summary_version >= 0),
    CONSTRAINT ck_ai_session_version CHECK (version >= 0),
    CONSTRAINT fk_ai_session_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ai_session_pet FOREIGN KEY (pet_id) REFERENCES pet (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_message (
    id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    seq_no INT NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    user_input_json JSON NULL,
    reply_to_id BIGINT NULL,
    source VARCHAR(16) NOT NULL,
    processing_status VARCHAR(24) NOT NULL,
    client_message_id VARCHAR(64) COLLATE utf8mb4_0900_bin NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    answer_json JSON NULL,
    risk_level VARCHAR(16) NULL,
    disclaimer VARCHAR(128) NULL,
    error_code VARCHAR(64) NULL,
    processing_token VARCHAR(64) COLLATE utf8mb4_0900_bin NULL,
    lease_until DATETIME(3) NULL,
    generation_no INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_message_session_seq (session_id, seq_no),
    UNIQUE KEY uk_ai_message_client_message (session_id, client_message_id),
    UNIQUE KEY uk_ai_message_reply_to (reply_to_id),
    KEY ix_ai_message_processing_lease (processing_status, lease_until),
    KEY ix_ai_message_session_role_created (session_id, role, created_at),
    CONSTRAINT ck_ai_message_seq CHECK (seq_no > 0),
    CONSTRAINT ck_ai_message_role CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT ck_ai_message_source CHECK (source IN ('USER', 'MODEL', 'RULE', 'FALLBACK')),
    CONSTRAINT ck_ai_message_processing_status CHECK (
        processing_status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED', 'FAILED_UNKNOWN')
    ),
    CONSTRAINT ck_ai_message_risk CHECK (
        risk_level IS NULL OR risk_level IN ('EMERGENCY', 'URGENT', 'ROUTINE', 'UNKNOWN')
    ),
    CONSTRAINT ck_ai_message_role_fields CHECK (
        (
            role = 'USER'
            AND source = 'USER'
            AND reply_to_id IS NULL
            AND client_message_id IS NOT NULL
            AND request_hash IS NOT NULL
            AND answer_json IS NULL
            AND disclaimer IS NULL
        )
        OR
        (
            role = 'ASSISTANT'
            AND source IN ('MODEL', 'RULE', 'FALLBACK')
            AND reply_to_id IS NOT NULL
            AND processing_status = 'COMPLETED'
            AND client_message_id IS NULL
            AND request_hash IS NULL
            AND user_input_json IS NULL
            AND disclaimer = 'AI 生成可能有误，请以线下诊断为主'
        )
    ),
    CONSTRAINT ck_ai_message_generation CHECK (generation_no >= 0),
    CONSTRAINT ck_ai_message_version CHECK (version >= 0),
    CONSTRAINT fk_ai_message_session FOREIGN KEY (session_id) REFERENCES ai_session (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ai_message_reply_to FOREIGN KEY (reply_to_id) REFERENCES ai_message (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_call_log (
    id BIGINT NOT NULL,
    session_id BIGINT NULL,
    user_message_id BIGINT NULL,
    call_kind VARCHAR(16) NOT NULL,
    business_ref VARCHAR(128) NOT NULL,
    generation_no INT NOT NULL DEFAULT 0,
    attempt_no INT NOT NULL DEFAULT 1,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    provider_request_id VARCHAR(128) NULL,
    status VARCHAR(24) NOT NULL,
    prompt_version VARCHAR(32) NULL,
    safety_version VARCHAR(32) NULL,
    input_snapshot_json JSON NULL,
    prompt_tokens INT NULL,
    completion_tokens INT NULL,
    cached_tokens INT NULL,
    latency_ms BIGINT NULL,
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_call_business_attempt (call_kind, business_ref, generation_no, attempt_no),
    KEY ix_ai_call_status_started (status, started_at),
    KEY ix_ai_call_model_started (model, started_at),
    KEY ix_ai_call_user_message (user_message_id),
    CONSTRAINT ck_ai_call_kind CHECK (call_kind IN ('GENERATION', 'SUMMARY', 'EMBEDDING')),
    CONSTRAINT ck_ai_call_status CHECK (status IN ('CALLING', 'COMPLETED', 'FAILED', 'FAILED_UNKNOWN')),
    CONSTRAINT ck_ai_call_generation CHECK (generation_no >= 0),
    CONSTRAINT ck_ai_call_attempt CHECK (attempt_no >= 1),
    CONSTRAINT ck_ai_call_prompt_tokens CHECK (prompt_tokens IS NULL OR prompt_tokens >= 0),
    CONSTRAINT ck_ai_call_completion_tokens CHECK (completion_tokens IS NULL OR completion_tokens >= 0),
    CONSTRAINT ck_ai_call_cached_tokens CHECK (cached_tokens IS NULL OR cached_tokens >= 0),
    CONSTRAINT ck_ai_call_latency CHECK (latency_ms IS NULL OR latency_ms >= 0),
    CONSTRAINT ck_ai_call_times CHECK (
        finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at
    ),
    CONSTRAINT fk_ai_call_session FOREIGN KEY (session_id) REFERENCES ai_session (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ai_call_user_message FOREIGN KEY (user_message_id) REFERENCES ai_message (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE triage_rule (
    id BIGINT NOT NULL,
    rule_code VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    revision_no INT NOT NULL,
    species_code VARCHAR(32) NOT NULL,
    title VARCHAR(128) NOT NULL,
    predicate_json JSON NOT NULL,
    risk_level VARCHAR(16) NOT NULL,
    fixed_advice TEXT NOT NULL,
    source_url VARCHAR(1000) NOT NULL,
    review_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    enabled TINYINT NOT NULL DEFAULT 0,
    creator_user_id BIGINT NOT NULL,
    reviewer_user_id BIGINT NULL,
    review_note VARCHAR(500) NULL,
    reviewed_at DATETIME(3) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_triage_rule_revision (rule_code, revision_no),
    KEY ix_triage_rule_enabled_review_species (enabled, review_status, species_code),
    CONSTRAINT ck_triage_rule_revision CHECK (revision_no >= 1),
    CONSTRAINT ck_triage_rule_risk CHECK (risk_level IN ('EMERGENCY', 'URGENT', 'ROUTINE', 'UNKNOWN')),
    CONSTRAINT ck_triage_rule_review_status CHECK (review_status IN ('DRAFT', 'IN_REVIEW', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_triage_rule_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT ck_triage_rule_review_fields CHECK (
        review_status NOT IN ('APPROVED', 'REJECTED')
        OR (reviewer_user_id IS NOT NULL AND reviewed_at IS NOT NULL)
    ),
    CONSTRAINT ck_triage_rule_enable_fields CHECK (
        enabled = 0
        OR (review_status = 'APPROVED' AND reviewer_user_id IS NOT NULL AND reviewed_at IS NOT NULL)
    ),
    CONSTRAINT ck_triage_rule_version CHECK (version >= 0),
    CONSTRAINT fk_triage_rule_creator FOREIGN KEY (creator_user_id) REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_triage_rule_reviewer FOREIGN KEY (reviewer_user_id) REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE triage_event (
    id BIGINT NOT NULL,
    user_message_id BIGINT NOT NULL,
    source VARCHAR(16) NOT NULL,
    rule_id BIGINT NULL,
    generation_no INT NOT NULL DEFAULT 0,
    symptom_codes_json JSON NOT NULL,
    evidence_json JSON NOT NULL,
    risk_level VARCHAR(16) NOT NULL,
    final_risk_level VARCHAR(16) NOT NULL,
    rule_version_snapshot VARCHAR(64) NULL,
    decision_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_triage_event_message_generation (user_message_id, generation_no),
    KEY ix_triage_event_final_risk_created (final_risk_level, created_at),
    CONSTRAINT ck_triage_event_source CHECK (source IN ('RULE', 'MODEL')),
    CONSTRAINT ck_triage_event_source_rule CHECK (
        (source = 'RULE' AND rule_id IS NOT NULL)
        OR (source = 'MODEL' AND rule_id IS NULL)
    ),
    CONSTRAINT ck_triage_event_generation CHECK (generation_no >= 0),
    CONSTRAINT ck_triage_event_risk CHECK (
        risk_level IN ('EMERGENCY', 'URGENT', 'ROUTINE', 'UNKNOWN')
        AND final_risk_level IN ('EMERGENCY', 'URGENT', 'ROUTINE', 'UNKNOWN')
    ),
    CONSTRAINT ck_triage_event_no_risk_downgrade CHECK (
        CASE final_risk_level
            WHEN 'EMERGENCY' THEN 3 WHEN 'URGENT' THEN 2 WHEN 'ROUTINE' THEN 1 ELSE 0
        END
        >=
        CASE risk_level
            WHEN 'EMERGENCY' THEN 3 WHEN 'URGENT' THEN 2 WHEN 'ROUTINE' THEN 1 ELSE 0
        END
    ),
    CONSTRAINT fk_triage_event_user_message FOREIGN KEY (user_message_id) REFERENCES ai_message (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_triage_event_rule FOREIGN KEY (rule_id) REFERENCES triage_rule (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_document (
    id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    species_code VARCHAR(32) NOT NULL,
    category_code VARCHAR(64) NOT NULL,
    tags_json JSON NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    active_version_id BIGINT NULL,
    creator_user_id BIGINT NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_knowledge_document_status_species_category (status, species_code, category_code),
    KEY ix_knowledge_document_active_version (active_version_id),
    CONSTRAINT ck_knowledge_document_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'OFFLINE')),
    CONSTRAINT ck_knowledge_document_publish CHECK (status <> 'PUBLISHED' OR active_version_id IS NOT NULL),
    CONSTRAINT ck_knowledge_document_version CHECK (version >= 0),
    CONSTRAINT fk_knowledge_document_creator FOREIGN KEY (creator_user_id) REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_version (
    id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    content LONGTEXT NOT NULL,
    source_title VARCHAR(255) NOT NULL,
    source_url VARCHAR(1000) NULL,
    source_file_id BIGINT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    review_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    reviewer_user_id BIGINT NULL,
    review_note VARCHAR(500) NULL,
    reviewed_at DATETIME(3) NULL,
    published_at DATETIME(3) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_version_number (document_id, version_no),
    CONSTRAINT ck_knowledge_version_number CHECK (version_no >= 1),
    CONSTRAINT ck_knowledge_version_source CHECK (
        NULLIF(TRIM(source_url), '') IS NOT NULL OR source_file_id IS NOT NULL
    ),
    CONSTRAINT ck_knowledge_version_review_status CHECK (
        review_status IN ('DRAFT', 'IN_REVIEW', 'APPROVED', 'REJECTED')
    ),
    CONSTRAINT ck_knowledge_version_review_fields CHECK (
        review_status NOT IN ('APPROVED', 'REJECTED')
        OR (reviewer_user_id IS NOT NULL AND reviewed_at IS NOT NULL)
    ),
    CONSTRAINT ck_knowledge_version_published CHECK (
        published_at IS NULL OR review_status = 'APPROVED'
    ),
    CONSTRAINT ck_knowledge_version_version CHECK (version >= 0),
    CONSTRAINT fk_knowledge_version_document FOREIGN KEY (document_id) REFERENCES knowledge_document (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_version_source_file FOREIGN KEY (source_file_id) REFERENCES file_object (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_version_reviewer FOREIGN KEY (reviewer_user_id) REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_chunk (
    id BIGINT NOT NULL,
    knowledge_version_id BIGINT NOT NULL,
    ordinal INT NOT NULL,
    content TEXT NOT NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    token_estimate INT NOT NULL,
    splitter_version VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_chunk_ordinal (knowledge_version_id, ordinal),
    CONSTRAINT ck_knowledge_chunk_ordinal CHECK (ordinal >= 0),
    CONSTRAINT ck_knowledge_chunk_tokens CHECK (token_estimate > 0 AND token_estimate <= 512),
    CONSTRAINT fk_knowledge_chunk_version FOREIGN KEY (knowledge_version_id) REFERENCES knowledge_version (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_index_job (
    id BIGINT NOT NULL,
    knowledge_version_id BIGINT NOT NULL,
    embedding_profile VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    embedding_model VARCHAR(128) NOT NULL,
    dimensions INT NOT NULL,
    distance_metric VARCHAR(16) NOT NULL,
    splitter_version VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    active_guard TINYINT NULL,
    indexed_chunks INT NOT NULL DEFAULT 0,
    expected_chunks INT NOT NULL DEFAULT 0,
    attempt_no INT NOT NULL DEFAULT 1,
    lease_until DATETIME(3) NULL,
    worker_token VARCHAR(64) COLLATE utf8mb4_0900_bin NULL,
    error_code VARCHAR(64) NULL,
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    creator_user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_index_active (knowledge_version_id, embedding_profile, active_guard),
    UNIQUE KEY uk_knowledge_index_idempotency (creator_user_id, idempotency_key),
    KEY ix_knowledge_index_status_lease (status, lease_until),
    CONSTRAINT ck_knowledge_index_dimensions CHECK (dimensions > 0),
    CONSTRAINT ck_knowledge_index_distance CHECK (distance_metric IN ('COSINE', 'DOT', 'EUCLID')),
    CONSTRAINT ck_knowledge_index_status CHECK (status IN ('QUEUED', 'RUNNING', 'READY', 'FAILED')),
    CONSTRAINT ck_knowledge_index_guard CHECK (
        (status IN ('QUEUED', 'RUNNING') AND active_guard IS NOT NULL AND active_guard = 1)
        OR (status IN ('READY', 'FAILED') AND active_guard IS NULL)
    ),
    CONSTRAINT ck_knowledge_index_counts CHECK (
        indexed_chunks >= 0 AND expected_chunks >= 0 AND indexed_chunks <= expected_chunks
    ),
    CONSTRAINT ck_knowledge_index_attempt CHECK (attempt_no >= 1),
    CONSTRAINT ck_knowledge_index_running CHECK (
        status <> 'RUNNING' OR (lease_until IS NOT NULL AND worker_token IS NOT NULL)
    ),
    CONSTRAINT ck_knowledge_index_ready CHECK (
        status <> 'READY' OR (expected_chunks > 0 AND indexed_chunks = expected_chunks)
    ),
    CONSTRAINT ck_knowledge_index_times CHECK (
        finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at
    ),
    CONSTRAINT fk_knowledge_index_version FOREIGN KEY (knowledge_version_id) REFERENCES knowledge_version (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_index_creator FOREIGN KEY (creator_user_id) REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_reference (
    id BIGINT NOT NULL,
    assistant_message_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    knowledge_version_id BIGINT NOT NULL,
    chunk_id BIGINT NOT NULL,
    rank_no INT NOT NULL,
    score DECIMAL(10,6) NOT NULL,
    title_snapshot VARCHAR(255) NOT NULL,
    source_snapshot VARCHAR(1000) NULL,
    content_snapshot TEXT NOT NULL,
    embedding_profile VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_reference_message_rank (assistant_message_id, rank_no),
    KEY ix_ai_reference_chunk (chunk_id),
    CONSTRAINT ck_ai_reference_rank CHECK (rank_no > 0),
    CONSTRAINT fk_ai_reference_message FOREIGN KEY (assistant_message_id) REFERENCES ai_message (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ai_reference_document FOREIGN KEY (document_id) REFERENCES knowledge_document (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ai_reference_version FOREIGN KEY (knowledge_version_id) REFERENCES knowledge_version (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ai_reference_chunk FOREIGN KEY (chunk_id) REFERENCES knowledge_chunk (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE health_guide (
    id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    species_code VARCHAR(32) NOT NULL,
    category_code VARCHAR(64) NOT NULL,
    body LONGTEXT NOT NULL,
    cover_file_id BIGINT NULL,
    source_note VARCHAR(1000) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    published_at DATETIME(3) NULL,
    editor_user_id BIGINT NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_health_guide_status_published (status, published_at),
    KEY ix_health_guide_species_category (species_code, category_code),
    CONSTRAINT ck_health_guide_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'OFFLINE')),
    CONSTRAINT ck_health_guide_publish CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL),
    CONSTRAINT ck_health_guide_version CHECK (version >= 0),
    CONSTRAINT fk_health_guide_cover_file FOREIGN KEY (cover_file_id) REFERENCES file_object (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_health_guide_editor FOREIGN KEY (editor_user_id) REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE health_standard (
    id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    species_code VARCHAR(32) NOT NULL,
    age_min_months INT NULL,
    age_max_months INT NULL,
    metric_code VARCHAR(64) NOT NULL,
    unit VARCHAR(32) NULL,
    reference_min DECIMAL(12,4) NULL,
    reference_max DECIMAL(12,4) NULL,
    description TEXT NOT NULL,
    conditions_note TEXT NOT NULL,
    source_note VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    editor_doctor_id BIGINT NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_health_standard_status_species_metric (status, species_code, metric_code),
    CONSTRAINT ck_health_standard_age_min CHECK (age_min_months IS NULL OR age_min_months >= 0),
    CONSTRAINT ck_health_standard_age_max CHECK (age_max_months IS NULL OR age_max_months >= 0),
    CONSTRAINT ck_health_standard_age_range CHECK (
        age_min_months IS NULL OR age_max_months IS NULL OR age_min_months <= age_max_months
    ),
    CONSTRAINT ck_health_standard_reference CHECK (
        (reference_min IS NULL AND reference_max IS NULL)
        OR (reference_min IS NOT NULL AND reference_max IS NOT NULL AND reference_min <= reference_max)
    ),
    CONSTRAINT ck_health_standard_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'OFFLINE')),
    CONSTRAINT ck_health_standard_version CHECK (version >= 0),
    CONSTRAINT fk_health_standard_editor FOREIGN KEY (editor_doctor_id) REFERENCES doctor_profile (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE guide_view_event (
    id BIGINT NOT NULL,
    guide_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    view_date DATE NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_guide_view_daily (guide_id, user_id, view_date),
    KEY ix_guide_view_date_guide (view_date, guide_id),
    CONSTRAINT fk_guide_view_guide FOREIGN KEY (guide_id) REFERENCES health_guide (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_guide_view_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
