# Universal product and unit catalog — implementation contract

These additive migrations extend the existing global catalog, organization catalog links, unit directory, and translation tables. They do not create parallel product, category, organization-catalog, unit, or generic translation masters.

## Included model
- The existing `commercial_global_catalog_categories.parent_code` hierarchy is retained and guarded against cycles; depth is unbounded.
- Shared global templates and organization-owned categories/products coexist.
- Reuses `commercial_global_units` and its canonical Arabic/English labels and conversion factors, plus `commercial_global_entity_translations` for additional locales. Unit dimensions honor the existing canonical constraints.
- Product-level data includes identifiers, descriptions, brand, manufacturer/country, model/part number, ingredients/materials/recipe/specifications, storage/use/warnings, expiry/lot/serial tracking and HTTPS image URL.
- Translations are normalized by locale for categories, products and units. Do not assume only Arabic/English.
- Product units support separate sale/purchase/stock/recipe/shipping/consumption roles, product-specific conversion to base quantity, multiple package variants, SKU, barcode, net quantity and per-package image URL.
- Sector, business-level and geographic targeting are explicit include/exclude rules; sector and level codes are data-driven rather than restricted to a fixed business list. Common sector codes include hospital, pharmacy, grocery, restaurant, hotel, general/mobile/automotive/heavy-equipment companies, wholesale and retail. Arbitrary sector and business-level codes are accepted without schema migrations.
- Recipe ingredients reference either another catalog product or a free-text ingredient.
- Religious, medical, regulatory and dietary statements are source-attributed, jurisdiction-aware, time-scoped and carry review/confidence metadata. These records are not universal religious rulings or a substitute for clinician/regulator advice.
- Cross-account catalog transfer is two-phase: an owner/admin of the source sends a transfer request; an owner/admin of the destination explicitly accepts or rejects it. Acceptance preserves destination links and translations without overwriting existing selections.
- `commercial_catalog_discover_products` provides authenticated text/barcode search with locale fallback, sector, business-level and region inclusion/exclusion, and recommendation scoring; it verifies active organization membership and respects disabled local catalog links.
- Product images are HTTPS URLs only. No image bytes are uploaded to Supabase Storage by this schema.

## Security
RLS is enabled on every table added here. Global reference/product rows are readable by authenticated users; organization-owned records and writes are limited by active organization membership. The transfer RPC verifies owner/admin membership in both organizations.

## Migration order
1. `20261024100000_universal_commercial_catalog.sql` — additive catalog fields, unit variants, targeting, recipes, rulings, ownership/RLS guards, and safe public-vs-tenant read policies.
2. `20261024102000_seed_universal_catalog_templates.sql` — bilingual starter categories/templates, canonical unit links, sector targeting and business-level recommendation weights. Generic templates do not invent manufacturer identities, GTINs, pack conversions or medical/religious outcomes.
3. `20261024103000_catalog_discovery_rpc.sql` — authenticated activity/level/region-aware search with barcode lookup, localized product details, unit names and sourced rulings.
4. `20261024104000_catalog_management_rpc.sql` — category/unit/product authoring, extra-locale translations, measured recipe ingredients, region/sector rules, separate ruling-review action, and two-phase account transfer.

The product discovery response includes the full product detail payload and the source-attributed rulings, not just a product name. Additional locale values use the existing translation directory; the UI accepts one field translation per line in `locale|field|value` form. For package variants, the UI accepts `unit_code|role|qty_in_base|barcode|package_name|image_url` lines. For multiple region rules, it accepts `country|region|city|include-or-exclude` lines.

## Android integration and remaining boundary
- The Android commercial app now has a Global Catalog screen for authenticated discovery, activity/level/region filtering, barcode/code search, localized product details and unit names, external product/package images, catalog-to-local-inventory import, category creation, organization-scoped custom units, product authoring, package-unit sales with base-stock conversion, extra-locale translations, source-attributed rulings and a separate source-review action, plus send/accept/reject transfer flows.
- Catalog import preserves catalog code, barcode, unit code, image URL, description, brand and manufacturer metadata in the local product JSON; existing local sales and stock flows remain local and are not silently represented as cloud-synced.
- The UI requires a valid organization UUID and the signed-in account's active membership. It deliberately does not use a service-role key. Cross-account transfer recipients must accept the pending request.
- The app does not yet provide camera-based barcode scanning, image rendering, full editing/archive of an existing global catalog record, bulk import/export files, or cloud synchronization of stock/sales. The current local inventory integration is an explicit import into the device, not cloud sync.
- Apply the migrations in timestamp order to a staging Supabase project, validate the RPCs and RLS with owner/admin/staff/non-member identities, then roll them out. They have not been applied to the live project by this change.
