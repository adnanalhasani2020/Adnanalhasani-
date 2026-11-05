# Commercial integer-quantity rollout checklist

## Current state

- PR #746 is merged into `main`.
- The migration is `20261105123000_commercial_integer_quantity_enforcement.sql`.
- The PostgreSQL 17 disposable-schema smoke workflow passes. It validates migration execution against a data-free fixture shaped from the current production column metadata; it is not a full production-schema or authenticated Supabase integration test.
- The production Supabase project has **not** applied this migration.
- Android cloud rollout gates for quantity RPCs and return-unit snapshot handling remain disabled. Do not enable them solely because CI passed.

## Why the rollout is gated

The production schema has migration-history drift relative to the repository's current migration directory. A separate empty validation project is not a valid substitute for a matching database. Applying this migration to production without a matching-schema rehearsal and authenticated integration tests would expose inventory, sales, purchasing, returns, and cost-layer workflows to avoidable risk.

## Required pre-deployment checks

1. Obtain a disposable Supabase database whose schema and migration history match production. Do not use a blank project or a schema inferred only from the repository's current migration folder.
2. Reconcile migration history and schema drift before testing. Record the exact production migration version and the exact fixture/rehearsal baseline.
3. Apply the migration to the disposable database with PostgreSQL 17.
4. Run the database release-gate pgTAP suite and authenticated integration tests with at least two distinct authenticated users.
5. Verify the following cases:
   - Whole-number quantities succeed for sales, purchases, receipts, inventory adjustments, customer orders, and returns.
   - Fractional quantities are rejected wherever the business contract requires integer stock units.
   - Global unit conversion factors remain fractional where required (for example, centimetres, milligrams, minutes, and seconds).
   - `NaN`, positive infinity, and negative infinity are rejected for constrained quantity columns.
   - A retry with the same idempotency key and identical selling-unit payload returns the original sale.
   - Reusing that key with a different selling-unit payload raises a conflict and never creates a second sale.
   - A pre-migration retry key is adopted only when its exact legacy projection matches.
   - The private sell-unit idempotency mapping cannot be read or modified directly by authenticated clients; only the trusted RPC helpers can access it.
   - Return quantities use the original invoice selling-unit snapshot and do not silently use a subsequently changed unit conversion.
6. Verify existing production rows before adding constraints. Resolve any historical fractional stock quantities through an explicit, audited reconciliation; never round or delete them automatically.
7. Capture test output, schema version, migration checksum, and reviewer approval in the release record.

## Deployment sequence

1. Take and verify a restorable production backup.
2. Apply the migration through the approved Supabase migration workflow during a controlled release window.
3. Re-run the database release-gate tests and authenticated smoke tests against the deployed schema.
4. Only after those checks pass, update the relevant Android release gate constants in `CommercialCloudReleaseGates.kt` and ship the app build.
5. Monitor failed RPCs, duplicate-sale conflicts, inventory balances, return postings, and accounting reconciliation immediately after rollout.

## Explicitly prohibited shortcuts

- Do not apply the migration to production merely to make the app gate green.
- Do not turn a release gate on based only on Android/Python CI or the disposable PostgreSQL fixture.
- Do not create a paid Supabase branch without explicit cost approval.
- Do not silently coerce fractional historical quantities to integers.
