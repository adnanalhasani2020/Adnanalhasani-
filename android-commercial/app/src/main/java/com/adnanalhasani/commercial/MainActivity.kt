package com.adnanalhasani.commercial

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Green,
                    background = Canvas,
                    surface = Color.White
                )
            ) {
                CommercialAppLock(applicationContext) { StoreApp(applicationContext) }
            }
        }
    }
}

internal val storeCloudIo = Executors.newSingleThreadExecutor()
internal val storeCloudMain = Handler(Looper.getMainLooper())
