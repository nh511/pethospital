CREATE TABLE sys_user (
    id BIGINT NOT NULL,
    username VARCHAR(64) NULL,
    password_hash VARCHAR(255) NULL,
    wechat_openid_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    wechat_openid_cipher VARBINARY(512) NULL,
    display_name VARCHAR(64) NOT NULL,
    phone_cipher VARBINARY(512) NULL,
    phone_masked VARCHAR(32) NULL,
    avatar_file_id BIGINT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    token_version INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username),
    UNIQUE KEY uk_sys_user_wechat_openid_hash (wechat_openid_hash),
    KEY ix_sys_user_status_created (status, created_at),
    CONSTRAINT ck_sys_user_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_sys_user_token_version CHECK (token_version >= 0),
    CONSTRAINT ck_sys_user_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_role (
    id BIGINT NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_role_code (code),
    CONSTRAINT ck_sys_role_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_sys_role_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_menu (
    id BIGINT NOT NULL,
    parent_id BIGINT NULL,
    name VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    route_path VARCHAR(255) NULL,
    permission_code VARCHAR(128) NOT NULL,
    sort_no INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_menu_permission_code (permission_code),
    KEY ix_sys_menu_parent_sort (parent_id, sort_no),
    CONSTRAINT ck_sys_menu_kind CHECK (kind IN ('MENU', 'BUTTON')),
    CONSTRAINT ck_sys_menu_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_sys_menu_sort CHECK (sort_no >= 0),
    CONSTRAINT ck_sys_menu_version CHECK (version >= 0),
    CONSTRAINT ck_sys_menu_not_self_parent CHECK (parent_id IS NULL OR parent_id <> id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_user_role (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_role (user_id, role_id),
    KEY ix_sys_user_role_role (role_id),
    CONSTRAINT fk_sys_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_sys_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_role_menu (
    id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_role_menu (role_id, menu_id),
    KEY ix_sys_role_menu_menu (menu_id),
    CONSTRAINT fk_sys_role_menu_role FOREIGN KEY (role_id) REFERENCES sys_role (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_sys_role_menu_menu FOREIGN KEY (menu_id) REFERENCES sys_menu (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE auth_session (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    client_type VARCHAR(16) NOT NULL,
    refresh_token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    family_id VARCHAR(64) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    revoked_at DATETIME(3) NULL,
    last_seen_at DATETIME(3) NOT NULL,
    rotated_to_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_auth_session_refresh_token_hash (refresh_token_hash),
    KEY ix_auth_session_user_revoked (user_id, revoked_at),
    KEY ix_auth_session_expires (expires_at),
    KEY ix_auth_session_last_seen (last_seen_at),
    CONSTRAINT ck_auth_session_client_type CHECK (client_type IN ('MINI', 'WEB')),
    CONSTRAINT ck_auth_session_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_auth_session_revoked CHECK (revoked_at IS NULL OR revoked_at >= created_at),
    CONSTRAINT ck_auth_session_last_seen CHECK (last_seen_at >= created_at),
    CONSTRAINT fk_auth_session_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE file_object (
    id BIGINT NOT NULL,
    uploader_user_id BIGINT NOT NULL,
    object_key VARCHAR(512) COLLATE utf8mb4_0900_bin NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    validated_at DATETIME(3) NULL,
    deleted_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_object_key (object_key),
    KEY ix_file_object_uploader_created (uploader_user_id, created_at),
    KEY ix_file_object_status_created (status, created_at),
    CONSTRAINT ck_file_object_size CHECK (size_bytes >= 0),
    CONSTRAINT ck_file_object_purpose CHECK (purpose IN ('AVATAR', 'PET', 'HEALTH_LOG', 'MEDICAL', 'KNOWLEDGE', 'GUIDE', 'QUALIFICATION')),
    CONSTRAINT ck_file_object_status CHECK (status IN ('QUARANTINED', 'READY', 'REJECTED', 'DELETED')),
    CONSTRAINT fk_file_object_uploader FOREIGN KEY (uploader_user_id) REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE doctor_profile (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    doctor_name VARCHAR(64) NOT NULL,
    title VARCHAR(64) NULL,
    specialties VARCHAR(255) NULL,
    introduction TEXT NULL,
    qualification_file_id BIGINT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_doctor_profile_user (user_id),
    KEY ix_doctor_profile_status (status),
    CONSTRAINT ck_doctor_profile_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_doctor_profile_version CHECK (version >= 0),
    CONSTRAINT fk_doctor_profile_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_doctor_profile_qualification_file FOREIGN KEY (qualification_file_id) REFERENCES file_object (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE file_binding (
    id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    business_type VARCHAR(32) NOT NULL,
    business_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_binding_business (file_id, business_type, business_id),
    KEY ix_file_binding_business (business_type, business_id),
    KEY ix_file_binding_owner (owner_user_id),
    CONSTRAINT ck_file_binding_business_type CHECK (business_type IN ('PET', 'HEALTH_LOG', 'MEDICAL_DRAFT', 'MEDICAL_VERSION', 'KNOWLEDGE_VERSION', 'GUIDE', 'QUALIFICATION', 'USER_AVATAR')),
    CONSTRAINT fk_file_binding_file FOREIGN KEY (file_id) REFERENCES file_object (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_file_binding_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_file_binding_creator FOREIGN KEY (created_by) REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE audit_log (
    id BIGINT NOT NULL,
    actor_user_id BIGINT NULL,
    actor_type VARCHAR(16) NOT NULL,
    action VARCHAR(128) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id BIGINT NULL,
    before_json JSON NULL,
    after_json JSON NULL,
    reason VARCHAR(500) NULL,
    trace_id VARCHAR(64) NOT NULL,
    operation_key VARCHAR(64) NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    result VARCHAR(16) NOT NULL,
    ip_masked VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_audit_actor_operation (actor_user_id, operation_key),
    KEY ix_audit_resource_created (resource_type, resource_id, created_at),
    KEY ix_audit_actor_created (actor_user_id, created_at),
    KEY ix_audit_action_created_actor (action, created_at, actor_user_id),
    CONSTRAINT ck_audit_result CHECK (result IN ('SUCCESS', 'FAILURE')),
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_user_id) REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_notice (
    id BIGINT NOT NULL,
    recipient_user_id BIGINT NOT NULL,
    event_key VARCHAR(128) NOT NULL,
    notice_type VARCHAR(32) NOT NULL,
    title VARCHAR(128) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id BIGINT NOT NULL,
    read_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_notice_event (recipient_user_id, event_key),
    KEY ix_user_notice_unread_created (recipient_user_id, read_at, created_at),
    CONSTRAINT fk_user_notice_recipient FOREIGN KEY (recipient_user_id) REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE consent_record (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    policy_version VARCHAR(32) NOT NULL,
    action VARCHAR(16) NOT NULL,
    notice_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_consent_idempotency (user_id, idempotency_key),
    KEY ix_consent_user_purpose_created (user_id, purpose, created_at, id),
    CONSTRAINT ck_consent_purpose CHECK (purpose IN ('PRIVACY', 'AI_SERVICE')),
    CONSTRAINT ck_consent_action CHECK (action IN ('ACCEPT', 'WITHDRAW')),
    CONSTRAINT fk_consent_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE sys_user
    ADD CONSTRAINT fk_sys_user_avatar_file
    FOREIGN KEY (avatar_file_id) REFERENCES file_object (id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE sys_menu
    ADD CONSTRAINT fk_sys_menu_parent
    FOREIGN KEY (parent_id) REFERENCES sys_menu (id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE auth_session
    ADD CONSTRAINT fk_auth_session_rotated_to
    FOREIGN KEY (rotated_to_id) REFERENCES auth_session (id) ON UPDATE RESTRICT ON DELETE RESTRICT;
