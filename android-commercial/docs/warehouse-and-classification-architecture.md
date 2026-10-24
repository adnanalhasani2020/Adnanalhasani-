# Warehouse, Department, Category, and Product Architecture

Status: Phase 1 additive migration applied to the live Supabase project. Android now supports listing/creating warehouses, nested storage locations (including validated location types), category trees, and departments. Client-side duplicate-code checks improve feedback, while database constraints remain authoritative. Storage locations are intentionally not connected to balance mutation, stock posting, or transfers until an audited, atomic movement RPC and reconciliation path are implemented.

## Research basis

The model follows established ERP patterns documented by:
- Odoo Inventory: warehouses represent physical sites; locations represent storage areas inside a warehouse; stock moves connect locations.
- Oracle Fusion Inventory: inventory organization -> subinventory -> optional locator hierarchy.
- ERPNext: item groups form a parent/child tree; item masters reference an item group, warehouse, and unit of measure; UOM conversion factors are explicit; variants are distinct sellable items derived from attributes.
- Microsoft Dynamics 365 Supply Chain Management: separate warehouse management, inventory management, product information management, and procurement flows.

Official references:
- https://www.odoo.com/documentation/19.0/applications/inventory_and_mrp/inventory/warehouses_storage/inventory_management.html
- https://www.odoo.com/documentation/17.0/applications/inventory_and_mrp/inventory/warehouses_storage/inventory_management/warehouses.html
- https://docs.oracle.com/en/cloud/saas/supply-chain-and-manufacturing/25c/faims/how-inventory-organizations-subinventories-and-locators-fit.html
- https://docs.frappe.io/erpnext/item-group
- https://docs.frappe.io/erpnext/item
- https://docs.frappe.io/erpnext/uom
- https://docs.frappe.io/erpnext/item-variants
- https://learn.microsoft.com/en-us/dynamics365/supply-chain/

## Existing schema facts to preserve

The database already has:
- `commercial_organizations` and `commercial_activities` for business ownership and activity scope.
- `commercial_organization_locations` with `organization_id`, `parent_location_id`, `location_type`, code, address, state, and metadata. This is the existing branch/site hierarchy; do not create a competing branch table.
- `commercial_products` as product master data and `commercial_offerings` as an activity-scoped sellable product with price/currency/location scope.
- `commercial_inventory_balances` keyed by organization, location, and offering with on-hand, reserved, and reorder quantities.
- `commercial_global_business_activity_nodes` for sector/activity/specialization taxonomy. This is a business-activity directory, NOT the product category tree.
- Existing global catalog and product/unit relations. Reuse them where appropriate; do not duplicate global product or unit masters.

## Recommended conceptual hierarchy

Organization
- Activity / business unit
  - Branch / operational site (`commercial_organization_locations`)
    - Warehouse (stock ownership / operational stock site)
      - Storage location (optional, unlimited parent-child depth: zone, aisle, rack, shelf, bin)
        - Stock balance per offering and storage location

Product data
- Product master (`commercial_products` or existing global catalog reference)
  - Organization/activity offering (`commercial_offerings`): price, currency, lifecycle and sale scope
  - Product variant/SKU only where attributes change identity, barcode, pricing, or stock tracking
  - Unit definitions and item-specific conversion rules
- Product category tree: independent, organization-scoped, unlimited depth, one parent per node; a product/offering has one primary category initially, with an optional many-to-many classification extension later.
- Department: an organizational responsibility/cost/reporting dimension, not a stock location or product category. A department can be parent/child and can own categories, employees, or expense allocations without owning inventory implicitly.

## Data model proposal and implementation status

Migration committed: `supabase/migrations/20261024001000_commercial_warehouse_category_department_foundation.sql`. Category RPC migrations: `supabase/migrations/20261024002000_commercial_offering_category_assignment_rpc.sql` and `supabase/migrations/20261024003000_commercial_inventory_create_with_category.sql`.
It adds organization-scoped warehouse, nested storage-location, product-category, department, offering-category, and department-warehouse tables with RLS, hierarchy cycle checks, and cross-organization relationship guards. It intentionally does not modify current inventory balances or checkout behavior. The migration was applied through the Supabase migration tool and verified in the live schema. It does not modify existing balances or checkout behavior. The Android screen can create root and nested category/department nodes, warehouses, and storage locations. Product creation now offers an optional active category selector; the item, offering, opening balance/movement, and primary category assignment are created atomically through `commercial_create_inventory_item_with_category`. The separate `commercial_assign_offering_category` RPC supports safe, idempotent reassignment for future editing screens. Both RPCs validate the authenticated organization manager, activity and category scope, and write audit events. The inventory screen reads assigned category names; unassigned items continue to show `غير مصنف`.

## Remaining data-model work

1. `commercial_warehouses`
   - id, organization_id, activity_id nullable where existing authorization model allows, branch_location_id, code, name, warehouse_type, state, address/metadata, created_by, timestamps.
   - Unique active code per organization; branch must belong to the same organization.
   - Types: main, retail, distribution, production, returns, quarantine, virtual/transit.
   - A warehouse is not the same thing as a branch; a branch may have several warehouses and a warehouse belongs to one branch/site.

2. `commercial_storage_locations`
   - id, warehouse_id, parent_location_id, code, name, location_type, state, picking_sequence, allow_negative_stock=false, metadata, timestamps.
   - Unlimited hierarchy with cycle prevention; terminal storage locations hold stock. Structural/view nodes should not hold stock.
   - Types include view, internal, receiving, packing, quality, quarantine, transit, customer, supplier, scrap. External locations are movement endpoints, not sellable on-hand stock.

3. Stock balance/movement evolution
   - Preserve `commercial_inventory_balances` as the summary by organization/site/offering until all readers/writers are migrated.
   - Add storage_location_id only as nullable during migration; add a unique balance key at the chosen grain after backfill.
   - Make an append-only stock movement ledger the source of truth: offering, source/destination location, quantity, unit, reason, reference type/id, idempotency key, actor, timestamp. Receipts, sales, returns, adjustments, and transfers must create ledger rows atomically.
   - Transfers use a draft -> submitted/posted lifecycle and post both sides in one database transaction; never update quantity silently from the client.
   - Reserved quantity is distinct from on-hand; available = on_hand - reserved. Negative stock is denied by default, with an explicit permissioned exception if a business later requires it.

4. `commercial_product_categories`
   - id, organization_id, parent_id, code, name_ar, name_en, description, state, sort_order, created_by, timestamps.
   - Unique normalized name/code within a parent or organization according to the selected uniqueness rule; indexes for parent and organization.
   - Prevent cycles and cross-organization parent references. Deactivate categories in use instead of hard deleting them.
   - Keep global product catalog taxonomies separate from each business's local operational categories.

5. Category and department links
   - Add `category_id` to the activity-scoped offering or a join table if multiple classifications are needed; decide based on actual product reuse and reporting needs before migration.
   - `commercial_departments`: organization-scoped tree with code/name/state/parent_id and optional manager/cost-center metadata.
   - Optional explicit assignment tables for department-to-activity, department-to-category, and department-to-warehouse. Do not infer these relationships from names.
   - A department is a reporting/ownership dimension; it must not duplicate activity, branch, warehouse, or product category.

6. Units, SKU, and identifiers
   - Reuse existing global units/catalog unit relations.
   - Store conversion factors per product/offering and unit pair, with positive factors and a clear base unit. Never assume all items convert identically (e.g. case sizes vary by product).
   - SKU, barcode, lot/batch, and serial tracking should be separate optional capabilities. Variant attributes generate distinct inventory identities only when the variant must be stocked/sold separately.

## Required constraints, security, and API behavior

- Every organization-scoped row is protected by RLS and validated against the authenticated user's organization/activity membership.
- Prefer transactional RPCs for warehouse create/update, category tree changes, stock adjustments, transfers, and receipts. Validate parent belongs to the same organization and reject hierarchy cycles server-side.
- Use idempotency keys for retryable stock and financial writes.
- Keep audit records for create/update/deactivate, posting, and reversal; posted transactions are reversed with compensating transactions, not edited in place.
- Do not upload product images into Supabase Storage as part of this work; store only an externally hosted image URL when that field is introduced.
- Preserve compatibility during rollout: nullable additive columns, backfill, dual-read only temporarily, then enforce constraints after data verification.
- Reports must filter by organization and respect role permissions. Cross-branch reporting must not depend on user-owned `commercial_sales` rows alone.

## Rollout order

1. Inspect all existing FKs, RLS policies, RPC definitions, and active client queries; produce a migration map and avoid duplicate concepts.
2. Implement warehouse/storage-location schema and RLS without changing existing stock writes.
3. Implement category and department trees, constraints, RPCs, and seed only universal neutral root nodes if the product requires them.
4. Add a stock movement ledger and idempotent adjustment/transfer RPCs.
5. Backfill existing location-level balances into the new model; reconcile totals before switching reads.
6. Update Android repositories and screens: settings/master data, warehouse selector, category tree, department management, item editor, stock ledger, transfers, and filters.
7. Verify RLS/tenant isolation, concurrent transfers, duplicate retries, cycle prevention, and aggregate stock reconciliation; then remove legacy write paths.

## Acceptance criteria

- A small grocery store can use one default warehouse and one default stock location without extra setup.
- A multi-branch company can operate several warehouses per branch and nested bins without hard-coded depth limits.
- Categories and departments are independent trees and can be managed without changing the global business-activity directory.
- The same global catalog product can be offered by multiple activities/organizations without leaking local category, price, or stock data between them.
- Transfers preserve total stock, are atomic and idempotent, and produce auditable source/destination movements.
- Existing balances and sales remain readable throughout migration; no destructive backfill or silent stock reset is permitted.
