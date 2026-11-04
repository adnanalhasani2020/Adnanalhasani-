package com.adnanalhasani.commercial

import android.content.Context
import org.json.JSONObject

/** Owns cloud sign-in, refresh-token restoration, persistence, and sign-out. */
internal class CatalogAuthWorkflow(
    private val context: Context,
    private val request: (action: () -> String, onSuccess: (String) -> Unit, onFailure: ((Throwable) -> Unit)?) -> Unit,
    private val accessToken: (String?) -> Unit,
    private val signedInUserId: (String) -> Unit,
    private val password: (String) -> Unit,
    private val feedback: (String) -> Unit,
    private val restoringSession: (Boolean) -> Unit,
    private val organizationId: (String) -> Unit
) {
    fun restoreSession() {
        val savedRefresh = runCatching {
            commercialCloudSessionPreferences(context).getString("refresh_token", null)
        }.getOrNull()
        if (savedRefresh.isNullOrBlank()) {
            restoringSession(false)
            return
        }
        request({
            val raw = catalogHttp(
                "/auth/v1/token?grant_type=refresh_token",
                JSONObject().put("refresh_token", savedRefresh)
            )
            val session = JSONObject(raw)
            val access = session.optString("access_token")
            val refresh = session.optString("refresh_token").ifBlank { savedRefresh }
            if (access.isBlank()) throw IllegalStateException("انتهت الجلسة؛ سجّل الدخول مجددًا.")
            "${access}|${refresh}|${session.optJSONObject("user")?.optString("id").orEmpty()}"
        }, { sessionValue ->
            val parts = sessionValue.split("|", limit = 3)
            accessToken(parts[0])
            commercialCloudSessionPreferences(context).edit()
                .putString("refresh_token", parts.getOrNull(1).orEmpty()).apply()
            signedInUserId(parts.getOrNull(2).orEmpty())
            feedback("تم استعادة جلستك السابقة.")
            restoringSession(false)
        }, null)
    }

    fun login(email: String, enteredPassword: String) {
        request({
            val raw = catalogHttp(
                "/auth/v1/token?grant_type=password",
                JSONObject().put("email", email).put("password", enteredPassword)
            )
            val session = JSONObject(raw)
            val access = session.optString("access_token")
            val refresh = session.optString("refresh_token")
            if (access.isBlank() || refresh.isBlank()) {
                throw IllegalStateException("لم تُرجع خدمة المصادقة جلسة صالحة.")
            }
            "${access}|${refresh}|${session.optJSONObject("user")?.optString("id").orEmpty()}"
        }, { sessionValue ->
            val parts = sessionValue.split("|", limit = 3)
            accessToken(parts[0])
            commercialCloudSessionPreferences(context).edit()
                .putString("refresh_token", parts.getOrNull(1).orEmpty()).apply()
            signedInUserId(parts.getOrNull(2).orEmpty())
            password("")
            feedback("تم تسجيل الدخول. ستُستعاد الجلسة تلقائيًا عند فتح الدليل لاحقًا.")
        }, null)
    }

    fun logout() {
        runCatching { commercialCloudSessionPreferences(context).edit().clear().apply() }
        accessToken(null)
        signedInUserId("")
        organizationId("")
        feedback("تم تسجيل الخروج.")
    }
}
