-- Заявка описывает только открытый слот владельца; второй участник — в таблице interviews.
ALTER TABLE interview_requests DROP CONSTRAINT IF EXISTS fk_interview_requests_partner;
ALTER TABLE interview_requests DROP COLUMN IF EXISTS candidate_id;
