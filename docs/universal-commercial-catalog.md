# Universal product and unit catalog — implementation contract

This migration introduces the database foundation for a shared, multilingual, cross-industry catalog without replacing existing product, offering, inventory, or sales tables.

## Included model
- Category tree uses a self-reference with cycle prevention and no fixed depth limit.
- Shared global templates and organization-owned categories/products coexist.
- Unit dimensions cover count, mass, volume, length, area, time, temperature, energy, pressure, data, package and extensible other; conversion factors use high-precision numerics.
- Product-level data includes identifiers, descriptions, brand, manufacturer/country, model/part number, ingredients/materials/recipe/specifications, storage/use/warnings, expiry/lot/serial tracking and HTTPS image URL.
- Translations are normalized by locale for categories, products and units. Do not assume only Arabic/English.
- Product units support separate sale/purchase/stock/recipe/shipping/consumption roles, conversion to base quantity, packaging labels, SKU, barcode, net quantity and per-package image URL.
- Sector and geographic targeting are explicit include/exclude rules. The sector list starts with hospital, pharmacy, grocery, restaurant, hotel, general/mobile/automotive/heavy-equipment companies, wholesale and other domains; it can be extended by migration.
- Recipe ingredients reference either another catalog product or a free-text ingredient.
- Religious, medical, regulatory and dietary statements are source-attributed, jurisdiction-aware, time-scoped and carry review/confidence metadata. These records are not universal religious rulings or a substitute for clinician/regulator advice.
- Organization catalog links can be transferred by an owner/admin who has rights to both source and destination organizations. Transfer is idempotent and does not overwrite existing destination links.
- Product images are HTTPS URLs only. No image bytes are uploaded to Supabase Storage by this schema.

## Security
RLS is enabled on every table added here. Global reference/product rows are readable by authenticated users; organization-owned records and writes are limited by active organization membership. The transfer RPC verifies owner/admin membership in both organizations.

## Important integration boundary
This is the database/API foundation only. It does **not** claim that Android product screens, inventory, sales, barcode scanning, search/autocomplete, region-aware discovery, business-type recommendations or cloud sync have already been connected. Those integrations must consume this schema and preserve existing product/sale snapshots and data. Apply this migration to the target Supabase project and validate against the live schema before enabling application writes.
