package com.adnanalhasani.commercial

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Obtains a single fresh foreground location for nearby catalog discovery.
 *
 * Only ACCESS_COARSE_LOCATION is required. Coordinates are returned to the
 * caller for the active screen session and are not persisted by this helper.
 */
internal suspend fun getFreshCatalogDeviceLocation(context: Context): Location {
    if (ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        throw SecurityException("إذن الموقع التقريبي مطلوب لاكتشاف الأصناف القريبة.")
    }

    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }

    if (providers.isEmpty()) {
        throw IllegalStateException("فعّل خدمات الموقع في الهاتف ثم أعد المحاولة.")
    }

    for (provider in providers) {
        val candidate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            awaitCurrentLocation(manager, provider, context)
        } else {
            awaitLegacyLocation(manager, provider)
        } ?: continue

        val ageMillis = System.currentTimeMillis() - candidate.time
        if (ageMillis in 0..120_000L && candidate.latitude.isFinite() &&
            candidate.longitude.isFinite() && candidate.latitude in -90.0..90.0 &&
            candidate.longitude in -180.0..180.0
        ) {
            return candidate
        }
    }

    throw IllegalStateException("لم يصل موقع حديث من الهاتف. تحقق من تشغيل GPS أو الشبكة ثم أعد المحاولة.")
}

private suspend fun awaitCurrentLocation(
    manager: LocationManager,
    provider: String,
    context: Context
): Location? = withTimeoutOrNull(12_000L) {
    suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }
        try {
            manager.getCurrentLocation(
                provider,
                cancellation,
                context.mainExecutor,
                Executor { command -> command.run() }
            ) { location ->
                if (continuation.isActive) continuation.resume(location)
            }
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
}

@Suppress("DEPRECATION")
private suspend fun awaitLegacyLocation(
    manager: LocationManager,
    provider: String
): Location? = withTimeoutOrNull(15_000L) {
    suspendCancellableCoroutine { continuation ->
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                runCatching { manager.removeUpdates(this) }
                if (continuation.isActive) continuation.resume(location)
            }

            @Deprecated("Deprecated by Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        continuation.invokeOnCancellation {
            runCatching { manager.removeUpdates(listener) }
        }
        try {
            manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
}
