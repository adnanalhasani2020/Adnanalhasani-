# Commercial Android architecture

This directory is the working architecture record for the existing `android-commercial` application.

## Scope

- Preserve current behavior and every existing feature.
- Refactor incrementally; no broad rewrite and no destructive database changes.
- Keep Supabase schema/RPC contracts stable during source organization.
- Keep local/demo data clearly separate from authenticated cloud data.
- A source move is not considered complete until the Android build and focused tests pass.

## Current implementation facts

- Android application: Kotlin + Jetpack Compose.
- Package: `com.adnanalhasani.commercial`.
- Entry/navigation/local legacy flows are concentrated in `MainActivity.kt`.
- Supabase HTTP/session and many commercial domain operations are concentrated in `CommercialCloudRepository.kt`.
- Report UI, report catalog, calculations/builders, and PDF/XLSX export are concentrated in `ReportsCenterScreen.kt`.
- Catalog discovery, market context, directory management, invoice linking, and transfers are concentrated in `CatalogScreen.kt`.
- Several domains already have separate screens and calculation/application files. They must be reused, not recreated.

## Target package layout

The target is a logical ownership map. Files will move into these packages in small verified slices; do not create empty placeholder classes merely to imitate the tree.

```text
com.adnanalhasani.commercial
  app/                 application entry and composition root
  core/
    auth/              session lifecycle and authentication state
    network/           shared HTTP/Supabase transport and error mapping
    common/            shared formatting and reusable primitives
    export/            PDF/XLSX/print infrastructure
  data/
    local/             local persistence and local data sources
    remote/            Supabase transport adapters
    repository/        repository implementations by domain
  domain/
    model/             shared business models
    validation/        reusable domain validation
    calculation/       pure calculations
    workflow/          document lifecycle rules
  feature/
    dashboard/
    sales/
    inventory/
    catalog/
    purchases/
    customers/
    returns/
    expenses/
    accounting/
    reports/
    warehouses/
    customerportal/
    notifications/
    settings/
  ui/
    navigation/
    components/
    theme/
```

## Ownership rules

1. A feature screen owns presentation and user interaction, not direct HTTP construction.
2. A repository owns data retrieval and persistence for one business domain.
3. Shared network/auth code is implemented once and injected/reused; no per-screen duplicate clients.
4. Domain calculations remain pure and independently testable.
5. Database authorization, tenant isolation, idempotency, and atomicity remain server-enforced.
6. Posted financial and inventory records are not silently edited by UI refactors.
7. Shared models must have one canonical owner; avoid duplicate model definitions across screens.
8. Existing public/internal function contracts are retained until all callers and tests are migrated.

## Status vocabulary

- `INVENTORIED`: source responsibilities and callers identified.
- `EXTRACTED`: implementation moved to a focused source file/package.
- `CALLERS_MIGRATED`: all callers use the new owner.
- `TESTED`: focused tests and Android build pass on the same commit.
- `VERIFIED_CLOUD`: authenticated integration test passed against the intended Supabase environment.

A documentation entry or source-contract test alone never means `TESTED` or `VERIFIED_CLOUD`.
