# Stage 14 — Commerce Persistence Evidence Report

**Baseline:** `bbc60451d20aade3f77177b7e6f52583adc47a34`  
**Status:** Evidence collection in progress; no Requirement is declared ACCEPTED by this report.  
**Scope:** Existing schema/migrations only. No schema expansion, domain concepts, API/UI, search/GIS/ranking, payment/settlement/financial transaction/ledger implementation, Stage 12B, tags, or releases.

## Requirement evidence matrix

| Requirement | Specification | Domain behavior evidence | Persistence behavior evidence | Test(s) | Acceptance evidence / disposition |
|---|---|---|---|---|---|
| REQ-DATA-0013 | SPEC-0005 | Stage 13 distinguishes Product and Offering; Offering retains Activity context | Product and Offering are separate rows with independent IDs and Product/Activity FKs | `test_product_offering_and_inventory_position_round_trip_independently` | Pending CI and governance review |
| REQ-DATA-0014 | SPEC-0005, SPEC-0006 | Offering does not imply Inventory Position | Separate Inventory Position row references Activity, Product, and Offering; FK check | `test_product_offering_and_inventory_position_round_trip_independently` | Pending CI and governance review |
| REQ-FUNC-0015 | SPEC-0006 | Inventory Position is the inventory state record | State, quantity, scope/location, observed_at, effective_from/to round-trip | `test_product_offering_and_inventory_position_round_trip_independently` | Evidence only; broader research-dependent policy remains excluded |
| REQ-FUNC-0016 | SPEC-0006, SPEC-0014 | Availability is derived and is not Inventory truth | Existing schema has no Availability authority table; inventory persists independently | `test_inventory_position_does_not_require_availability_or_discovery_record` | Boundary evidence only; temporal/proximity policy not accepted |
| REQ-FUNC-0033 | SPEC-0014 | Discovery consumes existing Offering/Availability context; no search implementation | Persistence contains source Commerce/Inventory facts, not a Discovery-owned truth table | `test_inventory_position_does_not_require_availability_or_discovery_record` | Boundary evidence only; no search/GIS/ranking implementation |
| REQ-FUNC-0034 | SPEC-0014, SPEC-0006 | Proximity and Availability are distinct | No proximity field/threshold is added; existing location reference remains a reference only | `test_inventory_position_does_not_require_availability_or_discovery_record` | No threshold invented; semantic acceptance remains bounded by specification |
| REQ-FUNC-0035 | SPEC-0014, SPEC-0006 | Existing temporal fields are persisted without inventing freshness policy | observed_at/effective_from/effective_to round-trip as stored | `test_product_offering_and_inventory_position_round_trip_independently` | Exact freshness semantics remain research-dependent and unaccepted |
| REQ-FUNC-0037 | SPEC-0005; supporting SPEC-0008/SPEC-0009 | Domain model currently uses initiated/confirmed/completed/cancelled/returned; SPEC-0005 uses fulfilled | Existing sales state constraint accepts completed but not fulfilled; state and domain_history records can persist | `test_sale_round_trip_preserves_activity_offering_and_current_state_without_finance_aliases`, `test_sale_state_constraint_rejects_unapproved_fulfilled_vocabulary`, `test_sale_lifecycle_state_and_history_can_be_persisted_without_semantic_equivalence_claim` | **BLOCKED for terminal-state semantic acceptance** pending authoritative clarification; no synonym assumed |
| REQ-FUNC-0038 | SPEC-0005, SPEC-0003, SPEC-0015 | Sale retains Activity context; Authorization and Finance remain separate owners | Sale persists offering/activity references; no Payment/Settlement/Financial Transaction/Ledger FK aliases | `test_sale_round_trip_preserves_activity_offering_and_current_state_without_finance_aliases` | Pending CI and governance review; no cross-domain implementation |

## Execution record

The tests above are added for execution by repository CI. No local test result is claimed here. The PR's latest GitHub Actions run must be checked against the final PR HEAD before review readiness is stated.

## Explicit semantic blocker

SPEC-0005 uses `fulfilled`; current domain and relational constraint use `completed`. These terms are not treated as equivalent. No domain, schema, migration, or requirement semantics are changed to bypass this conflict. The persistence tests characterize current behavior only. Requirement-level terminal-state acceptance remains withheld pending an authoritative clarification.

## Scope assertions

- No migration or schema file changed.
- No domain implementation changed.
- No Availability, Search, GIS, Ranking, proximity threshold, or freshness threshold added.
- No Payment, Settlement, Financial Transaction, or Ledger implementation added.
- No Stage 12B work performed.
- No tag or release changed.
