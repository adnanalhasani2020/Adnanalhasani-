package com.adnanalhasani.commercial

/**
 * Builds rows for inventory-specific reports. Returns null when the selected
 * report belongs to another domain so the caller can continue its dispatch.
 */
internal fun buildInventoryReportRows(
    selectedReport: String,
    cloudMode: Boolean,
    cloudStockAlerts: List<CommercialCloudStockAlert>,
    cloudGlMappings: List<CommercialCloudGlAccountMapping>,
    cloudStockMovements: List<CommercialCloudStockMovement>,
    cloudWarehouseInventoryContext: List<CommercialCloudWarehouseInventoryContext>,
    stockMovementUserReport: StockMovementUserReportState,
    locationInventoryReport: LocationInventoryReportState,
    products: List<Product>,
    stockLow: List<Product>,
    startMillis: Long,
    endExclusive: Long,
    endText: String,
    sourceErrorForReport: (String) -> String
): List<ReportLine>? = when (selectedReport) {
        "التقييم المالي الحالي للمخزون" -> if (sourceErrorForReport(selectedReport).isNotBlank()) emptyList() else {
            val currency = cloudGlMappings.first { it.mappingKey == "inventory" }.currencyCode
            buildList {
                var totalValue = 0L
                cloudStockAlerts.sortedBy { it.productName }.forEach { item ->
                    val value = runCatching {
                        item.quantityOnHand.multiply(java.math.BigDecimal.valueOf(item.unitCostMinor ?: 0L))
                            .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
                    }.getOrElse { throw IllegalStateException("تجاوزت قيمة المخزون نطاق الأرقام المدعوم.") }
                    totalValue = Math.addExact(totalValue, value)
                    add(ReportLine("حالي", item.offeringId, item.productName, value, currency,
                        "الكمية: ${item.quantityOnHand.stripTrailingZeros().toPlainString()} ${item.unitName} · تكلفة الوحدة المسجلة: ${reportMoney(item.unitCostMinor ?: 0L, currency, true)} · قيمة حالية فقط"))
                }
                add(ReportLine(endText, "إجمالي", "إجمالي قيمة المخزون الحالي", totalValue, currency,
                    "مجموع الكميات الحالية × تكلفة الوحدة المسجلة؛ لا يتضمن تقييمًا تاريخيًا أو مطابقة دفتر الأستاذ"))
            }
        }
        "حركة المخزون حسب المستخدم" -> stockMovementUserReport.lines
        "حركة المخزون الفعلية" -> cloudStockMovements.filter { movement ->
            reportDateMillis(movement.createdAt)?.let { it >= startMillis && it < endExclusive } == true
        }.map { movement ->
            val movementTypeLabel = when (movement.movementType.lowercase()) {
                "opening" -> "رصيد افتتاحي"
                "sale" -> "بيع"
                "receipt" -> "توريد / استلام"
                "adjustment" -> "تسوية مخزون"
                "return" -> "مرتجع"
                else -> movement.movementType
            }
            ReportLine(movement.createdAt.take(10), movement.sourceReference.ifBlank { movement.id }, movement.productName, 0L, "-",
                "نوع الحركة: $movementTypeLabel · المصدر: ${movement.saleId.ifBlank { movement.sourceReference }} ${movement.saleStatus}",
                displayAmount = movement.quantityDelta.stripTrailingZeros().toPlainString() + if (movement.unitName.isBlank()) "" else " ${movement.unitName}")
        }.sortedByDescending { it.date }
        "رصيد المخزون حسب الموقع" -> locationInventoryReport.lines
        "حالة ربط أرصدة المخزون بالمستودعات" -> cloudWarehouseInventoryContext.map { item ->
            val mappingLabel = when {
                item.mappingStatus == "mapped" && item.warehouseState == "inactive" ->
                    "مرتبط بمستودع غير نشط"
                item.mappingStatus == "mapped" && item.warehouseState == "archived" ->
                    "مرتبط بمستودع مؤرشف"
                item.mappingStatus == "mapped" && item.warehouseState != "active" ->
                    "حالة المستودع غير معروفة"
                item.mappingStatus == "mapped" -> "مربوط بمستودع واحد"
                item.mappingStatus == "unconfigured" -> "غير مهيأ"
                else -> "ملتبس · أكثر من مستودع"
            }
            val warehouseLabel = if (item.mappingStatus == "mapped") {
                item.warehouseName + if (item.warehouseCode.isBlank()) "" else " (${item.warehouseCode})"
            } else "لا يوجد مستودع وحيد معتمد"
            ReportLine(
                "-", item.locationName, item.productName, 0L, "-", mappingLabel,
                displayAmount = "المستودع: $warehouseLabel · حالة المستودع: ${item.warehouseState.ifBlank { "غير معروفة" }} · روابط المستودع: ${item.mappedWarehouseCount} · المتاح: ${item.availableQuantity.stripTrailingZeros().toPlainString()} ${item.unitName} · الإجمالي: ${item.quantityOnHand.stripTrailingZeros().toPlainString()} · المحجوز: ${item.quantityReserved.stripTrailingZeros().toPlainString()} · حد الطلب: ${item.reorderPoint.stripTrailingZeros().toPlainString()}"
            )
        }
        "رصيد المخزون الحالي" -> if (cloudMode) cloudStockAlerts.map { item ->
            ReportLine("-", item.offeringId, item.productName, 0L, "-", "المتاح بعد حجز الكمية · ${item.categoryName} · حد إعادة الطلب: ${item.reorderPoint} ${item.unitName}",
                displayAmount = java.math.BigDecimal.valueOf(item.availableQuantity).stripTrailingZeros().toPlainString() + " ${item.unitName}")
        } else products.map {
            ReportLine("-", it.catalogCode.ifBlank { it.barcode }, it.name, it.stock.toLong(), "-", "${it.unitCode} · سعر ${it.price}")
        }
        "تنبيهات المخزون المنخفض" -> if (cloudMode) cloudStockAlerts.filter { it.availableQuantity <= it.reorderPoint }.map { item ->
            ReportLine("-", item.offeringId, item.productName, 0L, "-", "حد إعادة الطلب: ${item.reorderPoint} ${item.unitName} · ${item.categoryName}",
                displayAmount = java.math.BigDecimal.valueOf(item.availableQuantity).stripTrailingZeros().toPlainString() + " ${item.unitName}")
        } else stockLow.map {
            ReportLine("-", it.catalogCode.ifBlank { it.barcode }, it.name, it.stock.toLong(), "-", "حد التنبيه ${it.reorderPoint.takeIf { n -> n >= 0 } ?: 9} ${it.unitCode}")
        }
        else -> null
}
