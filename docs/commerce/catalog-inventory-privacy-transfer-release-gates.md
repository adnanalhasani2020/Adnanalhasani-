# Catalog and transfer implementation gates

The catalog screen now exposes branch/warehouse selection, configures selected items using only units and conversion factors already defined in the global catalog, and offers radius-expanding nearby discovery plus cohort-gated price suggestions. Market discovery uses saved branch coordinates rather than silently tracking the device.

The database requires explicit offering-to-global-item mappings; no fuzzy name joins are used. Price suggestions return only median/interquartile aggregates and cohort bands when five distinct other organizations contribute. Nearby popularity uses completed sales from the last 30 days, radius filters, pagination and item deduplication. Transfer requests are idempotent; only a destination owner/admin/manager can approve/reject. Approval creates a draft purchase order and supplier relation; stock remains unchanged until receipt.

Still not production-complete:
- Historical offerings need explicit identity mapping; they are not guessed from names.
- Android seller/buyer invoice-transfer request and approval inbox screens still need to be connected to the transfer RPCs.
- FIFO/LIFO/weighted-average is not enabled. Current checkout cost capture uses existing balance cost and sale-item cost snapshots. A safe costing engine requires receipt cost layers, deterministic issue allocations, return reversals, transfer handling, idempotency and GL reconciliation before changing COGS.
- Phone OTP requires the project's configured Supabase Auth SMS provider and Android registration/recovery integration. A regular Android app cannot guarantee daily SIM ownership without carrier/platform support.
- Full role-by-role operation audit and authenticated tests across two organizations remain release gates.
- These migrations are not applied to live Supabase and must be staged before production.
