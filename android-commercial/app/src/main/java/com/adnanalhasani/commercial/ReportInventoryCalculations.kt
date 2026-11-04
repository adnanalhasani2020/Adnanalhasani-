package com.adnanalhasani.commercial

internal data class LocationInventoryBalanceInput(
    val locationId: String,
    val locationName: String,
    val locationType: String,
    val offeringId: String,
    val unitName: String,
    val quantityOnHand: java.math.BigDecimal,
    val quantityReserved: java.math.BigDecimal
)

internal data class LocationInventoryBalanceTotal(
    val locationId: String,
    val locationName: String,
    val locationType: String,
    val unitName: String,
    val offeringCount: Int,
    val quantityOnHand: java.math.BigDecimal,
    val quantityReserved: java.math.BigDecimal,
    val quantityAvailable: java.math.BigDecimal
)

/** Aggregates current balances by organization location and unit; never adds unlike units. */
internal fun calculateLocationInventoryBalanceTotals(
    balances: List<LocationInventoryBalanceInput>
): List<LocationInventoryBalanceTotal> {
    val seen = mutableSetOf<Pair<String, String>>()
    balances.forEach { balance ->
        require(balance.locationId.isNotBlank() && balance.offeringId.isNotBlank() &&
            seen.add(balance.locationId to balance.offeringId)) {
            "يوجد رصيد مخزون مفقود المعرف أو مكرر للموقع والصنف؛ تم حجب التقرير."
        }
        require(balance.locationName.isNotBlank() && balance.unitName.isNotBlank() &&
            balance.quantityReserved.signum() >= 0) {
            "توجد بيانات موقع أو وحدة أو كمية محجوزة غير صالحة؛ تم حجب التقرير."
        }
    }
    return balances.groupBy { balance ->
        Triple(balanceLocationKey(balance.locationId), balance.unitName.trim(), balance.locationType.trim())
    }.map { (key, rows) ->
        val onHand = rows.fold(java.math.BigDecimal.ZERO) { total, row -> total.add(row.quantityOnHand) }
        val reserved = rows.fold(java.math.BigDecimal.ZERO) { total, row -> total.add(row.quantityReserved) }
        LocationInventoryBalanceTotal(
            locationId = key.first,
            locationName = rows.first().locationName.trim(),
            locationType = key.third,
            unitName = key.second,
            offeringCount = rows.size,
            quantityOnHand = onHand,
            quantityReserved = reserved,
            quantityAvailable = onHand.subtract(reserved)
        )
    }.sortedWith(compareBy<LocationInventoryBalanceTotal> { it.locationName }
        .thenBy { it.locationType }.thenBy { it.unitName })
}

private fun balanceLocationKey(locationId: String): String = locationId.trim()

internal data class StockMovementUserInput(
    val movementId: String,
    val userId: String,
    val movementType: String,
    val unitName: String,
    val quantityDelta: java.math.BigDecimal
)

internal data class StockMovementUserTotal(
    val userId: String,
    val movementType: String,
    val unitName: String,
    val movementCount: Int,
    val quantityDelta: java.math.BigDecimal
)

/** Groups persisted stock movements by their recorded actor, movement type, and unit. */
internal fun calculateStockMovementUserTotals(
    movements: List<StockMovementUserInput>
): List<StockMovementUserTotal> {
    val seen = mutableSetOf<String>()
    movements.forEach { movement ->
        require(movement.movementId.isNotBlank() && seen.add(movement.movementId)) {
            "معرف حركة مخزون مفقود أو مكرر في تقرير المستخدمين."
        }
        require(movement.movementType.isNotBlank() && movement.quantityDelta.compareTo(java.math.BigDecimal.ZERO) != 0) {
            "توجد حركة مخزون بنوع أو كمية غير صالحة؛ تم حجب تقرير المستخدمين."
        }
    }
    return movements.groupBy {
        Triple(
            movementActorLabel(it.userId),
            it.movementType.trim(),
            it.unitName.trim().ifBlank { "وحدة غير محددة" }
        )
    }.map { (key, rows) ->
        StockMovementUserTotal(
            userId = key.first,
            movementType = key.second,
            unitName = key.third,
            movementCount = rows.size,
            quantityDelta = rows.fold(java.math.BigDecimal.ZERO) { total, row -> total.add(row.quantityDelta) }
        )
    }.sortedWith(
        compareBy<StockMovementUserTotal> { it.movementType }
            .thenBy { it.userId }
            .thenBy { it.unitName }
    )
}

private fun movementActorLabel(userId: String): String = userId.trim().ifBlank { "غير منسوب" }
