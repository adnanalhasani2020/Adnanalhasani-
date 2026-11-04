package com.adnanalhasani.commercial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun CatalogSelectionSummaryCard(
    selectedCount: Int,
    busy: Boolean,
    onConfirm: () -> Unit,
    onClear: () -> Unit
) {
    if (selectedCount == 0) return

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("تم تحديد $selectedCount صنفًا", fontWeight = FontWeight.SemiBold)
            Text(
                "سيتم ربطها بدليل المؤسسة دون تسعير، وستبقى غير متاحة للبيع حتى ضبط أسعارها.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onConfirm,
                    enabled = !busy && selectedCount <= 100
                ) {
                    Text("موافق — ربط بدون تسعير")
                }
                TextButton(onClick = onClear, enabled = !busy) {
                    Text("إلغاء التحديد")
                }
            }
        }
    }
}
