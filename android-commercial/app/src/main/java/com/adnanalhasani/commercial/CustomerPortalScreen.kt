package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import java.util.concurrent.Executors

private val PortalInk = Color(0xFF183B35)
private val PortalGreen = Color(0xFF167D63)
private val PortalMuted = Color(0xFF71817B)
private val PortalBorder = Color(0xFFE5EBE8)
private val portalExecutor = Executors.newSingleThreadExecutor()
private val portalMain = Handler(Looper.getMainLooper())

private fun portalMoney(amount: Long, currency: String) =
    String.format(Locale.US, "%,d %s", amount, currency)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomerPortalScreen(context: Context, onBack: () -> Unit, onOrders: () -> Unit = {}) {
    var organizations by remember { mutableStateOf<List<CustomerPortalOrganization>>(emptyList()) }
    var offerings by remember { mutableStateOf<List<CustomerPortalOffering>>(emptyList()) }
    var bookingResources by remember { mutableStateOf<List<CustomerPortalBookingResource>>(emptyList()) }
    var bookingMode by remember { mutableStateOf(false) }
    var bookingOfferingId by remember { mutableStateOf("") }
    var bookingResourceId by remember { mutableStateOf("") }
    var startsAt by remember { mutableStateOf("") }
    var endsAt by remember { mutableStateOf("") }
    var bookingResourceMenu by remember { mutableStateOf(false) }
    var bookingOfferingMenu by remember { mutableStateOf(false) }
    var selectedOrganization by remember { mutableStateOf<CustomerPortalOrganization?>(null) }
    var quantities by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var paymentMode by remember { mutableStateOf("cash") }
    var note by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var organizationMenu by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        loading = true
        error = ""
        portalExecutor.execute {
            val result = runCatching { loadCustomerPortalOrganizations(context) }
            portalMain.post {
                loading = false
                result.onSuccess { rows ->
                    organizations = rows
                    if (selectedOrganization == null || rows.none { it.id == selectedOrganization?.id }) {
                        selectedOrganization = null
                        offerings = emptyList()
                        quantities = emptyMap()
                    }
                    if (rows.isEmpty()) notice = "لا توجد مؤسسة مرتبطة بحساب الزبون الحالي. اطلب من المؤسسة ربط حسابك بملف العميل."
                }.onFailure { error = it.message ?: "تعذر تحميل المؤسسات المرتبطة بالحساب." }
            }
        }
    }

    LaunchedEffect(selectedOrganization?.id) {
        val org = selectedOrganization ?: return@LaunchedEffect
        loading = true
        error = ""
        portalExecutor.execute {
            val result = runCatching { loadCustomerPortalOfferings(context, org.id) }
            val resourcesResult = runCatching { loadCustomerPortalBookingResources(context, org.id) }
            portalMain.post {
                loading = false
                result.onSuccess { rows -> offerings = rows; quantities = emptyMap(); bookingOfferingId = rows.firstOrNull { it.itemType == "service" }?.id.orEmpty(); notice = "" }
                    .onFailure { offerings = emptyList(); error = it.message ?: "تعذر تحميل المنتجات والخدمات." }
                resourcesResult.onSuccess { bookingResources = it; bookingResourceId = it.firstOrNull()?.id.orEmpty() }
                    .onFailure { bookingResources = emptyList(); notice = it.message ?: "تعذر تحميل موارد الحجز." }
            }
        }
    }

    val org = selectedOrganization
    val cart = offerings.mapNotNull { item ->
        val text = quantities[item.id].orEmpty()
        val quantity = text.toBigDecimalOrNull()
        if (quantity == null || quantity <= java.math.BigDecimal.ZERO) null
        else Triple(item, text, kotlin.math.round(quantity.toDouble() * item.priceMinor).toLong())
    }
    val estimatedTotal = cart.sumOf { it.third }
    val bookingCart = offerings.filter { it.itemType == "service" }.mapNotNull { service ->
        val text = quantities[service.id].orEmpty()
        val quantity = text.toBigDecimalOrNull()
        if (quantity == null || quantity <= java.math.BigDecimal.ZERO) null
        else Triple(service, text, kotlin.math.round(quantity.toDouble() * service.priceMinor).toLong())
    }
    val bookingEstimatedTotal = bookingCart.sumOf { it.third }
    val bookingQuantitiesValid = bookingCart.all { (_, text, _) ->
        text.toBigDecimalOrNull()?.let { it > java.math.BigDecimal.ZERO && it.scale().coerceAtLeast(0) <= 3 && it <= java.math.BigDecimal("100000") } == true
    } && bookingCart.map { it.first.currencyCode }.distinct().size <= 1
    val unsupportedFractionalProduct = cart.any { (item, text, _) ->
        item.itemType == "product" && text.toBigDecimalOrNull()?.let { quantity ->
            quantity.stripTrailingZeros().scale() > 0 || quantity > java.math.BigDecimal("100000")
        } == true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("بوابة الزبون", color = PortalInk, fontWeight = FontWeight.Bold); Text("المؤسسات والطلبات السحابية", color = PortalMuted, fontSize = 11.sp) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "رجوع", tint = PortalInk) } },
                actions = {
                    IconButton(onClick = onOrders) { Icon(Icons.Default.History, contentDescription = "طلباتي وحجوزاتي", tint = PortalInk) }
                    IconButton(onClick = { refresh++ }) { Icon(Icons.Default.Refresh, contentDescription = "تحديث", tint = PortalInk) }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, PortalBorder)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Business, contentDescription = null, tint = PortalGreen)
                            Spacer(Modifier.width(8.dp))
                            Text("اختر المؤسسة", color = PortalInk, fontWeight = FontWeight.Bold)
                        }
                        Box {
                            OutlinedButton(onClick = { organizationMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(org?.name ?: "المؤسسات المرتبط بها حسابك", modifier = Modifier.weight(1f))
                            }
                            DropdownMenu(expanded = organizationMenu, onDismissRequest = { organizationMenu = false }) {
                                organizations.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Column { Text(option.name); if (option.description.isNotBlank()) Text(option.description, color = PortalMuted, fontSize = 11.sp) } },
                                        onClick = { selectedOrganization = option; organizationMenu = false; note = ""; paymentMode = "cash"; notice = "" }
                                    )
                                }
                            }
                        }
                        if (org != null) {
                            Text("العملة: ${org.currencyCode}", color = PortalMuted, fontSize = 12.sp)
                            Text(if (org.allowCredit) "يمكنك طلب فاتورة آجلة حسب موافقة الخادم." else "الشراء الآجل غير مفعّل لحسابك في هذه المؤسسة.", color = PortalMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (error.isNotBlank()) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = { refresh++ }) { Text("إعادة المحاولة") }
                    }
                }
            }
            if (notice.isNotBlank() && error.isBlank()) item { Text(notice, color = PortalMuted, fontSize = 12.sp) }
            if (org != null && offerings.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("المنتجات والخدمات", color = PortalInk, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        FilterChip(selected = !bookingMode, onClick = { bookingMode = false }, label = { Text("طلب") })
                        Spacer(Modifier.width(6.dp))
                        FilterChip(selected = bookingMode, onClick = { bookingMode = true }, label = { Text("حجز") })
                    }
                }
                if (bookingMode) item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, PortalBorder)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("تفاصيل الحجز", color = PortalInk, fontWeight = FontWeight.Bold)
                            Text("اختر الخدمات وكميات كل بند", color = PortalMuted, fontSize = 12.sp)
                            offerings.filter { it.itemType == "service" }.forEach { service ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FBF9)),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, PortalBorder)
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(service.name, color = PortalInk, fontWeight = FontWeight.SemiBold)
                                                if (service.description.isNotBlank()) Text(service.description, color = PortalMuted, fontSize = 11.sp)
                                            }
                                            Text(portalMoney(service.priceMinor, service.currencyCode), color = PortalGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                        OutlinedTextField(
                                            value = quantities[service.id].orEmpty(),
                                            onValueChange = { value ->
                                                quantities = quantities.toMutableMap().apply { put(service.id, value.filter { it.isDigit() || it == '.' }.take(12)) }
                                                error = ""
                                            },
                                            label = { Text("الكمية (اتركها فارغة إذا لم تختر الخدمة)") },
                                            placeholder = { Text("مثال: 1") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                            Box {
                                OutlinedButton(onClick = { bookingResourceMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text(bookingResources.firstOrNull { it.id == bookingResourceId }?.let { "${it.name} · السعة ${it.capacity}" } ?: "اختر مورد الحجز", modifier = Modifier.weight(1f))
                                }
                                DropdownMenu(expanded = bookingResourceMenu, onDismissRequest = { bookingResourceMenu = false }) {
                                    bookingResources.forEach { resource ->
                                        DropdownMenuItem(text = { Text("${resource.name} · ${resource.resourceType} · ${resource.timezone}") }, onClick = { bookingResourceId = resource.id; bookingResourceMenu = false })
                                    }
                                }
                            }
                            OutlinedTextField(startsAt, { startsAt = it.take(40) }, label = { Text("بداية الحجز ISO-8601 مع المنطقة الزمنية") }, placeholder = { Text("2026-10-27T10:00:00+03:00") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(endsAt, { endsAt = it.take(40) }, label = { Text("نهاية الحجز ISO-8601 مع المنطقة الزمنية") }, placeholder = { Text("2026-10-27T11:00:00+03:00") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Text("لا يُثبت الموعد إلا بعد فحص السعة والتداخل في قاعدة البيانات.", color = PortalMuted, fontSize = 11.sp)
                            if (offerings.none { it.itemType == "service" }) Text("لا توجد خدمات نشطة يمكن حجزها.", color = PortalMuted)
                            if (bookingResources.isEmpty()) Text("لا توجد موارد حجز نشطة لدى المؤسسة.", color = PortalMuted)
                        }
                    }
                }
                if (!bookingMode && offerings.none { it.itemType == "product" }) item {
                    Text("لا توجد منتجات قابلة للشراء المباشر. استخدم وضع الحجز للخدمات.", color = PortalMuted, fontSize = 12.sp)
                }
                if (!bookingMode) items(offerings.filter { it.itemType == "product" }, key = { it.id }) { item ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, PortalBorder)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, color = PortalInk, fontWeight = FontWeight.SemiBold)
                                    Text(if (item.itemType == "service") "خدمة" else item.unitName, color = PortalMuted, fontSize = 11.sp)
                                }
                                Text(portalMoney(item.priceMinor, item.currencyCode), color = PortalGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            if (item.description.isNotBlank()) Text(item.description, color = PortalMuted, fontSize = 12.sp)
                            OutlinedTextField(
                                value = quantities[item.id].orEmpty(),
                                onValueChange = { value ->
                                    quantities = quantities.toMutableMap().apply { put(item.id, value.filter { it.isDigit() || it == '.' }.take(12)) }
                                    error = ""
                                },
                                label = { Text("الكمية") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                if (!bookingMode) item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, PortalBorder)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = PortalGreen)
                                Spacer(Modifier.width(8.dp))
                                Text("مراجعة الطلب", color = PortalInk, fontWeight = FontWeight.Bold)
                            }
                            if (cart.isEmpty()) Text("أدخل كمية أكبر من صفر لصنف واحد على الأقل.", color = PortalMuted, fontSize = 12.sp)
                            cart.forEach { (item, quantity, total) ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${item.name} × $quantity", color = PortalInk, modifier = Modifier.weight(1f))
                                    Text(portalMoney(total, item.currencyCode), color = PortalInk, fontWeight = FontWeight.Medium)
                                }
                            }
                            HorizontalDivider(color = PortalBorder)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("الإجمالي التقديري", color = PortalMuted)
                                Text(portalMoney(estimatedTotal, org.currencyCode), color = PortalGreen, fontWeight = FontWeight.Bold)
                            }
                            Text("الإجمالي النهائي يُحسب ويُتحقق منه داخل قاعدة البيانات عند الإرسال.", color = PortalMuted, fontSize = 11.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = paymentMode == "cash", onClick = { paymentMode = "cash" })
                                Text("نقدي", color = PortalInk)
                                Spacer(Modifier.width(14.dp))
                                RadioButton(selected = paymentMode == "credit", onClick = { paymentMode = "credit" }, enabled = org.allowCredit)
                                Text("آجل", color = if (org.allowCredit) PortalInk else PortalMuted)
                            }
                            OutlinedTextField(value = note, onValueChange = { note = it.take(1000) }, label = { Text("ملاحظة للطلب (اختياري)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                            Button(
                                onClick = {
                                    val invalid = cart.any { (_, text, _) -> text.toBigDecimalOrNull()?.let { it <= java.math.BigDecimal.ZERO || it.scale().coerceAtLeast(0) > 3 } != false } ||
                                        cart.map { it.first.currencyCode }.distinct().size != 1
                                    if (bookingMode) {
                                        val service = offerings.firstOrNull { it.id == bookingOfferingId && it.itemType == "service" }
                                        val startsValid = runCatching { java.time.OffsetDateTime.parse(startsAt).toInstant().isAfter(java.time.Instant.now()) }.getOrDefault(false)
                                        val endsValid = runCatching { java.time.OffsetDateTime.parse(endsAt).toInstant().isAfter(java.time.OffsetDateTime.parse(startsAt).toInstant()) }.getOrDefault(false)
                                        if (service == null || bookingResourceId.isBlank() || bookingResources.none { it.id == bookingResourceId } || !startsValid || !endsValid) {
                                            error = "اختر خدمة وموردًا نشطًا وأدخل وقت بداية ونهاية صالحين في المستقبل مع المنطقة الزمنية."
                                        } else {
                                            submitting = true; error = ""; notice = "جارٍ التحقق من الموعد والسعة وتسجيل الحجز..." 
                                            val snapshotOrg = org; val snapshotService = service; val snapshotResource = bookingResourceId
                                            val snapshotStart = startsAt; val snapshotEnd = endsAt; val snapshotPayment = paymentMode; val snapshotNote = note
                                            portalExecutor.execute {
                                                val result = runCatching {
                                                    val key = getOrCreateCustomerPortalOrderKey(context, snapshotOrg.id, listOf(snapshotService.id to "1"), snapshotPayment, snapshotNote, listOf(snapshotResource, snapshotStart, snapshotEnd))
                                                    val booking = submitCustomerPortalBooking(context, snapshotOrg.id, snapshotService.id, snapshotResource, snapshotStart, snapshotEnd, snapshotPayment, snapshotNote, key)
                                                    clearCustomerPortalOrderKey(context, key); booking
                                                }
                                                portalMain.post {
                                                    submitting = false
                                                    result.onSuccess { booking -> notice = "تم إرسال طلب الحجز رقم ${booking.bookingNumber}. الحالة: ${booking.status}، القيمة: ${portalMoney(booking.priceMinor, booking.currencyCode)}."; error = "" }
                                                        .onFailure { error = it.message ?: "تعذر تأكيد الحجز. أعد المحاولة بالبيانات نفسها."; notice = "" }
                                                }
                                            }
                                        }
                                    } else if (cart.isEmpty() || invalid) {
                                        error = "تحقق من الكميات (حتى 3 منازل عشرية) وتأكد من اختيار أصناف بعملة واحدة."
                                    } else if (unsupportedFractionalProduct) {
                                        error = "البيع السحابي يدعم حاليًا كميات المنتجات الصحيحة من 1 إلى 100000 فقط. عدّل الكمية؛ لن يتم إنشاء طلب غير قابل للإتمام."
                                    } else if (estimatedTotal <= 0L) {
                                        error = "إجمالي الطلب يجب أن يكون أكبر من صفر."
                                    } else {
                                        submitting = true
                                        error = ""
                                        notice = "جارٍ إرسال الطلب والتحقق من الأسعار والصلاحيات على الخادم..."
                                        val snapshotOrg = org
                                        val snapshotLines = cart.map { it.first.id to it.second }
                                        val snapshotPayment = paymentMode
                                        val snapshotNote = note
                                        portalExecutor.execute {
                                            val result = runCatching {
                                                val key = getOrCreateCustomerPortalOrderKey(context, snapshotOrg.id, snapshotLines, snapshotPayment, snapshotNote)
                                                val order = submitCustomerPortalOrder(context, snapshotOrg.id, snapshotLines, snapshotPayment, snapshotNote, key)
                                                clearCustomerPortalOrderKey(context, key)
                                                order
                                            }
                                            portalMain.post {
                                                submitting = false
                                                result.onSuccess { order ->
                                                    quantities = emptyMap()
                                                    note = ""
                                                    notice = "تم تسجيل الطلب المبدئي رقم ${order.orderNumber} للمراجعة. الإجمالي المسجل: ${portalMoney(order.totalMinor, order.currencyCode)}. لم يُحجز المخزون ولم يكتمل البيع أو التحصيل بعد.${if (order.replayed) " (استرجاع نتيجة محاولة سابقة)" else ""}"
                                                }.onFailure {
                                                    error = it.message ?: "تعذر تأكيد حفظ الطلب. أعد المحاولة دون تغيير السلة."
                                                    notice = ""
                                                }
                                            }
                                        }
                                    }
                                },
                                enabled = !submitting && !loading && (if (bookingMode) offerings.any { it.itemType == "service" } && bookingResources.isNotEmpty() else cart.isNotEmpty()),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (submitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Text(if (bookingMode) "إرسال طلب الحجز" else "إرسال الطلب")
                            }
                        }
                    }
                }
                if (bookingMode) item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, PortalBorder)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("فاتورة الحجز الذكية", color = PortalInk, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            if (bookingCart.isEmpty()) {
                                Text("أدخل كمية أكبر من صفر لخدمة واحدة على الأقل.", color = PortalMuted, fontSize = 12.sp)
                            } else {
                                bookingCart.forEach { (service, quantity, total) ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(service.name, color = PortalInk, fontWeight = FontWeight.SemiBold)
                                            Text("الكمية: $quantity × ${portalMoney(service.priceMinor, service.currencyCode)}", color = PortalMuted, fontSize = 11.sp)
                                        }
                                        Text(portalMoney(total, service.currencyCode), color = PortalInk, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                            HorizontalDivider(color = PortalBorder)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("الإجمالي التقديري", color = PortalMuted)
                                Text(portalMoney(bookingEstimatedTotal, org.currencyCode), color = PortalGreen, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            }
                            Text("كل البنود تُحفظ تحت حجز واحد. السعر والإجمالي النهائيان يُعاد احتسابهما والتحقق منهما داخل قاعدة البيانات.", color = PortalMuted, fontSize = 11.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = paymentMode == "cash", onClick = { paymentMode = "cash" })
                                Text("نقدي", color = PortalInk)
                                Spacer(Modifier.width(14.dp))
                                RadioButton(selected = paymentMode == "credit", onClick = { paymentMode = "credit" }, enabled = org.allowCredit)
                                Text("آجل", color = if (org.allowCredit) PortalInk else PortalMuted)
                            }
                            OutlinedTextField(value = note, onValueChange = { note = it.take(1000) }, label = { Text("ملاحظة للحجز (اختياري)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                            Button(
                                onClick = {
                                    val startsValid = runCatching { java.time.OffsetDateTime.parse(startsAt).toInstant().isAfter(java.time.Instant.now()) }.getOrDefault(false)
                                    val endsValid = runCatching { java.time.OffsetDateTime.parse(endsAt).toInstant().isAfter(java.time.OffsetDateTime.parse(startsAt).toInstant()) }.getOrDefault(false)
                                    if (bookingCart.isEmpty() || !bookingQuantitiesValid) {
                                        error = "اختر خدمة واحدة على الأقل، وتأكد من أن الكميات موجبة وبحد أقصى 3 منازل عشرية وبعملة واحدة."
                                    } else if (bookingResourceId.isBlank() || bookingResources.none { it.id == bookingResourceId } || !startsValid || !endsValid) {
                                        error = "اختر موردًا نشطًا وأدخل وقت بداية ونهاية صالحين في المستقبل مع المنطقة الزمنية."
                                    } else if (bookingEstimatedTotal <= 0L) {
                                        error = "إجمالي الحجز يجب أن يكون أكبر من صفر."
                                    } else {
                                        submitting = true
                                        error = ""
                                        notice = "جارٍ التحقق من الخدمات والأسعار والصلاحيات والسعة وتسجيل الحجز..."
                                        val snapshotOrg = org
                                        val snapshotLines = bookingCart.map { it.first.id to it.second }
                                        val snapshotResource = bookingResourceId
                                        val snapshotStart = startsAt
                                        val snapshotEnd = endsAt
                                        val snapshotPayment = paymentMode
                                        val snapshotNote = note
                                        portalExecutor.execute {
                                            val result = runCatching {
                                                val key = getOrCreateCustomerPortalOrderKey(
                                                    context, snapshotOrg.id, snapshotLines, snapshotPayment, snapshotNote,
                                                    listOf(snapshotResource, snapshotStart, snapshotEnd)
                                                )
                                                val booking = submitCustomerPortalMultiServiceBooking(
                                                    context, snapshotOrg.id, snapshotLines, snapshotResource,
                                                    snapshotStart, snapshotEnd, snapshotPayment, snapshotNote, key
                                                )
                                                clearCustomerPortalOrderKey(context, key)
                                                booking
                                            }
                                            portalMain.post {
                                                submitting = false
                                                result.onSuccess { booking ->
                                                    quantities = quantities.filterKeys { id -> offerings.any { it.id == id && it.itemType == "product" } }
                                                    notice = "تم تسجيل الحجز رقم ${booking.bookingNumber} وفيه ${snapshotLines.size} بندًا. الإجمالي المحفوظ: ${portalMoney(booking.priceMinor, booking.currencyCode)}.${if (booking.replayed) " استُرجعت نتيجة المحاولة السابقة." else ""}"
                                                    error = ""
                                                }.onFailure {
                                                    error = it.message ?: "تعذر تأكيد الحجز. أعد المحاولة بالبيانات نفسها دون تغيير البنود."
                                                    notice = ""
                                                }
                                            }
                                        }
                                    }
                                },
                                enabled = !submitting && !loading && bookingCart.isNotEmpty() &&
                                    bookingQuantitiesValid && bookingResources.any { it.id == bookingResourceId },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (submitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Text("تأكيد الحجز وإصدار الفاتورة")
                            }
                        }
                    }
                }
            } else if (org != null && !loading && error.isBlank()) {
                item { Text("لا توجد منتجات أو خدمات نشطة متاحة حاليًا لهذه المؤسسة.", color = PortalMuted) }
            }
        }
    }
}
