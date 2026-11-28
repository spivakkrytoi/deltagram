ALTER TABLE investor_memberships ADD COLUMN login text;
UPDATE investor_memberships SET login = 'investor_' || user_id WHERE login IS NULL;
ALTER TABLE investor_memberships ALTER COLUMN login SET NOT NULL;
ALTER TABLE investor_memberships ADD CONSTRAINT investor_login_format CHECK (login ~ '^[a-zA-Z][a-zA-Z0-9_.-]{2,47}$');
CREATE UNIQUE INDEX investor_memberships_login_idx ON investor_memberships (lower(login));
