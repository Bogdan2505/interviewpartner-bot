-- Колонка partner_user_id переименована в applicant_user_id (заявитель / второй участник).
-- Имя ограничения FK в БД может остаться прежним — на JPA это не влияет.

ALTER TABLE interview_requests RENAME COLUMN partner_user_id TO applicant_user_id;
