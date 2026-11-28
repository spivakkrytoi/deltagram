DROP INDEX IF EXISTS investor_memberships_login_idx;
ALTER TABLE investor_memberships DROP CONSTRAINT IF EXISTS investor_login_format;
ALTER TABLE investor_memberships DROP COLUMN IF EXISTS login;
