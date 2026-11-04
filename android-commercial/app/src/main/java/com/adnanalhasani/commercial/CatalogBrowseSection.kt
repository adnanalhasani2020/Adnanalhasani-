package com.adnanalhasani.commercial

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Callback-driven browse filters and global catalog result list. */
@Composable
internal fun CatalogBrowseSection(
    items: List<CatalogProduct>,
    categories: List<CatalogCategory>,
    directoryUnits: List<DirectoryUnit>,
    query: String,
    onQueryChange: (String) -> Unit,
    sectorOptions: List<CatalogActivityNode>,
    sector: String,
    onSectorSelected: (String) -> Unit,
    sectorMenuExpanded: Boolean,
    onSectorMenuExpandedChange: (Boolean) -> Unit,
    businessLevelOptions: List<String>,
    businessLevel: String,
    onBusinessLevelSelected: (String) -> Unit,
    businessLevelMenuExpanded: Boolean,
    onBusinessLevelMenuExpandedChange: (Boolean) -> Unit,
    activityOptions: List<CatalogActivityNode>,
    selectedActivityCode: String,
    onActivitySelected: (CatalogActivityNode?) -> Unit,
    activityMenuExpanded: Boolean,
    onActivityMenuExpandedChange: (Boolean) -> Unit,
    country: String,
    onCountryChange: (String) -> Unit,
    region: String,
    onRegionChange: (String) -> Unit,
    city: String,
    onCityChange: (String) -> Unit,
    locale: String,
    onLocaleChange: (String) -> Unit,
    busy: Boolean,
    onSearch: () -> Unit,
    onRefreshDirectories: () -> Unit,
    listState: LazyListState,
    selectedCatalogIds: Set<String>,
    marketPriceHints: Map<String, String>,
    organizationId: String,
    requireCloudInventoryContext: Boolean,
    selectedBranchId: String,
    selectedWarehouseId: String,
    onSelectionChange: (CatalogProduct, Boolean) -> Unit,
    onFeedback: (String) -> Unit,
    onOpenSource: (String) -> Unit,
    onReviewRuling: (String) -> Unit,
    onAddToInventory: (CatalogProduct, String, String, String, String, String) -> Unit,
    onEditProduct: (CatalogProduct) -> Unit
) {
    LazyColumn(
        state=listState,
        verticalArrangement=Arrangement.spacedBy(8.dp),
        contentPadding=PaddingValues(bottom=20.dp)
    ) {
        item {
            OutlinedTextField(
                query,onQueryChange,Modifier.fillMaxWidth(),
                label={Text("بحث بالاسم أو الوصف أو الباركود أو الكود")},
                singleLine=true
            )
        }
        item {
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("تصفية الدليل حسب النشاط التجاري", style=MaterialTheme.typography.labelMedium)
                Box {
                    OutlinedButton(
                        onClick={onSectorMenuExpandedChange(true)},
                        enabled=!busy && sectorOptions.isNotEmpty(),
                        modifier=Modifier.fillMaxWidth()
                    ) {
                        Text(sectorOptions.firstOrNull { it.code.equals(sector,true) }?.pathAr ?: "كل القطاعات")
                    }
                    DropdownMenu(expanded=sectorMenuExpanded,onDismissRequest={onSectorMenuExpandedChange(false)}) {
                        DropdownMenuItem(text={Text("كل القطاعات")},onClick={onSectorSelected("")})
                        sectorOptions.forEach { option ->
                            DropdownMenuItem(text={Text(option.pathAr)},onClick={onSectorSelected(option.code)})
                        }
                    }
                }
                Box {
                    OutlinedButton(
                        onClick={onBusinessLevelMenuExpandedChange(true)},
                        enabled=!busy && businessLevelOptions.isNotEmpty(),
                        modifier=Modifier.fillMaxWidth()
                    ) {
                        Text(businessLevel.takeIf { it.isNotBlank() }?.let { "مستوى النشاط: ${catalogBusinessLevelLabel(it)}" } ?: "كل مستويات النشاط")
                    }
                    DropdownMenu(expanded=businessLevelMenuExpanded,onDismissRequest={onBusinessLevelMenuExpandedChange(false)}) {
                        DropdownMenuItem(text={Text("كل المستويات")},onClick={onBusinessLevelSelected("")})
                        businessLevelOptions.forEach { level ->
                            DropdownMenuItem(text={Text(catalogBusinessLevelLabel(level))},onClick={onBusinessLevelSelected(level)})
                        }
                    }
                }
                Text("اختر القطاع لتصفية الأنشطة ومستوياتها من الدليل الرسمي؛ لا تُكتب الرموز يدويًا.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("ربط الأصناف بنشاط تجاري محدد (اختياري)",style=MaterialTheme.typography.labelMedium)
                Box {
                    OutlinedButton(
                        onClick={onActivityMenuExpandedChange(true)},
                        enabled=!busy && activityOptions.isNotEmpty(),
                        modifier=Modifier.fillMaxWidth()
                    ) {
                        Text(activityOptions.firstOrNull { it.code==selectedActivityCode }?.pathAr ?: "اختر النشاط من الدليل")
                    }
                    DropdownMenu(expanded=activityMenuExpanded,onDismissRequest={onActivityMenuExpandedChange(false)}) {
                        DropdownMenuItem(text={Text("بدون إسناد لنشاط محدد")},onClick={onActivitySelected(null)})
                        activityOptions.forEach { node ->
                            DropdownMenuItem(text={Text(node.pathAr)},onClick={onActivitySelected(node)})
                        }
                    }
                }
                Text("تُعرض الأنشطة والتخصصات التابعة للقطاع المختار فقط، ويُضبط المستوى المقترح من بيانات الدليل.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(country,onCountryChange,Modifier.weight(1f),label={Text("الدولة")},singleLine=true)
                OutlinedTextField(region,onRegionChange,Modifier.weight(1f),label={Text("المنطقة")},singleLine=true)
                OutlinedTextField(city,onCityChange,Modifier.weight(1f),label={Text("المدينة")},singleLine=true)
            }
        }
        item {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(locale,onLocaleChange,Modifier.weight(1f),label={Text("اللغة")},singleLine=true)
                Button(onClick=onSearch,enabled=!busy) { Icon(Icons.Default.Search,null); Text("بحث") }
                OutlinedButton(onClick=onRefreshDirectories,enabled=!busy) { Text("تحديث الأدلة") }
            }
        }
        items(items,key={it.id}) { item ->
            CatalogProductResultCard(
                item=item,
                selected=item.id in selectedCatalogIds,
                marketPriceHint=marketPriceHints[item.id].orEmpty(),
                busy=busy,
                organizationId=organizationId,
                requireCloudInventoryContext=requireCloudInventoryContext,
                selectedBranchId=selectedBranchId,
                selectedWarehouseId=selectedWarehouseId,
                selectedActivityCode=selectedActivityCode,
                onSelectionChange={checked -> onSelectionChange(item,checked)},
                onFeedback=onFeedback,
                onOpenSource=onOpenSource,
                onReviewRuling=onReviewRuling,
                onAddToInventory=onAddToInventory,
                onEditProduct={onEditProduct(item)}
            )
        }
        if(items.isEmpty()) item {
            Text("ابحث عن أصناف أو اضغط تحديث الأدلة لعرض التصنيفات والوحدات المتاحة.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(categories.isNotEmpty()) item {
            Text("دليل التصنيفات: ${categories.size} تصنيفًا",style=MaterialTheme.typography.bodySmall)
        }
        if(directoryUnits.isNotEmpty()) item {
            Text("دليل الوحدات: ${directoryUnits.size} وحدة محمّلة",style=MaterialTheme.typography.bodySmall)
        }
    }
}
