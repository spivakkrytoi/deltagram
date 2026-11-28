-- Reservations are committed BEFORE the admin command. Pending entries count
-- against a quota if the network dies after the command; uncertain outcomes
-- fail closed rather than granting twice.
CREATE TABLE investor_star_grants (
    user_id bigint NOT NULL REFERENCES investor_memberships(user_id) ON DELETE CASCADE,
    request_key text NOT NULL CHECK (request_key ~ '^[a-zA-Z0-9-]{16,64}$'),
    utc_day date NOT NULL,
    amount bigint NOT NULL CHECK (amount BETWEEN 1 AND 150000),
    status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','completed','failed')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id,request_key)
);
CREATE INDEX investor_star_grants_daily_idx ON investor_star_grants(user_id,utc_day) WHERE status <> 'failed';
CREATE TABLE investor_username_mints (
    user_id bigint NOT NULL REFERENCES investor_memberships(user_id) ON DELETE CASCADE,
    request_key text NOT NULL CHECK (request_key ~ '^[a-zA-Z0-9-]{16,64}$'),
    username_lower text NOT NULL CHECK (username_lower ~ '^[a-z][a-z0-9_]{3,31}$'),
    status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','completed','failed')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id,request_key)
);
CREATE UNIQUE INDEX investor_username_mints_pending_name_idx
    ON investor_username_mints(username_lower) WHERE status = 'pending';
