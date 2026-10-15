# Product Units and Conversion — Contract Proposal (Not Approved)

**Status:** Baseline unit-configuration decisions approved for implementation; transaction and inventory integration decisions remain open.  
**Audit baseline:** `main @ 44d37ff770892404d5c0ba54e67a3171857b68c5` (2026-10-14).  
**Purpose:** Define the domain contract needed to implement product units, unit settings, and safe links to commerce and inventory without duplicating existing concepts or guessing conversions.

## Decisions confirmed for the first implementation slice

The user explicitly approved the following baseline for the product-unit configuration slice:

- Use a shared measurement-unit catalog and explicit per-Product configuration; do not create duplicate Products to represent packaging.
- A configured Product must have one active base unit before additional allowed units can be configured. Products with no unit metadata remain valid and unknown; legacy units are never inferred or backfilled.
- Each unit has an explicit dimension. Conversions across different dimensions are rejected.
- Conversion factors are positive exact rational values (numerator/denominator), expressed as base units per one configured unit. Binary floating-point quantities and factors are rejected; persisted factors must fit SQLite's signed 64-bit integer range.
- Unit-setting edits use optimistic versions and atomic Domain History. Referenced units are retired rather than deleted; base units cannot be retired while allowed units remain active.
- This slice provides catalog/configuration and exact conversion only. It does not post inventory, record receipt effects, change purchase quantities, or create financial effects.

These decisions supersede the corresponding open questions in Section 3 only to the extent explicitly stated above. They do **not** approve assumptions about packaging contents, fractional quantity persistence, rounding, price-per-unit semantics, historical transaction snapshots, inventory posting, authorization policy, or user-interface/API integration. Those require a separate explicit contract before integration.

## 1. Main-first repository audit

The following were checked on `main` before preparing this proposal:

- `src/agent_core/domain_inventory.py`: Product, Offering, and InventoryPosition domain models. Product currently has a name, ID, and lifecycle state; no unit or conversion model exists.
- `src/agent_core/application.py`: Product persistence/lifecycle and current commerce application entry points.
- `src/agent_core/offering_application.py`: Offering lifecycle; Offering references Product and Activity.
- `src/agent_core/inventory_read.py`, `src/agent_core/commerce_inventory_read.py`: inventory read paths and their boundaries.
- `schema/migrations/0001_initial_relational_schema.sql`: Product, Offering, InventoryPosition, and Sale relational structures. InventoryPosition has nullable integer `quantity_minor`; this field must not be assumed to mean a physical measurement unit or a currency amount.
- `schema/migrations/0006_inventory_quantity_integer.sql`: protects integer storage for the existing inventory quantity representation.
- `schema/migrations/0007_product_name.sql`: existing additive Product migration.
- `docs/06-المواصفات/SPEC-0005-المنتج-والعرض-والبيع.md` and `SPEC-0006-المخزون-والتوافر.md`: Product, Offering, InventoryPosition, and Availability are distinct concepts with separate ownership.
- `tests/test_stage22_product_application.py` and the Stage 21/19 inventory tests: current persistence, lifecycle, and integer-quantity behavior.

### Existing work and duplicate check

- Open PR #196, `feature/product-edit-history`, adds audited Product name updates and Product listing. Its description explicitly says units and conversion rules remain unimplemented pending an explicit domain contract. It does not provide a unit model.
- Open PR #192, `feature/purchases-domain-v1`, adds procurement and supplier-invoice paths. Its line quantities make unit semantics a dependency, but its receipt evidence does not automatically post inventory. Review its full current diff before integrating any unit work.
- No unit-named branch was returned by the branch search, and no dedicated unit implementation PR was found in the inspected PR search.
- No runtime code, schema migration, or unit model is added by this proposal.

## 2. Ownership and integration boundaries

The intended boundaries to confirm are:

- **Product** identifies the general product. A unit selection must not create a duplicate Product.
- **Product-unit configuration** should define which units are valid for a product and how quantities can be converted, if conversion is explicitly permitted.
- **Offering** remains the Activity-specific offer; it must not become the owner of global unit definitions. An Offering may need a permitted sale unit and a historical snapshot of the chosen unit.
- **InventoryPosition** remains the owner of inventory quantity truth. Unit support must not turn a read into a stock reservation, receipt, deduction, or correction.
- **Purchase / supplier invoice** remain separate from Sale and Inventory. Unit-aware receipt quantities must not change inventory without an explicitly specified Inventory-owned operation.
- **Sale / invoice** may need a unit and quantity snapshot on a line, but current Sale is not a general order-line model. Do not add sale lines or silently infer them as part of this proposal.
- **Financial amounts** and their currency minor units are not physical product units and must remain distinct.

## 3. Decisions required before runtime implementation

These are open design decisions, not safe defaults to guess:

1. **Unit catalog scope:** Are units globally defined and then enabled per Product, or may an Activity define its own unit labels? If labels are local, how is semantic identity distinguished from display text?
2. **Unit dimensions:** Which dimensions are in scope initially (count, mass, volume, length, area, time, or other)? Units from different dimensions must never convert to each other.
3. **Base/stock unit:** Does each stock-tracked Product have exactly one canonical inventory unit? Can a Product be non-stock/service-only and have sale units without an inventory unit?
4. **Conversion model:** Are conversions exact rational factors (numerator/denominator), decimal factors, or explicit package mappings? Must conversions be reversible? How are non-terminating or inexact conversions handled?
5. **Fractional quantities and precision:** Which quantities may be fractional, what scale/rounding policy applies, and who approves rounding? Existing integer `quantity_minor` constraints cannot simply be relaxed without a migration and compatibility plan.
6. **Product packaging:** Are units such as box/carton/package defined as conversions to a base unit, and may the contents of a package change over time? If so, which version applies to historical transactions?
7. **Transaction snapshots:** Should Purchase lines, supplier-invoice lines, Sale lines, and Invoice lines persist the selected unit, entered quantity, conversion version/factor, and normalized quantity so later configuration edits cannot rewrite history?
8. **Price semantics:** Is price expressed per selected unit, and if so, how should price-per-unit and converted quantities interact with integer currency minor units, taxes, and rounding? Do not infer taxes/discounts here.
9. **Inventory linkage:** Is InventoryPosition quantity always stored in a Product's canonical stock unit, with all receipts/issues converted at the boundary? What happens to legacy positions with unknown or ambiguous units?
10. **Configuration lifecycle:** Can enabled units and conversion rules be edited or retired after use? Historical records should remain interpretable; deletion must not erase referenced history.
11. **Authorization and audit:** Which existing authorization contract governs unit-setting changes, and what domain-history payload/version checks are required? Unit settings must not grant new authority.
12. **Legacy migration:** How should existing products and inventory positions with no unit metadata be represented? Do not invent a unit or backfill an assumed “piece” value.

## 4. Safe implementation sequence after decisions

1. Approve the unit semantics and compatibility rules.
2. Add domain validation and tests for dimensions, unit identity, conversion arithmetic, invalid factors, precision, and historical snapshots.
3. Add an additive migration that preserves legacy unknown-unit state without fabricating values.
4. Add persisted unit-configuration application paths using existing transaction, version, and history patterns.
5. Integrate Product settings first, then inventory canonical-unit reads/writes, and then Purchase/Supplier Invoice and any future Sale/Invoice line models under their owners.
6. Run the full test suite and migration/legacy tests; inspect PR #192 and #196 against the exact updated main before merging anything.
7. Keep all integration changes on a branch/PR until reviewed; do not write directly to `main`.

## 5. Acceptance criteria

- No duplicate Product, unit, conversion, purchase, sale, or inventory model is introduced.
- No conversion factor, base unit, rounding policy, or legacy unit is guessed.
- Cross-dimension conversion is rejected.
- Invalid or stale unit configuration fails before any partial write.
- Historical transactions remain interpretable after a unit setting is changed or retired.
- Inventory remains the source of truth for quantities; configuration and transaction records do not silently mutate stock.
- Existing integer quantity protections and all existing tests remain valid unless a separately approved migration explicitly changes them.
- Unit settings changes are versioned, audited, and atomic.
