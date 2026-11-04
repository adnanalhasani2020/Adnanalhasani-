package com.adnanalhasani.commercial

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/** Owns the foreground permission and one-shot device-location flow for catalog discovery. */
@Composable
internal fun CatalogDeviceLocationController(
    context: Context,
    state: CatalogMarketUiState,
    onStartNearbyCatalog: () -> Unit,
    onFeedback: (String) -> Unit
): () -> Unit {
    val scope = rememberCoroutineScope()

    suspend fun updateNearbyFromDevice() {
        state.locatingDevice = true
        try {
            val location = getFreshCatalogDeviceLocation(context)
            state.deviceLatitude = location.latitude.toString()
            state.deviceLongitude = location.longitude.toString()
            onFeedback("تم تحديد موقعك لهذه الجلسة فقط. لا يتم حفظ الإحداثيات في ملفك أو تتبعك في الخلفية.")
            onStartNearbyCatalog()
        } catch (error: Exception) {
            onFeedback(error.message ?: "تعذر تحديد موقع الجهاز. تحقق من تشغيل خدمات الموقع.")
        } finally {
            state.locatingDevice = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scope.launch { updateNearbyFromDevice() }
        } else {
            onFeedback("لم تمنح إذن الموقع. يمكنك متابعة البحث باستخدام إحداثيات الفرع المحفوظة.")
        }
    }

    return {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            scope.launch { updateNearbyFromDevice() }
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
}
