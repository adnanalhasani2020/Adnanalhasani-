package com.adnanalhasani.commercial

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.Locale
import java.util.Date
import java.text.SimpleDateFormat
import java.util.UUID
import java.util.concurrent.Executors
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Canvas, surface = Color.White)) { StoreApp(applicationContext) } }
    }
}

internal val storeCloudIo = Executors.newSingleThreadExecutor()
internal val storeCloudMain = Handler(Looper.getMainLooper())
