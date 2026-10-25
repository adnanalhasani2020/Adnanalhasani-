package com.adnanalhasani.commercial

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Records a refund only after the operator has actually paid the customer.
 * This RPC does not move money through a bank or payment provider.
 */
internal fun executeCommercialCloudSaleReturnRefund(
    context: Context,
    returnId: String,
    method: String,
    evidenceReference: String
): JSONObject {
    require(returnId.isNotBlank()) { "اختر المرتجع أولًا." }
    require(method in setOf("cash", "bank_transfer", "provider")) { "طريقة الاسترداد غير صالحة." }
    val evidence = evidenceReference.trim()
    require(evidence.length in 3..200) { "أدخل مرجع إثبات الدفع الفعلي (3 إلى 200 حرف)." }

    val prefs = context.getSharedPreferences("commercial_cloud_pending_return_refund_v1", Context.MODE_PRIVATE)
    val fingerprint = listOf(returnId, method, evidence).joinToString("|")
    val storedFingerprint = prefs.getString("fingerprint", null).orEmpty()
    val storedKey = prefs.getString("idempotency_key", null).orEmpty()
    if (storedKey.isNotBlank()) {
        require(storedFingerprint == fingerprint) {
            "يوجد استرداد معلّق لم يُحسم بعد. أعد المحاولة بنفس المرتجع والطريقة ومرجع الإثبات."
        }
    }
    val idempotencyKey = storedKey.ifBlank { UUID.randomUUID().toString() }
    if (storedKey.isBlank()) {
        check(prefs.edit().clear().putString("fingerprint", fingerprint)
            .putString("idempotency_key", idempotencyKey).commit()) {
            "تعذر حفظ مفتاح منع التكرار قبل الإرسال."
        }
    }

    val (organizationId, accessToken) = returnRefundAccess(context)
    val result = JSONObject(returnRefundRequest(
        "/rest/v1/rpc/commercial_execute_sale_return_refund",
        "POST",
        JSONObject()
            .put("p_return_id", returnId)
            .put("p_idempotency_key", idempotencyKey)
            .put("p_method", method)
            .put("p_evidence_reference", evidence),
        accessToken
    ))
    check(prefs.edit().clear().commit()) {
        "أكدت قاعدة البيانات العملية، لكن تعذر تنظيف مفتاح إعادة المحاولة. أعد المحاولة بنفس البيانات."
    }
    return result.put("organization_id", organizationId)
}

private fun returnRefundAccess(context: Context): Pair<String, String> {
    val saved = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val organizationId = saved.getString("organization_id", null).orEmpty()
    require(organizationId.isNotBlank()) { "ملف المؤسسة غير محفوظ." }

    val sessionPrefs = EncryptedSharedPreferences.create(
        context,
        "ruh_catalog_auth_session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    val refreshToken = sessionPrefs.getString("refresh_token", null).orEmpty()
    require(refreshToken.isNotBlank()) { "سجّل الدخول إلى الحساب أولًا." }
    val session = JSONObject(returnRefundRequest(
        "/auth/v1/token?grant_type=refresh_token",
        "POST",
        JSONObject().put("refresh_token", refreshToken),
        null
    ))
    val accessToken = session.optString("access_token")
    require(accessToken.isNotBlank()) { "انتهت جلسة الحساب؛ سجّل الدخول مجددًا." }
    sessionPrefs.edit().putString("refresh_token", session.optString("refresh_token").ifBlank { refreshToken }).apply()
    return organizationId to accessToken
}

private fun returnRefundRequest(path: String, method: String, payload: JSONObject, token: String?): String {
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val anon = BuildConfig.SUPABASE_ANON_KEY
    require(base.startsWith("https://") && anon.isNotBlank()) { "إعدادات اتصال Supabase غير مكتملة." }
    val connection = (URL("$base$path").openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 15000
        readTimeout = 25000
        doOutput = true
        setRequestProperty("apikey", anon)
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Content-Type", "application/json")
        if (token != null) setRequestProperty("Authorization", "Bearer $token")
    }
    return try {
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            val error = runCatching { JSONObject(raw) }.getOrNull()
            throw IllegalStateException(
                listOf(error?.optString("message"), error?.optString("hint"))
                    .filter { !it.isNullOrBlank() && it != "null" }
                    .joinToString(" — ").ifBlank { "فشل الاتصال بقاعدة البيانات (HTTP $status)." }
            )
        }
        raw
    } finally {
        connection.disconnect()
    }
}
