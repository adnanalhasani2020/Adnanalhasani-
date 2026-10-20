package com.adnanalhasani.hospital

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth

object FirebaseAuthGateway {
    fun isConfigured(context: Context): Boolean =
        FirebaseApp.getApps(context.applicationContext).isNotEmpty()

    fun currentUser(context: Context) =
        if (isConfigured(context)) FirebaseAuth.getInstance().currentUser else null

    fun signIn(context: Context, email: String, password: String, onResult: (Result<Unit>) -> Unit) {
        if (!isConfigured(context)) {
            onResult(Result.failure(IllegalStateException("Firebase غير مهيأ. أضف ملف google-services.json الخاص بالمشروع.")))
            return
        }
        FirebaseAuth.getInstance().signInWithEmailAndPassword(email.trim(), password)
            .addOnSuccessListener { onResult(Result.success(Unit)) }
            .addOnFailureListener { onResult(Result.failure(it)) }
    }

    fun register(context: Context, email: String, password: String, onResult: (Result<Unit>) -> Unit) {
        if (!isConfigured(context)) {
            onResult(Result.failure(IllegalStateException("Firebase غير مهيأ. أضف ملف google-services.json الخاص بالمشروع.")))
            return
        }
        FirebaseAuth.getInstance().createUserWithEmailAndPassword(email.trim(), password)
            .addOnSuccessListener { onResult(Result.success(Unit)) }
            .addOnFailureListener { onResult(Result.failure(it)) }
    }

    fun sendPasswordReset(context: Context, email: String, onResult: (Result<Unit>) -> Unit) {
        if (!isConfigured(context)) {
            onResult(Result.failure(IllegalStateException("Firebase غير مهيأ. أضف إعدادات Firebase أولاً.")))
            return
        }
        FirebaseAuth.getInstance().sendPasswordResetEmail(email.trim())
            .addOnSuccessListener { onResult(Result.success(Unit)) }
            .addOnFailureListener { onResult(Result.failure(it)) }
    }

    fun signOut(context: Context) {
        if (isConfigured(context)) FirebaseAuth.getInstance().signOut()
    }
}
