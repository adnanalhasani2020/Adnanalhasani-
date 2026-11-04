package com.adnanalhasani.commercial

import org.json.JSONObject

internal fun commercialCloudActionableError(raw: String, status: Int): String {
    val error = runCatching { JSONObject(raw) }.getOrNull()
    val code = error?.optString("code").orEmpty()
    val message = listOf(error?.optString("message"), error?.optString("hint"))
        .filter { !it.isNullOrBlank() && it != "null" }
        .joinToString(" — ")
    val detail = message.ifBlank { "HTTP $status" }
    return when {
        code == "PGRST202" || message.contains("schema cache", ignoreCase = true) ||
            message.contains("could not find the function", ignoreCase = true) ->
            "توقّف الإجراء لأن دالة قاعدة البيانات المطلوبة غير منشورة أو لم تُحدّث بعد. لم يؤكد الخادم تنفيذ العملية. لا تنشئ عملية بديلة بمفتاح جديد؛ أعد المحاولة بنفس العملية بعد إصلاح ترحيلات قاعدة البيانات. التفاصيل: $detail"
        message.contains("idempotency_key_reused_with_different_request", ignoreCase = true) ||
            message.contains("expense_idempotency_key_reused_with_different_request", ignoreCase = true) ->
            "تم إيقاف العملية لحماية البيانات: مفتاح إعادة المحاولة مرتبط بطلب مختلف. لا تكرر الدفع أو الصرف؛ راجع السجل الموجود وأعد المحاولة بالبيانات الأصلية فقط. التفاصيل: $detail"
        message.contains("expense_cash_or_bank_account_not_configured_for_currency", ignoreCase = true) ->
            "لم يُحفظ سند الصرف لأن حساب الصندوق/البنك غير مربوط بالعملة المختارة في دليل الحسابات. اضبط الربط أولًا ثم أعد المحاولة؛ لم يُسجل قيد أو سند. التفاصيل: $detail"
        message.contains("customer_credit_limit_exceeded", ignoreCase = true) ->
            "رُفض الطلب الآجل لأن إجمالي المديونية والطلبات الآجلة المفتوحة سيتجاوز حد الائتمان المحدد لحسابك. استخدم الدفع النقدي أو اطلب من المؤسسة مراجعة حد الائتمان والرصيد المستحق."
        message.contains("customer_account_currency_mismatch", ignoreCase = true) ->
            "عملة حساب العميل لا تطابق عملة الأصناف المختارة. لا يمكن حفظ الطلب حتى تصحيح إعداد العملة في المؤسسة."
        message.contains("customer_payment_journal_not_posted", ignoreCase = true) ->
            "لم يؤكد الخادم ترحيل قيد التحصيل، لذلك رُفضت المعاملة كاملة. لا تنشئ تحصيلًا جديدًا بمفتاح مختلف؛ حدّث السجل وراجع القيد قبل إعادة المحاولة. التفاصيل: $detail"
        message.contains("receipt_and_ar_mappings_required", ignoreCase = true) ->
            "لم يُرحّل التحصيل لأن حساب وسيلة القبض أو ذمم العملاء غير مضبوط. راجع دليل الحسابات قبل إعادة المحاولة. التفاصيل: $detail"
        message.contains("supplier_payment_account_not_configured_for_currency", ignoreCase = true) ->
            "لم يُسجل تسديد المورد لأن حساب وسيلة الدفع غير مربوط بالعملة المختارة. اضبط ربط الصندوق/البنك/الشيكات أولًا ثم أعد المحاولة. التفاصيل: $detail"
        message.contains("collection_exceeds_outstanding_balance", ignoreCase = true) ||
            message.contains("overpayment", ignoreCase = true) ->
            "رُفض التحصيل لأن المبلغ يتجاوز الرصيد المستحق. حدّث الفاتورة وأدخل المبلغ المتبقي الفعلي. التفاصيل: $detail"
        // Expired refresh credentials require re-authentication, not a generic retry.
        message.contains("invalid refresh token", ignoreCase = true) ||
            message.contains("refresh token not found", ignoreCase = true) ||
            message.contains("invalid_grant", ignoreCase = true) ||
            code == "refresh_token_not_found" ->
            "انتهت جلسة الحساب أو لم تعد صالحة؛ سجّل الدخول مجددًا قبل متابعة العمل. التفاصيل: $detail"
        message.contains("insufficient_stock", ignoreCase = true) ||
            message.contains("insufficient_inventory", ignoreCase = true) ||
            message.contains("stock", ignoreCase = true) && message.contains("available", ignoreCase = true) ->
            "رُفض البيع لأن الكمية المتاحة غير كافية أو تغيّرت أثناء الحفظ. حدّث المخزون قبل المحاولة مجددًا. التفاصيل: $detail"
        status == 401 || status == 403 || code == "42501" ->
            "لم يُسمح بتنفيذ العملية. تحقق من تسجيل الدخول وصلاحيات المؤسسة قبل إعادة المحاولة. التفاصيل: $detail"
        else -> "تعذر تأكيد العملية من الخادم (HTTP $status). لا تنشئ طلبًا جديدًا بمفتاح مختلف؛ استخدم إعادة المحاولة للعملية نفسها ثم تحقق من الفاتورة/الدفعة في السجل. التفاصيل: $detail"
    }
}
