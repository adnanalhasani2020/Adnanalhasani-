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
