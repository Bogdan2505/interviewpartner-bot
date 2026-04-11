-- Грейд слота на заявке; профиль users без language/level

ALTER TABLE interview_requests ADD COLUMN level VARCHAR(32);

UPDATE interview_requests ir
SET level = (SELECT u.level FROM users u WHERE u.id = ir.owner_user_id)
WHERE ir.partner_user_id IS NULL
  AND ir.status = 'PENDING';

ALTER TABLE users DROP COLUMN IF EXISTS language;
ALTER TABLE users DROP COLUMN IF EXISTS level;
