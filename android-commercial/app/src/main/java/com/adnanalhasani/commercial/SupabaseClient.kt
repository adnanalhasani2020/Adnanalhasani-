package com.adnanalhasani.commercial

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object SupabaseClient {
    fun request(path: String, method: String, payload: JSONObject? = null, token: String? = null): String {
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')
        val anon = BuildConfig.SUPABASE_ANON_KEY
        require(base.startsWith("https://") && anon.isNotBlank()) { "إعدادات اتصال Supabase غير مكتملة." }
        val connection = (URL("$base$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 25000
            setRequestProperty("apikey", anon)
            setRequestProperty("Accept", "application/json")
            if (payload != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            if (payload != null) connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException(commercialCloudActionableError(raw, status))
            }
            raw
        } catch (e: java.net.SocketTimeoutException) {
            throw IllegalStateException(
                "انتهت مهلة الاتصال ولم يتمكن التطبيق من تأكيد النتيجة. إذا كانت العملية بيعًا أو تحصيلًا، لا تبدأ عملية جديدة بمفتاح مختلف؛ أعد المحاولة للعملية المعلّقة نفسها ثم تحقق من سجل الفاتورة/الدفعة.",
                e
            )
        } catch (e: java.io.IOException) {
            throw IllegalStateException(
                "انقطع الاتصال قبل تأكيد النتيجة من الخادم. لا تكرر البيع أو التحصيل كعملية جديدة؛ أعد المحاولة للعملية المعلّقة نفسها وتحقق من السجل قبل أي إجراء بديل.",
                e
            )
        } finally {
            connection.disconnect()
        }
    }
}
