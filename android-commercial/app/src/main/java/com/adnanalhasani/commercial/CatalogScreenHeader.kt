package com.adnanalhasani.commercial

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

@Composable
internal fun CatalogScreenHeader(
    onBack: () -> Unit,
    signedIn: Boolean,
    onLogout: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
        }
        Column(Modifier.weight(1f)) {
            Text(
                "دليل الأصناف والوحدات",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "دليل متعدد الأنشطة والمستويات والمناطق",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (signedIn) {
            TextButton(onClick = onLogout) { Text("تسجيل الخروج") }
        }
    }
}
