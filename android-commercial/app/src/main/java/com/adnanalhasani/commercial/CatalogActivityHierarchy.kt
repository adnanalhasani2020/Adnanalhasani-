package com.adnanalhasani.commercial

/** Activity-directory hierarchy and selector helpers; no network or UI state. */
internal data class CatalogActivityNode(val code: String, val parentCode: String, val nodeKind: String, val pathAr: String, val pathEn: String, val defaultBusinessLevelCode: String, val depth: Int, val selectable: Boolean)
internal fun catalogNodeBelongsToSector(node: CatalogActivityNode, sectorCode: String, nodes: List<CatalogActivityNode>): Boolean {
    if (sectorCode.isBlank()) return true
    val byCode = nodes.associateBy { it.code }
    var current: CatalogActivityNode? = node
    val visited = mutableSetOf<String>()
    while (current != null && visited.add(current.code)) {
        if (current.code.equals(sectorCode, ignoreCase = true)) return true
        current = byCode[current.parentCode]
    }
    return false
}
internal fun catalogBusinessLevelLabel(code: String): String = when (code.lowercase()) {
    "clinic" -> "عيادة / مركز صحي"
    "contractor" -> "مقاول"
    "factory" -> "مصنع"
    "grocery" -> "بقالة"
    "hotel" -> "فندق"
    "manufacturer" -> "منشأة تصنيع"
    "office" -> "مكتب"
    "other" -> "أخرى"
    "pharmacy" -> "صيدلية"
    "producer" -> "منتج / مزارع"
    "restaurant" -> "مطعم"
    "service" -> "مقدم خدمات"
    "shop" -> "محل تجاري"
    "supermarket" -> "سوبرماركت"
    "wholesale" -> "تجارة جملة"
    else -> code
}
internal fun catalogSectorForNode(nodeCode: String, nodes: List<CatalogActivityNode>): String {
    val byCode = nodes.associateBy { it.code }
    var current = byCode[nodeCode]
    val visited = mutableSetOf<String>()
    while (current != null && visited.add(current.code)) {
        if (current.nodeKind.equals("sector", ignoreCase = true)) return current.code
        current = byCode[current.parentCode]
    }
    return ""
}
