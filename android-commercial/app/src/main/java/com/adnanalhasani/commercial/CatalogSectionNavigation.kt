package com.adnanalhasani.commercial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun CatalogSectionNavigation(
    selectedSection: String,
    onSectionSelected: (String) -> Unit
) {
    val sections = listOf(
        "browse" to "بحث",
        "product" to "صنف جديد",
        "category" to "تصنيف",
        "unit" to "وحدة",
        "transfer" to "نقل",
        "invoice_transfer" to "فاتورة بيع → مشتريات"
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        sections.forEach { (key, label) ->
            FilterChip(
                selected = selectedSection == key,
                onClick = { onSectionSelected(key) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}
