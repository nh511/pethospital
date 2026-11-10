ALTER TABLE appointment
    ADD CONSTRAINT fk_appointment_ai_session
    FOREIGN KEY (ai_session_id) REFERENCES ai_session (id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE knowledge_document
    ADD CONSTRAINT fk_knowledge_document_active_version
    FOREIGN KEY (active_version_id) REFERENCES knowledge_version (id) ON UPDATE RESTRICT ON DELETE RESTRICT;
