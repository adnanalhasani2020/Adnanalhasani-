package com.adnanalhasani.commercial

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Runs catalog requests off the main thread and returns their results to the UI thread.
 * Compose-owned state remains with the caller through the supplied callbacks.
 */
internal object CatalogRequestDispatcher {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun request(
        isBusy: Boolean,
        setBusy: (Boolean) -> Unit,
        setFeedback: (String) -> Unit,
        setRestoringSession: (Boolean) -> Unit,
        action: () -> String,
        onSuccess: (String) -> Unit,
        onFailure: ((Throwable) -> Unit)? = null
    ) {
        if (isBusy) return
        setBusy(true)
        setFeedback("")
        io.execute {
            val result = runCatching(action)
            main.post {
                setBusy(false)
                result.onSuccess(onSuccess).onFailure { error ->
                    if (onFailure != null) onFailure(error)
                    else setFeedback(error.message ?: "تعذر إكمال العملية.")
                    setRestoringSession(false)
                }
            }
        }
    }
}
