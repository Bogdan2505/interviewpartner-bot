-- Владелец слота + опциональный партнёр вместо candidate_id / interviewer_id

ALTER TABLE interview_requests ADD COLUMN owner_user_id BIGINT;
ALTER TABLE interview_requests ADD COLUMN partner_user_id BIGINT;

UPDATE interview_requests
SET owner_user_id   = CASE WHEN candidate_id = interviewer_id THEN candidate_id ELSE interviewer_id END,
    partner_user_id = CASE WHEN candidate_id = interviewer_id THEN NULL ELSE candidate_id END;

ALTER TABLE interview_requests ALTER COLUMN owner_user_id SET NOT NULL;

ALTER TABLE interview_requests DROP CONSTRAINT fk_interview_requests_candidate;
ALTER TABLE interview_requests DROP CONSTRAINT fk_interview_requests_interviewer;

ALTER TABLE interview_requests DROP COLUMN candidate_id;
ALTER TABLE interview_requests DROP COLUMN interviewer_id;

ALTER TABLE interview_requests
    ADD CONSTRAINT fk_interview_requests_owner FOREIGN KEY (owner_user_id) REFERENCES users (id);
ALTER TABLE interview_requests
    ADD CONSTRAINT fk_interview_requests_partner FOREIGN KEY (partner_user_id) REFERENCES users (id);
