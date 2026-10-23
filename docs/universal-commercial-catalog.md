# Universal product and unit catalog — implementation contract

This migration introduces the database foundation for a shared, multilingual, cross-industry catalog without replacing existing product, offering, inventory, or sales tables.

## Included model
- Category tree uses a self-reference with cycle prevention and no fixed depth limit.
- Shared global templates and organization-owned categories/products coexist.
- Reuses the existing `commercial_global_units` directory and its canonical Arabic/English labels and conversion factors; adds locale-keyed unit-label overrides without duplicating the unit master. Unit dimensions use the existing canonical dimension constraints.
- Product-level data includes identifiers, descriptions, brand, manufacturer/country, model/part number, ingredients/materials/recipe/specifications, storage/use/warnings, expiry/lot/serial tracking and HTTPS image URL.
- Translations are normalized by locale for categories, products and units. Do not assume only Arabic/English.
- Product units support separate sale/purchase/stock/recipe/shipping/consumption roles, product-specific conversion to base quantity, multiple package variants, SKU, barcode, net quantity and per-package image URL.
- Sector, business-level and geographic targeting are explicit include/exclude rules; sector and level codes are data-driven rather than restricted to a fixed business list. Common sector codes include hospital, pharmacy, grocery, restaurant, hotel, general/mobile/automotive/heavy-equipment companies, wholesale and retail. Sector codes are data-driven rather than restricted to this starter list, so new business specializations do not require a schema migration.
- Recipe ingredients reference either another catalog product or a free-text ingredient.
- Religious, medical, regulatory and dietary statements are source-attributed, jurisdiction-aware, time-scoped and carry review/confidence metadata. These records are not universal religious rulings or a substitute for clinician/regulator advice.
- Organization catalog links can be transferred by an owner/admin who has rights to both source and destination organizations. Transfer is idempotent and does not overwrite existing destination links.
- `commercial_catalog_discover_products` provides authenticated text/barcode search with locale fallback, sector, business-level and region inclusion/exclusion, and recommendation scoring; it verifies active organization membership and respects disabled local catalog links.
- Product images are HTTPS URLs only. No image bytes are uploaded to Supabase Storage by this schema.

## Security
RLS is enabled on every table added here. Global reference/product rows are readable by authenticated users; organization-owned records and writes are limited by active organization membership. The transfer RPC verifies owner/admin membership in both organizations.

## Important integration boundary
This is the database/API foundation only. It does **not** claim that Android product screens, inventory, sales, barcode scanning, search/autocomplete, region-aware discovery, business-type recommendations or cloud sync have already been connected. Those integrations must consume this schema and preserve existing product/sale snapshots and data. Apply the migrations in timestamp order to the target Supabase project and validate against the live schema before enabling application writes. The migrations reuse the existing global unit and translation directories; no parallel unit master or translation master is created.
