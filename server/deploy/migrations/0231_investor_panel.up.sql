-- An investor is explicitly provisioned for a DeltaGram account. No admin
-- operator credentials or admin API bearer tokens are accepted by this portal.
CREATE TABLE IF NOT EXISTS investor_memberships (
    user_id bigint PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    password_hash text NOT NULL,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS investor_sessions (
    token_hash bytea PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES investor_memberships(user_id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS investor_sessions_expiry_idx ON investor_sessions(expires_at);
CREATE TABLE IF NOT EXISTS investor_requests (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES investor_memberships(user_id) ON DELETE CASCADE,
    kind text NOT NULL CHECK (kind IN ('username', 'phone', 'rating', 'prefix', 'verification', 'channel')),
    details text NOT NULL CHECK (char_length(details) BETWEEN 1 AND 300),
    status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected')),
    created_at timestamptz NOT NULL DEFAULT now(),
    reviewed_at timestamptz
);
CREATE INDEX IF NOT EXISTS investor_requests_user_idx ON investor_requests(user_id, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS investor_requests_one_active_kind_idx
ON investor_requests(user_id, kind) WHERE kind <> 'username' AND status IN ('pending', 'approved');
