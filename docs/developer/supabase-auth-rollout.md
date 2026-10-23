# Supabase Auth integration work — draft status

## Implemented in this branch

- Added `agent_core.supabase_auth.SupabaseAuthClient` for Supabase email/password sign-up and sign-in, server-side `/auth/v1/user` session verification, and sign-out.
- Uses only `SUPABASE_URL` and `SUPABASE_ANON_KEY`; it does not require or accept a service-role key.
- Avoids including credentials in URLs and avoids forwarding provider error bodies to callers.
- Added mocked unit tests for request shape, session verification, HTTPS configuration, and safe error handling.

## Not yet integrated

The existing `commercial_web` login and the catalog/checkout/receipt endpoints still use the local SQLite identity/session boundary. This branch intentionally does not switch the browser to Supabase Auth until the session cookie and commerce data paths can be migrated together. A Supabase-authenticated user must not be treated as a SQLite user, and a local SQLite session must not be accepted as a Supabase session.

## Required before this can be called complete

1. Wire email/password forms to Supabase Auth through server-side routes.
2. Set and clear Secure, HttpOnly, SameSite cookies for the Supabase session; verify the access token server-side on authenticated requests and implement refresh/expiry behavior.
3. Decide and implement the commerce data source for catalog, inventory, checkout, and receipts. The current endpoints use SQLite, while the Supabase schema has separate commercial tables and RPC functions.
4. Verify RLS/RPC tenant isolation and checkout idempotency with two distinct test users.
5. Run the full pytest suite and integration tests against a dedicated Supabase test project; record the exact commit and results.

## Validation status

The new tests are committed but have not been executed in this environment. No live sign-in was attempted and no Supabase database policies or production settings were changed.
