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

## Business activity directory (separate from product categories)
- `commercial_global_business_activity_nodes` is the distinct, unbounded-depth activity hierarchy. It is not a second product-category table; product categories remain in `commercial_global_catalog_categories`.
- The hierarchy uses stable internal codes and `parent_code`, with a write-time cycle guard, selectable activity/specialization nodes, Arabic/English labels, path labels, and optional default business-level hints. The recursive RPC has no fixed depth cap and uses PostgreSQL `CYCLE` detection to stop malformed loops safely.
- `commercial_global_business_activity_aliases` provides curated localized search synonyms with globally unique active normalized aliases across locales, preventing ambiguous aliases from pointing to multiple activities.
- `commercial_global_business_activity_codes` maps a node to an external standard/version only when an explicit mapping has been reviewed. Seed labels are ROUH-maintained starter taxonomy inspired by international classification practice; no ISIC/NAICS code is claimed without a stored external mapping.
- Additional languages reuse `commercial_global_entity_translations` with `entity_type='business_activity'`; no parallel translation master is created.
- Existing `commercial_activities.activity_code` remains compatible, while `business_activity_node_id` provides the normalized FK. A guarded trigger links a clear canonical name/code match without forcing old free-text activities into a possibly incorrect category.
- The Android activity-profile screen loads selectable options from `commercial_list_business_activity_nodes`, displays the full hierarchy path, and uses the selected canonical Arabic name so the server can link the activity record. For example: Trade → Retail trade → Non-specialized retail → Grocery and convenience store.
- Grocery stock categories/products remain in the existing product catalog (`FOOD`, `FOOD-GRAINS`, `FOOD-DAIRY`, `FOOD-DRINKS`, etc.). The activity hierarchy is not reused to classify individual stock items.

## Security
RLS is enabled on every table added here. Global reference/product rows are readable by authenticated users; organization-owned records and writes are limited by active organization membership. The transfer RPC verifies owner/admin membership in both organizations.

## Migration order
1. `20261024100000_universal_commercial_catalog.sql` — additive catalog fields, unit variants, targeting, recipes, rulings, ownership/RLS guards, and safe public-vs-tenant read policies.
2. `20261024102000_seed_universal_catalog_templates.sql` — bilingual starter categories/templates, canonical unit links, sector targeting and business-level recommendation weights. Generic templates do not invent manufacturer identities, GTINs, pack conversions or medical/religious outcomes.
3. `20261024103000_catalog_discovery_rpc.sql` — authenticated activity/level/region-aware search with barcode lookup, localized product details, unit names and sourced rulings.
4. `20261024104000_catalog_management_rpc.sql` — category/unit/product authoring, extra-locale translations, measured recipe ingredients, region/sector rules, separate ruling-review action, and two-phase account transfer.
5. `20261024105000_catalog_update_rpc.sql` — owner/admin-only editing of organization-owned product details, with audit fields and preservation of linked units, recipes, targeting and rulings.
6. `20261024110000_business_activity_taxonomy.sql` — separate unbounded-depth business activity tree, aliases, external-code mappings, safe activity linkage and read RPC.
7. `20261024112000_business_activity_taxonomy_expansion.sql` — broader cross-sector activity coverage, unique alias guard, and explicit ROUH-owned provenance for unmapped starter nodes.
8. `20261024113000_catalog_policy_deduplication.sql` — removes overlapping SELECT policies and replaces broad `FOR ALL` catalog writes with separate insert/update/delete policies.
9. `20261024114000_catalog_integrity_performance.sql` — removes the obsolete one-phase transfer RPC, adds missing FK indexes, and adds stable row identity to region rules.
10. `20261024115000_business_activity_alias_global_uniqueness.sql` — replaces per-locale alias uniqueness with global normalized-alias uniqueness so an alias can never resolve to different nodes.\n11. `20261024116000_business_activity_unbounded_api_depth.sql` — removes the arbitrary API traversal depth cap and uses cycle-safe recursion.

The product discovery response includes the full product detail payload and the source-attributed rulings, not just a product name. Additional locale values use the existing translation directory; the UI accepts one field translation per line in `locale|field|value` form. For package variants, the UI accepts `unit_code|role|qty_in_base|barcode|package_name|image_url` lines. For multiple region rules, it accepts `country|region|city|include-or-exclude` lines.

## Android integration and remaining boundary
- The Android commercial app now has a Global Catalog screen for authenticated discovery, activity/level/region filtering, barcode/code search, localized product details and unit names, external product/package images, catalog-to-local-inventory import, category creation, organization-scoped custom units, product authoring and detail editing for organization-owned products, package-unit sales with base-stock conversion, extra-locale translations, source-attributed rulings and a separate source-review action, plus send/accept/reject transfer flows.
- Catalog import preserves catalog code, barcode, unit code, image URL, description, brand and manufacturer metadata in the local product JSON; existing local sales and stock flows remain local and are not silently represented as cloud-synced.
- The UI requires a valid organization UUID and the signed-in account's active membership. It deliberately does not use a service-role key. Cross-account transfer recipients must accept the pending request.
- The activity-profile screen now offers a hierarchical-path activity selector backed by the global directory; if directory loading fails it falls back to manual text only after the failure is surfaced.
- The app does not yet provide camera-based barcode scanning, image rendering, full editing/archive of an existing global catalog record, bulk import/export files, or cloud synchronization of stock/sales. The current local inventory integration is an explicit import into the device, not cloud sync.
- The five catalog migrations and the two activity-taxonomy migrations were applied to the linked Supabase project on 2026-10-24 after correcting a dollar-quote syntax defect in the first catalog migration. Initial live checks confirmed 0 duplicate catalog codes, 0 orphan product categories, 0 orphan product units, 0 duplicate active unit barcodes, 0 duplicate active activity aliases, and 0 orphan activity parents. Follow-up migrations also split overlapping permissive catalog policies, removed the obsolete one-phase transfer RPC, added missing FK indexes, gave product-region rules a primary key, enforced globally unique activity aliases, and removed the arbitrary activity API depth cap with cycle-safe recursion. This does not replace role-matrix/RLS testing with real authenticated owner/admin/staff/non-member sessions or Android build/CI verification.
