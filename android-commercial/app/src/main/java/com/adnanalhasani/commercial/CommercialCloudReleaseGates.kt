package com.adnanalhasani.commercial

/**
 * Production rollout gates for cloud workflows whose required Supabase migrations
 * are present in Git but not yet verified in the linked production schema.
 *
 * Change a gate to true only after the matching migration is deployed and
 * authenticated integration tests pass against the deployed schema.
 */
internal const val COMMERCIAL_CLOUD_FRACTIONAL_QUANTITY_RPC_READY = false
internal const val COMMERCIAL_CLOUD_PAYMENT_REQUESTS_RPC_READY = false
internal const val COMMERCIAL_CLOUD_SUPPLIER_INVOICE_DATES_RPC_READY = false
internal const val COMMERCIAL_CLOUD_UNMAPPED_INVOICE_ITEM_RPC_READY = false
