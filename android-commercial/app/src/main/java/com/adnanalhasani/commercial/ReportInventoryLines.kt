package com.adnanalhasani.commercial

import java.time.LocalDate

/** Presentation-ready rows and mapping for inventory-focused reports. */
internal data class ReportLine(val date: String, val reference: String, val name: String, val amount: Long, val currency: String, val status: String, val displayAmount: String? = null)

internal data class LocationInventoryReportState(val lines: List<ReportLine>, val error: String)

internal fun buildLocationInventoryReportState(
    balances: List<CommercialCloudLocationInventoryBalance>,
    loadError: String
): LocationInventoryReportState {
    if (loadError.isNotBlank()) return LocationInventoryReportState(emptyList(), loadError)
    val totals = runCatching {
        calculateLocationInventoryBalanceTotals(balances.map { row ->
            LocationInventoryBalanceInput(
                locationId = row.locationId,
                locationName = row.locationName,
                locationType = row.locationType,
                offeringId = row.offeringId,
                unitName = row.unitName,
                quantityOnHand = row.quantityOnHand,
                quantityReserved = row.quantityReserved
            )
        })
    }.getOrElse {
        return LocationInventoryReportState(emptyList(), it.message.orEmpty().ifBlank { "تعذر حساب أرصدة المخزون حسب الموقع." })
    }
    val lines = totals.map { row ->
        val typeLabel = row.locationType.ifBlank { "نوع موقع غير محدد" }
        ReportLine(
            "حالي",
            row.locationId,
            row.locationName,
            0L,
            "-",
            "$typeLabel · ${row.offeringCount} صنف · محجوز ${row.quantityReserved.stripTrailingZeros().toPlainString()} ${row.unitName}",
            displayAmount = "متاح ${row.quantityAvailable.stripTrailingZeros().toPlainString()} ${row.unitName} · إجمالي ${row.quantityOnHand.stripTrailingZeros().toPlainString()} ${row.unitName}"
        )
    }
    return LocationInventoryReportState(lines, "")
}

internal data class StockMovementUserReportState(val lines: List<ReportLine>, val error: String)

internal fun buildStockMovementUserReportState(
    movements: List<CommercialCloudStockMovement>,
    startDate: LocalDate?,
    endDate: LocalDate?,
    cloudMode: Boolean,
    loadError: String
): StockMovementUserReportState {
    if (!cloudMode) return StockMovementUserReportState(emptyList(), "تقرير حركة المخزون حسب المستخدم يتطلب سجل الحركات السحابي.")
    if (loadError.isNotBlank()) return StockMovementUserReportState(emptyList(), loadError)
    val start = startDate ?: return StockMovementUserReportState(emptyList(), "تاريخ بداية الفترة غير صالح.")
    val end = endDate ?: return StockMovementUserReportState(emptyList(), "تاريخ نهاية الفترة غير صالح.")
    if (start.isAfter(end)) return StockMovementUserReportState(emptyList(), "فترة التقرير غير صحيحة.")
    val datedMovements = runCatching {
        movements.map { movement -> movement to LocalDate.parse(movement.createdAt.take(10)) }
    }.getOrElse {
        return StockMovementUserReportState(emptyList(), "توجد حركة مخزون بتاريخ غير صالح؛ تم حجب التجميع حتى لا تُستبعد حركة من الفترة.")
    }
    val periodMovements = datedMovements.filter { (_, date) ->
        !date.isBefore(start) && !date.isAfter(end)
    }.map { it.first }
    val totals = runCatching {
        calculateStockMovementUserTotals(periodMovements.map { movement ->
            StockMovementUserInput(
                movementId = movement.id,
                userId = movement.createdBy,
                movementType = movement.movementType,
                unitName = movement.unitName,
                quantityDelta = movement.quantityDelta
            )
        })
    }.getOrElse {
        return StockMovementUserReportState(emptyList(), it.message.orEmpty().ifBlank { "تعذر حساب تقرير حركة المخزون حسب المستخدم." })
    }
    val lines = totals.map { row ->
        val actorLabel = if (row.userId == "غير منسوب") "حركات غير منسوبة لمستخدم"
            else "مستخدم ${row.userId}"
        ReportLine(
            "${start} — ${end}",
            "${row.movementCount} حركة",
            "$actorLabel · ${row.movementType}",
            0L,
            "-",
            "عدد الحركات: ${row.movementCount} · نوع الحركة: ${row.movementType} · المستخدم المسجل: ${row.userId}",
            displayAmount = row.quantityDelta.stripTrailingZeros().toPlainString() + " " + row.unitName
        )
    }
    return StockMovementUserReportState(lines, "")
}

