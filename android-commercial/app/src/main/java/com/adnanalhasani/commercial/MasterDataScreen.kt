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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.Executors

private val masterIo = Executors.newSingleThreadExecutor()
private val masterMain = Handler(Looper.getMainLooper())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MasterDataScreen(context: Context, onBack: () -> Unit) {
    val options = listOf(
        "المستودعات" to "commercial_warehouses",
        "مجموعات الأصناف" to "commercial_product_categories",
        "الأقسام" to "commercial_departments",
        "مواقع التخزين" to "commercial_storage_locations"
    )
    var selected by remember { mutableStateOf(options.first()) }
    var rows by remember { mutableStateOf<List<CommercialMasterDataRow>>(emptyList()) }
    var warehouseRows by remember { mutableStateOf<List<CommercialMasterDataRow>>(emptyList()) }
    var warehouseId by remember { mutableStateOf("") }
    var warehouseMenuExpanded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("all") }
    var showCreate by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var warehouseType by remember { mutableStateOf("main") }
    var locationType by remember { mutableStateOf("internal") }
    var parentId by remember { mutableStateOf("") }
    var parentMenuExpanded by remember { mutableStateOf(false) }
    var showAssignDialog by remember { mutableStateOf(false) }
    var assignmentLoading by remember { mutableStateOf(false) }
    var assignmentSaving by remember { mutableStateOf(false) }
    var assignmentProducts by remember { mutableStateOf<List<Product>>(emptyList()) }
    var assignmentCategories by remember { mutableStateOf<List<CommercialMasterDataRow>>(emptyList()) }
    var selectedOfferingId by remember { mutableStateOf("") }
    var selectedCategoryId by remember { mutableStateOf("") }
    var assignmentProductMenuExpanded by remember { mutableStateOf(false) }
    var assignmentCategoryMenuExpanded by remember { mutableStateOf(false) }
    var showDepartmentWarehouseDialog by remember { mutableStateOf(false) }
    var departmentWarehouseLoading by remember { mutableStateOf(false) }
    var departmentWarehouseSaving by remember { mutableStateOf(false) }
    var departmentWarehouseDepartments by remember { mutableStateOf<List<CommercialMasterDataRow>>(emptyList()) }
    var departmentWarehouseWarehouses by remember { mutableStateOf<List<CommercialMasterDataRow>>(emptyList()) }
    var departmentWarehouseAssignments by remember { mutableStateOf<List<CommercialDepartmentWarehouseAssignment>>(emptyList()) }
    var selectedDepartmentId by remember { mutableStateOf("") }
    var selectedDepartmentWarehouseId by remember { mutableStateOf("") }
    var departmentMenuExpanded by remember { mutableStateOf(false) }
    var departmentWarehouseMenuExpanded by remember { mutableStateOf(false) }

    fun reload() {
        val table = selected.second
        loading = true
        error = ""
        masterIo.execute {
            val result = runCatching {
                val data = loadCommercialMasterData(context, table)
                val warehouses = if (table == "commercial_storage_locations") loadCommercialMasterData(context, "commercial_warehouses") else emptyList()
                data to warehouses
            }
            masterMain.post {
                if (selected.second == table) {
                    loading = false
                    result.onSuccess { rows = it.first; warehouseRows = it.second; if (it.second.isNotEmpty() && it.second.none { warehouse -> warehouse.id == warehouseId }) warehouseId = it.second.first().id; error = "" }
                        .onFailure { error = it.message ?: "تعذر تحميل البيانات." }
                }
            }
        }
    }
    LaunchedEffect(selected) { parentId = ""; reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("المستودعات ومواقع التخزين والتصنيفات", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } },
                actions = { IconButton(onClick = { reload() }, enabled = !loading) { Icon(Icons.Default.Refresh, "تحديث") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { code = ""; name = ""; locationType = "internal"; parentId = ""; showCreate = true }, containerColor = Color(0xFF167D63)) {
                Icon(Icons.Default.Add, "إضافة", tint = Color.White)
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(if (selected.second == "commercial_storage_locations") "مواقع تخزين مرتبطة بمستودع؛ لا تغيّر أرصدة المخزون من هذه الشاشة." else "بيانات محفوظة في المؤسسة السحابية، وليست بيانات محلية.", color = Color(0xFF71817B), fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    assignmentLoading = true
                    error = ""
                    masterIo.execute {
                        val result = runCatching {
                            val products = loadCommercialCloudInventory(context).products
                            val categories = loadCommercialMasterData(context, "commercial_product_categories").filter { it.kind == "active" }
                            products to categories
                        }
                        masterMain.post {
                            assignmentLoading = false
                            result.onSuccess { (products, categories) ->
                                assignmentProducts = products
                                assignmentCategories = categories
                                selectedOfferingId = products.firstOrNull()?.cloudOfferingId.orEmpty()
                                selectedCategoryId = categories.firstOrNull()?.id.orEmpty()
                                showAssignDialog = true
                                error = ""
                            }.onFailure { error = it.message ?: "تعذر تحميل الأصناف والتصنيفات." }
                        }
                    }
                },
                enabled = !assignmentLoading
            ) { Text(if (assignmentLoading) "جارٍ تحميل الأصناف..." else "تصنيف الأصناف الموجودة") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    departmentWarehouseLoading = true
                    error = ""
                    masterIo.execute {
                        val result = runCatching {
                            val departments = loadCommercialMasterData(context, "commercial_departments").filter { it.state == "active" }
                            val warehouses = loadCommercialMasterData(context, "commercial_warehouses").filter { it.state == "active" }
                            val assignments = loadCommercialDepartmentWarehouseAssignments(context)
                            Triple(departments, warehouses, assignments)
                        }
                        masterMain.post {
                            departmentWarehouseLoading = false
                            result.onSuccess { (departments, warehouses, assignments) ->
                                departmentWarehouseDepartments = departments
                                departmentWarehouseWarehouses = warehouses
                                departmentWarehouseAssignments = assignments
                                selectedDepartmentId = departments.firstOrNull()?.id.orEmpty()
                                selectedDepartmentWarehouseId = warehouses.firstOrNull()?.id.orEmpty()
                                showDepartmentWarehouseDialog = true
                                error = ""
                            }.onFailure { error = it.message ?: "تعذر تحميل ربط الأقسام بالمستودعات." }
                        }
                    }
                },
                enabled = !departmentWarehouseLoading
            ) { Text(if (departmentWarehouseLoading) "جارٍ تحميل الروابط..." else "ربط الأقسام بالمستودعات") }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    FilterChip(
                        selected = selected == option,
                        onClick = { selected = option },
                        label = { Text(option.first) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("بحث بالاسم أو الرمز أو النوع") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "الكل", "active" to "نشط", "inactive" to "غير نشط").forEach { (value, label) ->
                    FilterChip(
                        selected = statusFilter == value,
                        onClick = { statusFilter = value },
                        label = { Text(label) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("عدد السجلات: ${rows.size} · المعروض: ${rows.count { row ->
                (statusFilter == "all" || (statusFilter == "active" && row.state == "active") || (statusFilter == "inactive" && row.state != "active")) &&
                    (search.isBlank() || row.label.contains(search.trim(), ignoreCase = true) || row.code.contains(search.trim(), ignoreCase = true) || row.kind.contains(search.trim(), ignoreCase = true))
            }}", color = Color(0xFF71817B), fontSize = 12.sp)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 10.dp))
            if (notice.isNotBlank()) Text(notice, color = Color(0xFF167D63), modifier = Modifier.padding(vertical = 8.dp))
            val filteredRows = rows.filter { row ->
                val matchesStatus = statusFilter == "all" ||
                    (statusFilter == "active" && row.state == "active") ||
                    (statusFilter == "inactive" && row.state != "active")
                val query = search.trim()
                matchesStatus && (query.isBlank() || row.label.contains(query, ignoreCase = true) ||
                    row.code.contains(query, ignoreCase = true) ||
                    row.kind.contains(query, ignoreCase = true) ||
                    row.state.contains(query, ignoreCase = true))
            }
            if (!loading && error.isBlank() && rows.isEmpty()) {
                Text("لا توجد سجلات بعد. استخدم زر الإضافة لإنشاء أول سجل.", modifier = Modifier.padding(top = 24.dp))
            } else if (!loading && error.isBlank() && filteredRows.isEmpty()) {
                Text("لا توجد نتائج تطابق البحث.", modifier = Modifier.padding(top = 24.dp))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filteredRows, key = { it.id }) { row ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, Color(0xFFE5EBE8)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(row.label, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(3.dp))
                            Text("الرمز: ${row.code}", color = Color(0xFF71817B), fontSize = 12.sp)
                            if (row.kind.isNotBlank()) Text("النوع: ${row.kind}", color = Color(0xFF71817B), fontSize = 12.sp)
                            Text(
                                if (row.state == "active") "الحالة: نشط" else "الحالة: غير نشط (${row.state})",
                                color = if (row.state == "active") Color(0xFF167D63) else MaterialTheme.colorScheme.error,
                                fontSize = 12.sp
                            )
                            if (selected.second == "commercial_storage_locations") {
                                val warehouseLabel = warehouseRows.firstOrNull { it.id == row.warehouseId }?.label ?: "مستودع غير معروف"
                                Text("المستودع: $warehouseLabel", color = Color(0xFF71817B), fontSize = 12.sp)
                                val parentLabel = rows.firstOrNull { it.id == row.parentId }?.label
                                if (parentLabel != null) Text("الموقع الأب: $parentLabel", color = Color(0xFF71817B), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAssignDialog) {
        val selectedProduct = assignmentProducts.firstOrNull { it.cloudOfferingId == selectedOfferingId }
        val selectedCategory = assignmentCategories.firstOrNull { it.id == selectedCategoryId }
        AlertDialog(
            onDismissRequest = { if (!assignmentSaving) showAssignDialog = false },
            title = { Text("ربط تصنيف بصنف موجود") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (assignmentProducts.isEmpty()) {
                        Text("لا توجد أصناف سحابية نشطة في الموقع الحالي.")
                    } else {
                        Box {
                            OutlinedButton(onClick = { assignmentProductMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(selectedProduct?.name ?: "اختر الصنف")
                            }
                            DropdownMenu(expanded = assignmentProductMenuExpanded, onDismissRequest = { assignmentProductMenuExpanded = false }) {
                                assignmentProducts.filter { it.cloudOfferingId.isNotBlank() }.forEach { product ->
                                    DropdownMenuItem(
                                        text = { Text("${product.name} · ${product.category}") },
                                        onClick = { selectedOfferingId = product.cloudOfferingId; assignmentProductMenuExpanded = false }
                                    )
                                }
                            }
                        }
                    }
                    if (assignmentCategories.isEmpty()) {
                        Text("لا توجد تصنيفات نشطة. أنشئ تصنيفًا أولًا من دليل مجموعات الأصناف.")
                    } else {
                        Box {
                            OutlinedButton(onClick = { assignmentCategoryMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(selectedCategory?.let { "${it.code} — ${it.label}" } ?: "اختر التصنيف")
                            }
                            DropdownMenu(expanded = assignmentCategoryMenuExpanded, onDismissRequest = { assignmentCategoryMenuExpanded = false }) {
                                assignmentCategories.forEach { category ->
                                    DropdownMenuItem(
                                        text = { Text("${category.code} — ${category.label}") },
                                        onClick = { selectedCategoryId = category.id; assignmentCategoryMenuExpanded = false }
                                    )
                                }
                            }
                        }
                    }
                    Text("سيصبح التصنيف المختار هو التصنيف الأساسي. لا تتغير الكمية أو السعر أو حركة المخزون.", color = Color(0xFF71817B), fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !assignmentSaving && selectedProduct != null && selectedCategory != null,
                    onClick = {
                        val offeringId = selectedOfferingId
                        val categoryId = selectedCategoryId
                        assignmentSaving = true
                        masterIo.execute {
                            val result = runCatching { assignCommercialCloudOfferingCategory(context, offeringId, categoryId) }
                            masterMain.post {
                                assignmentSaving = false
                                result.onSuccess {
                                    showAssignDialog = false
                                    notice = "تم ربط التصنيف بالصنف وتسجيل العملية في سجل التدقيق."
                                    error = ""
                                }.onFailure { error = it.message ?: "تعذر ربط التصنيف." }
                            }
                        }
                    }
                ) { Text(if (assignmentSaving) "جارٍ الحفظ..." else "حفظ الربط") }
            },
            dismissButton = { TextButton(onClick = { if (!assignmentSaving) showAssignDialog = false }) { Text("إلغاء") } }
        )
    }

    if (showDepartmentWarehouseDialog) {
        val chosenDepartment = departmentWarehouseDepartments.firstOrNull { it.id == selectedDepartmentId }
        val chosenWarehouse = departmentWarehouseWarehouses.firstOrNull { it.id == selectedDepartmentWarehouseId }
        val visibleAssignments = departmentWarehouseAssignments.mapNotNull { assignment ->
            val department = departmentWarehouseDepartments.firstOrNull { it.id == assignment.departmentId }
            val warehouse = departmentWarehouseWarehouses.firstOrNull { it.id == assignment.warehouseId }
            if (department == null || warehouse == null) null else department to warehouse
        }
        AlertDialog(
            onDismissRequest = { if (!departmentWarehouseSaving) showDepartmentWarehouseDialog = false },
            title = { Text("ربط الأقسام بالمستودعات") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (departmentWarehouseDepartments.isEmpty()) {
                        Text("لا توجد أقسام نشطة. أنشئ قسمًا أولًا.")
                    } else {
                        Box {
                            OutlinedButton(onClick = { departmentMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(chosenDepartment?.let { "${it.code} — ${it.label}" } ?: "اختر القسم")
                            }
                            DropdownMenu(expanded = departmentMenuExpanded, onDismissRequest = { departmentMenuExpanded = false }) {
                                departmentWarehouseDepartments.forEach { department ->
                                    DropdownMenuItem(text = { Text("${department.code} — ${department.label}") }, onClick = {
                                        selectedDepartmentId = department.id
                                        departmentMenuExpanded = false
                                    })
                                }
                            }
                        }
                    }
                    if (departmentWarehouseWarehouses.isEmpty()) {
                        Text("لا توجد مستودعات نشطة. أنشئ مستودعًا أولًا.")
                    } else {
                        Box {
                            OutlinedButton(onClick = { departmentWarehouseMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(chosenWarehouse?.let { "${it.code} — ${it.label}" } ?: "اختر المستودع")
                            }
                            DropdownMenu(expanded = departmentWarehouseMenuExpanded, onDismissRequest = { departmentWarehouseMenuExpanded = false }) {
                                departmentWarehouseWarehouses.forEach { warehouse ->
                                    DropdownMenuItem(text = { Text("${warehouse.code} — ${warehouse.label}") }, onClick = {
                                        selectedDepartmentWarehouseId = warehouse.id
                                        departmentWarehouseMenuExpanded = false
                                    })
                                }
                            }
                        }
                    }
                    Text("الروابط الحالية: ${visibleAssignments.size}", fontWeight = FontWeight.SemiBold)
                    visibleAssignments.take(8).forEach { (department, warehouse) ->
                        Text("${department.label} ← ${warehouse.label}", color = Color(0xFF71817B), fontSize = 12.sp)
                    }
                    if (visibleAssignments.size > 8) Text("و${visibleAssignments.size - 8} روابط أخرى", color = Color(0xFF71817B), fontSize = 12.sp)
                    Text("يُحفظ الربط داخل المؤسسة الحالية، وتتحقق قاعدة البيانات من تطابق نطاق القسم والمستودع. لا تتغير أرصدة المخزون.", color = Color(0xFF71817B), fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !departmentWarehouseSaving && chosenDepartment != null && chosenWarehouse != null,
                    onClick = {
                        val departmentId = selectedDepartmentId
                        val warehouseId = selectedDepartmentWarehouseId
                        departmentWarehouseSaving = true
                        masterIo.execute {
                            val result = runCatching {
                                assignCommercialDepartmentToWarehouse(context, departmentId, warehouseId)
                                loadCommercialDepartmentWarehouseAssignments(context)
                            }
                            masterMain.post {
                                departmentWarehouseSaving = false
                                result.onSuccess { assignments ->
                                    departmentWarehouseAssignments = assignments
                                    notice = "تم حفظ ربط القسم بالمستودع أو كان الربط موجودًا بالفعل."
                                    error = ""
                                }.onFailure { error = it.message ?: "تعذر حفظ الربط." }
                            }
                        }
                    }
                ) { Text(if (departmentWarehouseSaving) "جارٍ الحفظ..." else "حفظ الربط") }
            },
            dismissButton = { TextButton(onClick = { if (!departmentWarehouseSaving) showDepartmentWarehouseDialog = false }) { Text("إغلاق") } }
        )
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("إضافة إلى ${selected.first}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("الرمز") }, singleLine = true)
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("الاسم") }, singleLine = true)
                    if (selected.second == "commercial_storage_locations") {
                        val warehouseLabel = warehouseRows.firstOrNull { it.id == warehouseId }?.let { "${it.code} — ${it.label}" } ?: "اختر المستودع"
                        Box {
                            OutlinedButton(onClick = { warehouseMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text("المستودع: $warehouseLabel") }
                            DropdownMenu(expanded = warehouseMenuExpanded, onDismissRequest = { warehouseMenuExpanded = false }) {
                                warehouseRows.forEach { warehouse -> DropdownMenuItem(text = { Text("${warehouse.code} — ${warehouse.label}") }, onClick = { warehouseId = warehouse.id; parentId = ""; warehouseMenuExpanded = false }) }
                            }
                        }
                    }
                    val parentRows = if (selected.second == "commercial_storage_locations") rows.filter { it.warehouseId == warehouseId } else rows
                    if (selected.second != "commercial_warehouses" && parentRows.isNotEmpty()) {
                        val parentLabel = parentRows.firstOrNull { it.id == parentId }?.let { "${it.code} — ${it.label}" } ?: "جذر رئيسي (بلا أب)"
                        Box {
                            OutlinedButton(onClick = { parentMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text("المستوى الأعلى: $parentLabel") }
                            DropdownMenu(expanded = parentMenuExpanded, onDismissRequest = { parentMenuExpanded = false }) {
                                DropdownMenuItem(text = { Text("جذر رئيسي (بلا أب)") }, onClick = { parentId = ""; parentMenuExpanded = false })
                                parentRows.forEach { parent -> DropdownMenuItem(text = { Text("${parent.code} — ${parent.label}") }, onClick = { parentId = parent.id; parentMenuExpanded = false }) }
                            }
                        }
                    }
                    if (selected.second == "commercial_storage_locations") {
                        Text("نوع موقع التخزين")
                        Column {
                            listOf(
                                "view" to "عرض/تجميع", "internal" to "تخزين داخلي",
                                "receiving" to "استلام", "packing" to "تجهيز",
                                "quality" to "فحص الجودة", "quarantine" to "حجر",
                                "transit" to "عبور", "customer" to "عميل",
                                "supplier" to "مورد", "scrap" to "تالف/خردة"
                            ).forEach { item ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = locationType == item.first, onClick = { locationType = item.first })
                                    Text(item.second)
                                }
                            }
                        }
                    }
                    if (selected.second == "commercial_warehouses") {
                        Text("نوع المستودع")
                        Column {
                            listOf("main" to "رئيسي", "retail" to "تجزئة", "distribution" to "توزيع", "production" to "إنتاج", "returns" to "مرتجعات", "quarantine" to "حجر", "virtual" to "افتراضي", "transit" to "عبور").forEach { item ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = warehouseType == item.first, onClick = { warehouseType = item.first })
                                    Text(item.second)
                                }
                            }
                        }
                    } else {
                        Text("يمكن بناء مستويات فرعية متعددة بلا حد ثابت عبر اختيار المستوى الأعلى.", fontSize = 12.sp, color = Color(0xFF71817B))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val table = selected.second
                    val submittedCode = code
                    val submittedName = name
                    val submittedParentId = parentId
                    val submittedWarehouseType = warehouseType
                    val submittedWarehouseId = warehouseId
                    val submittedLocationType = locationType
                    showCreate = false
                    notice = ""
                    masterIo.execute {
                        val result = runCatching {
                            createCommercialMasterData(context, table, submittedCode, submittedName, parentId = submittedParentId, warehouseType = submittedWarehouseType, warehouseId = submittedWarehouseId, locationType = submittedLocationType)
                            loadCommercialMasterData(context, table)
                        }
                        masterMain.post {
                            if (selected.second == table) {
                                result.onSuccess { rows = it; notice = "تم الحفظ في قاعدة البيانات السحابية."; error = "" }
                                    .onFailure { error = it.message ?: "تعذر الحفظ."; notice = "" }
                            }
                        }
                    }
                }, enabled = code.isNotBlank() && name.isNotBlank() && (selected.second != "commercial_storage_locations" || (warehouseId.isNotBlank() && warehouseRows.any { it.id == warehouseId }))) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("إلغاء") } }
        )
    }
}
