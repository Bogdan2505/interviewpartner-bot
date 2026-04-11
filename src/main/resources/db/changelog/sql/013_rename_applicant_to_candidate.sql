-- Убираем имя applicant_user_id: колонка становится candidate_id (кандидат на прямую заявку; у открытого solo NULL).

ALTER TABLE interview_requests RENAME COLUMN applicant_user_id TO candidate_id;
