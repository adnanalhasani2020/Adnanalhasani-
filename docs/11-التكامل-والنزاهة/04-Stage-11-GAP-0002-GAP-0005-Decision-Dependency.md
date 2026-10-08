# Stage 11 — GAP-0002 / GAP-0005 Decision Dependency

**Status:** RESOLVED BY DEC-ST11-0002 + DEC-ST11-0003
**Stage 11 Gate:** d3be7907e34dd75b13de7aa14676d328ab45203e
**Prior dependency commit:** e354ddf71c2408e374a144bb4d926e43684b44f3

## 1. Resolution

The implementation blockers identified in the prior dependency record are now resolved at the design-contract level only.

### GAP-0002
Resolved by DEC-ST11-0002 — GAP-0002 Semantic Integrity Contract:
- authoritative semantic source = owning domain's Source of Truth;
- semantic reference resolution occurs through a runtime Semantic Authority contract;
- missing entities are deterministically rejected;
- owner mismatch is deterministically rejected;
- provenance is enforced only where the governed operation specification requires it;
- enforcement occurs before semantic effect at a single application runtime boundary;
- UUID/type validity is not semantic truth.

### GAP-0005
Resolved by DEC-ST11-0003 — GAP-0005 Durable Operation Integrity Contract:
- durable operation state is authoritative for operation identity/replay/conflict;
- operation key = namespace + operation_id;
- request fingerprint distinguishes same-identity replay from conflicting reuse;
- durable state survives process/runtime restart;
- exact repeats are idempotent and return the stored deterministic outcome;
- conflicting reuse is rejected and cannot overwrite the original record;
- enforcement occurs before semantic effect at a single application runtime boundary.

## 2. Remaining Architecture / Data Model boundaries

These are not implementation approvals and remain bounded:

1. A future implementation must define the concrete repository/storage interface for Semantic Authority without creating a second domain Source of Truth.
2. If provenance-required operations need a new durable relation not represented by current domain concepts, that relation requires a separate Architecture/Data Model decision before implementation.
3. GAP-0005 requires a first-class durable operation-integrity record and a uniqueness/atomicity rule. The decision approves the semantic concept, not a schema, database, migration, or transaction technology.
4. No storage technology is selected by these decisions.
5. Any change to domain ownership, cross-domain truth authority, or existing Architecture principles requires a new Decision Dependency.

## 3. Status

**GAP-0002: READY FOR IMPLEMENTATION**
**GAP-0005: READY FOR IMPLEMENTATION**

This resolution does not start implementation, does not change src/, tests/, main, v1.0.0, or release state, and does not authorize GAP-0006.
