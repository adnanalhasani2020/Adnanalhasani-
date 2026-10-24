package com.adnanalhasani.commercial

internal fun aggregateSaleQuantities(lines: List<Pair<String, Int>>): Map<String, Int> {
    require(lines.isNotEmpty())
    val totals = linkedMapOf<String, Int>()
    lines.forEach { (productId, quantity) ->
        require(productId.isNotBlank() && quantity > 0)
        totals[productId] = Math.addExact(totals[productId] ?: 0, quantity)
    }
    return totals
}

internal fun validateAvailableStock(requested: Map<String, Int>, available: Map<String, Int>) {
    requested.forEach { (id, qty) -> require(qty > 0 && available[id] != null && qty <= available.getValue(id)) }
}
