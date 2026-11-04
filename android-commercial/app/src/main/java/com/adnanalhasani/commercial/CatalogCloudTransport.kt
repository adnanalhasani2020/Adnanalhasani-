package com.adnanalhasani.commercial

import org.json.JSONObject

/**
 * Catalog-specific names retained for existing call sites; all HTTP traffic uses the
 * application's single shared Supabase transport and encrypted session store.
 */
internal fun catalogHttp(path: String, payload: JSONObject, token: String? = null): String =
    SupabaseClient.request(
        path = path,
        method = "POST",
        payload = payload,
        token = token,
        readTimeoutMs = 25000,
        mapErrors = true,
        wrapNetworkErrors = true
    )

internal fun catalogGet(path: String, token: String): String =
    SupabaseClient.request(
        path = path,
        method = "GET",
        token = token,
        readTimeoutMs = 25000,
        mapErrors = true,
        wrapNetworkErrors = true
    )
