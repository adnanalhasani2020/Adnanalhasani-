package com.adnanalhasani.commercial

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Branch configuration and nearby-market discovery presentation.
 *
 * All network operations and location-permission workflows are supplied by the
 * owning screen as callbacks; this section only renders and updates UI state.
 */
@Composable
internal fun CatalogMarketDiscoverySection(
    state: CatalogMarketUiState,
    selectedCatalogIds: Set<String>,
    busy: Boolean,
    nearbyListState: androidx.compose.foundation.lazy.LazyListState,
    onToggleCatalogItem: (String) -> Unit,
    onSaveCoordinates: (String?, String?) -> Unit,
    onLoadMarketContext: () -> Unit,
    onLoadNearbyFromDevice: () -> Unit,
    onStartNearbyCatalog: () -> Unit,
    onLoadSelectedPriceSuggestions: () -> Unit,
    onCreateProduct: () -> Unit,
    onConfigureSelectedCatalogItems: () -> Unit,
    onLoadNearbyCatalog: (Boolean) -> Unit
) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("الفرع والمستودع واكتشاف السوق",fontWeight=FontWeight.SemiBold)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.weight(1f)) {
                                    OutlinedButton(onClick={state.branchMenuExpanded=true},modifier=Modifier.fillMaxWidth(),enabled=!busy) {
                                        Text(state.marketBranches.firstOrNull { it.id==state.selectedBranchId }?.name ?: "اختر الفرع")
                                    }
                                    DropdownMenu(expanded=state.branchMenuExpanded,onDismissRequest={state.branchMenuExpanded=false}) {
                                        state.marketBranches.forEach { branch -> DropdownMenuItem(text={Text(branch.name)},onClick={
                                            state.selectedBranchId=branch.id
                                            state.branchLatitude=branch.latitude
                                            state.branchLongitude=branch.longitude
                                            state.selectedWarehouseId=state.marketWarehouses.firstOrNull { it.branchId==branch.id }?.id.orEmpty()
                                            state.nearbyMarketItems=emptyList(); state.nearbyOffset=0; state.nearbyHasMore=true; state.nearbyLoadGeneration++; state.marketRadius=5000; state.marketPriceHints=emptyMap(); state.branchMenuExpanded=false
                                        }) }
                                    }
                                }
                                Box(Modifier.weight(1f)) {
                                    OutlinedButton(onClick={state.warehouseMenuExpanded=true},modifier=Modifier.fillMaxWidth(),enabled=!busy && state.selectedBranchId.isNotBlank()) {
                                        Text(state.marketWarehouses.firstOrNull { it.id==state.selectedWarehouseId }?.name ?: "اختر المستودع")
                                    }
                                    DropdownMenu(expanded=state.warehouseMenuExpanded,onDismissRequest={state.warehouseMenuExpanded=false}) {
                                        state.marketWarehouses.filter { it.branchId==state.selectedBranchId }.forEach { warehouse ->
                                            DropdownMenuItem(text={Text(warehouse.name)},onClick={state.selectedWarehouseId=warehouse.id;state.warehouseMenuExpanded=false})
                                        }
                                    }
                                }
                            }
                            Text("إحداثيات الفرع المحدد",fontWeight=FontWeight.SemiBold)
                            Text("أدخل إحداثيات الموقع يدويًا لاستخدام اكتشاف السوق القريب. اترك الحقلين فارغين لمسح الموقع.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(state.branchLatitude,{state.branchLatitude=it},Modifier.weight(1f),label={Text("خط العرض Latitude")},singleLine=true)
                                OutlinedTextField(state.branchLongitude,{state.branchLongitude=it},Modifier.weight(1f),label={Text("خط الطول Longitude")},singleLine=true)
                            }
                            val parsedLatitude=state.branchLatitude.trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
                            val parsedLongitude=state.branchLongitude.trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
                            val coordinatesCleared=state.branchLatitude.isBlank() && state.branchLongitude.isBlank()
                            val coordinatesValid=coordinatesCleared || (
                                parsedLatitude!=null && parsedLongitude!=null &&
                                parsedLatitude >= java.math.BigDecimal("-90") && parsedLatitude <= java.math.BigDecimal("90") &&
                                parsedLongitude >= java.math.BigDecimal("-180") && parsedLongitude <= java.math.BigDecimal("180")
                            )
                            Button(
                                onClick={onSaveCoordinates(parsedLatitude?.toPlainString(), parsedLongitude?.toPlainString())},
                                enabled=!busy && state.state.selectedBranchId.isNotBlank() && coordinatesValid,
                                modifier=Modifier.fillMaxWidth()
                            ) { Text(if(coordinatesCleared) "مسح الإحداثيات" else "حفظ إحداثيات الفرع") }
                            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick={onLoadMarketContext()},enabled=!busy) { Text("تحديث الفروع") }
                                Button(onClick={onLoadNearbyFromDevice()},enabled=!busy && !state.locatingDevice) { Text(if(state.locatingDevice) "جارٍ تحديد الموقع..." else "موقعي الحالي") }
                            }
                            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick={state.deviceLatitude=null;state.deviceLongitude=null;onStartNearbyCatalog()},enabled=!busy && state.selectedBranchId.isNotBlank()) { Text("حسب موقع الفرع") }
                                OutlinedButton(onClick={onLoadSelectedPriceSuggestions()},enabled=!busy && selectedCatalogIds.isNotEmpty()) { Text("أسعار استرشادية") }
                            }
                            OutlinedButton(
                                onClick={onCreateProduct()},
                                enabled=!busy,
                                modifier=Modifier.fillMaxWidth()
                            ) { Text("إنشاء صنف جديد") }
                            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                Button(onClick={onConfigureSelectedCatalogItems()},enabled=!busy && selectedCatalogIds.isNotEmpty() && selectedCatalogIds.size<=100 && selectedActivityCode.isNotBlank() && state.selectedBranchId.isNotBlank() && state.selectedWarehouseId.isNotBlank()) { Text("تهيئة المختار للفرع") }
                                OutlinedButton(onClick={onLoadNearbyCatalog(false)},enabled=!busy && state.nearbyHasMore && !nearbyPageLoadFailed) { Text("تحميل المزيد") }
                                if(nearbyPageLoadFailed) OutlinedButton(onClick={onLoadNearbyCatalog(!state.nearbyHasMore)},enabled=!busy) { Text("إعادة المحاولة") }
                                OutlinedButton(onClick={onLoadNearbyCatalog(true)},enabled=!busy && state.marketRadius<100000 && !state.nearbyHasMore) { Text("توسيع النطاق") }
                            }
                            if(state.nearbyMarketItems.isNotEmpty()) {
                                Text("الأكثر استخدامًا في السوق القريب • نطاق "+(state.marketRadius/1000)+" كم",fontWeight=FontWeight.SemiBold)
                                LazyColumn(state=nearbyListState,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp,max=220.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                    items(state.nearbyMarketItems,key={it.id}) { market ->
                                        FilterChip(
                                            selected=market.id in selectedCatalogIds,
                                            onClick={
                                                onToggleCatalogItem(market.id)
                                            },
                                            label={Text(market.name+" • "+market.popularityBand+" • تجار "+market.merchantCountBand)}
                                        )
                                    }
                                    if(busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                                    if(!state.nearbyHasMore && state.marketRadius<100000) item { Text("اسحب قائمة السوق لتوسيع البحث إلى المنطقة التالية.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                                    if(state.marketRadius>=100000 && !state.nearbyHasMore) item { Text("وصل البحث إلى أقصى نطاق 100 كم.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                            Text(if(state.deviceLatitude!=null && state.deviceLongitude!=null) "مركز البحث: موقع الجهاز الحالي لهذه الجلسة. استخدم «حسب موقع الفرع» للعودة إلى إحداثيات الفرع." else "مركز البحث الافتراضي: إحداثيات الفرع المحفوظة. يمكنك اختيار موقع الجهاز بإذن اختياري لمرة واحدة.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("لا يعمل الموقع في الخلفية ولا تُحفظ إحداثيات الجهاز. الأسعار لا تظهر إلا عند توافر بيانات مجمعة موثوقة من خمسة تجار مستقلين على الأقل.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
}
