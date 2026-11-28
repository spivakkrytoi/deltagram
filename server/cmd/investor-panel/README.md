# DeltaGram Investor

An independent, self-service **investor request portal**, not a reduced copy
of the operator console. Official offer:
https://t.me/dltagram/88 (5 November 2026).

Production landing page: https://dltagram.duckdns.org/investor/
The site can be viewed without a login; the personal section requires an
explicitly enrolled investor. A live, enrolled account is required before
the end-to-end login and request workflow can be verified in production.

## Boundaries

- Only explicitly enrolled investor logins may sign in. The admin-console password
  and bearer tokens never authenticate here.
- Sessions are random, stored only as SHA-256 hashes, limited to seven days,
  and checked against the current active membership on every request.
- A member can see only their own requests. They cannot select a beneficiary
  account or invoke the admin API.
- The portal **does not automatically grant** Stars, numbers, usernames,
  ratings, verification, prefixes or channel access. It accepts requests for
  manual review. Seven active NFT-name requests and one active request for
  each other category are allowed; this is a request limit, not proof of
  seven actual minted names.
- The 150,000 Stars/day allowance and actual ownership limits require a
  separate authoritative entitlement/ledger integration before automation.

## Enrolment

Apply migrations `0231_investor_panel.up.sql` and
`0232_investor_login.up.sql` through the existing migration system.
An operator with `admins.manage` can issue or rotate a login and a 12–72 byte
password in the **Admin → Operators → Investor access** section. The action
requires a reason, dry run and confirmation; no plaintext password is returned
or stored. The operator must verify the DeltaGram account ID and investor
eligibility before granting access, and pass the chosen password privately.
For emergency maintenance outside the console, a bcrypt hash can be generated
from stdin:

```sh
printf '%s' "$PASSWORD" | ./investor-panel -hash-password
```

Store the output in `investor_memberships(user_id,login,password_hash)` using
a parameterized query. Never copy a plaintext password to the database or
put one in a shell argument. Revocation: set `active=false`; existing
sessions immediately stop working. A password reset also deletes all sessions.
The portal reads `INVESTOR_POSTGRES_DSN`
and optionally `INVESTOR_LISTEN` (default `127.0.0.1:2802`), serving
`/investor/`. Reverse proxy it only through HTTPS. Review requests from the
operator side using restricted database access; only a trusted operator may
set `status` to `approved` or `rejected` after processing the real command.

The investor UI never receives a database password, admin session, or admin
API token. See `main_test.go` for origin, rate-limiting, and kind checks.
