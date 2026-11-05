package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

private val SupplyInk = Color(0xFF183B35)
private val SupplyMuted = Color(0xFF71817B)
private val SupplyBorder = Color(0xFFE5EBE8)
private val supplyIo = Executors.newSingleThreadExecutor()
private val supplyMain = Handler(Looper.getMainLooper())

private data class CustomerSupplyRow(
    val id:String,val customer:String,val name:String,val specs:String,val quantity:String,
    val unit:String,val price:Long,val currency:String,val payment:String,val note:String,
    val status:String,val merchantNote:String,val directSaleStatus:String,val saleId:String
)

private fun loadCustomerSupplyRows(context:Context):List<CustomerSupplyRow>{
    val (orgId,access)=commercialMasterDataAccess(context)
    val rows=JSONArray(SupabaseClient.request("/rest/v1/rpc/commercial_customer_portal_list_organization_supply_requests","POST",
        JSONObject().put("p_organization_id",orgId).put("p_limit",200),access))
    return (0 until rows.length()).mapNotNull { i ->
        val r=rows.optJSONObject(i) ?: return@mapNotNull null
        CustomerSupplyRow(r.optString("id"),r.optString("customer_name"),r.optString("item_name"),
            r.optString("specifications"),r.optString("quantity"),r.optString("unit_name"),
            r.optLong("requested_price_minor"),r.optString("currency_code"),r.optString("payment_mode"),
            r.optString("customer_note"),r.optString("status"),r.optString("merchant_note"),r.optString("direct_sale_status"),r.optString("sale_id"))
    }
}

private fun reviewCustomerSupplyRequest(context:Context,id:String,decision:String,note:String){
    val (orgId,access)=commercialMasterDataAccess(context)
    SupabaseClient.request("/rest/v1/rpc/commercial_customer_portal_review_supply_request","POST",
        JSONObject().put("p_organization_id",orgId).put("p_request_id",id).put("p_decision",decision).put("p_note",note),access)
}

private fun sellCustomerSupplyRequestDirect(context:Context,id:String,salePrice:Long?,discount:Long,tax:Long):JSONObject{
    val (orgId,access)=commercialMasterDataAccess(context)
    return JSONObject(SupabaseClient.request("/rest/v1/rpc/commercial_customer_supply_request_sell_direct","POST",
        JSONObject().put("p_organization_id",orgId).put("p_request_id",id)
            .put("p_sale_unit_price_minor",salePrice ?: JSONObject.NULL)
            .put("p_discount_minor",discount).put("p_tax_minor",tax),access))
}

private fun convertCustomerSupplyRequest(context:Context,id:String,supplierId:String,locationId:String,price:Long){
    val (orgId,access)=commercialMasterDataAccess(context)
    SupabaseClient.request("/rest/v1/rpc/commercial_customer_portal_convert_supply_request_to_purchase","POST",
        JSONObject().put("p_organization_id",orgId).put("p_request_id",id).put("p_supplier_id",supplierId)
            .put("p_receive_location_id",locationId).put("p_order_number","CSR-"+UUID.randomUUID().toString().take(8).uppercase())
            .put("p_unit_cost_minor",price).put("p_notes","أُنشئ من طلب توفير عميل."),access)
}

@Composable
internal fun CustomerSupplyRequestsScreen(context:Context,onBack:()->Unit){
    var rows by remember { mutableStateOf<List<CustomerSupplyRow>>(emptyList()) }
    var options by remember { mutableStateOf<CommercialPurchaseOrderOptions?>(null) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var decisionTarget by remember { mutableStateOf<CustomerSupplyRow?>(null) }
    var decision by remember { mutableStateOf("request_changes") }
    var decisionNote by remember { mutableStateOf("") }
    var convertTarget by remember { mutableStateOf<CustomerSupplyRow?>(null) }
    var supplierId by remember { mutableStateOf("") }
    var locationId by remember { mutableStateOf("") }
    var supplierMenu by remember { mutableStateOf(false) }
    var locationMenu by remember { mutableStateOf(false) }
    var directSaleTarget by remember { mutableStateOf<CustomerSupplyRow?>(null) }
    var directSalePrice by remember { mutableStateOf("") }
    var directSaleDiscount by remember { mutableStateOf("0") }
    var directSaleTax by remember { mutableStateOf("0") }

    LaunchedEffect(refresh){
        loading=true;error=""
        supplyIo.execute {
            val result=runCatching { loadCustomerSupplyRows(context) to loadCommercialCloudPurchaseOrderOptions(context) }
            supplyMain.post {
                loading=false
                result.onSuccess { (r,o)->rows=r;options=o
                    if(supplierId.isBlank())supplierId=o.suppliers.firstOrNull()?.id.orEmpty()
                    if(locationId.isBlank())locationId=o.locations.firstOrNull()?.id.orEmpty()
                }.onFailure { error=it.message ?: "تعذر تحميل طلبات التوفير." }
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item {
            Row(verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,contentDescription="رجوع")}
                Column(Modifier.weight(1f)){Text("طلبات توفير الأصناف",fontSize=20.sp,fontWeight=FontWeight.Bold,color=SupplyInk);Text("طلبات العملاء لأصناف غير موجودة في الكتالوج",fontSize=12.sp,color=SupplyMuted)}
                IconButton(onClick={refresh++},enabled=!loading){Icon(Icons.Default.Refresh,contentDescription="تحديث")}
            }
        }
        if(loading)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
        if(error.isNotBlank())item{Text(error,color=MaterialTheme.colorScheme.error)}
        if(notice.isNotBlank())item{Text(notice,color=SupplyInk)}
        if(rows.isEmpty()&&!loading&&error.isBlank())item{Text("لا توجد طلبات توفير حاليًا.",color=SupplyMuted)}
        items(rows,key={it.id}){r->
            Card(colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,SupplyBorder),shape=RoundedCornerShape(16.dp)){
                Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Text(r.name,fontWeight=FontWeight.Bold,color=SupplyInk,modifier=Modifier.weight(1f))
                        Text(when(r.status){"pending"->"قيد المراجعة";"accepted"->"مقبول";"change_requested"->"مطلوب تعديل";"rejected"->"مرفوض";"deferred"->"مؤجل";"ignored"->"متجاهل";else->r.status},fontSize=11.sp,color=SupplyMuted)
                    }
                    Text("العميل: ${r.customer}",fontSize=12.sp,color=SupplyMuted)
                    Text("الكمية: ${r.quantity} ${r.unit} • السعر المتوقع: ${formatCommercialOperationalAmount(r.price,r.currency)} • ${if(r.payment=="credit")"آجل" else "نقد"}",fontSize=12.sp,color=SupplyInk)
                    if(r.specs.isNotBlank())Text("المواصفات: ${r.specs}",fontSize=12.sp)
                    if(r.note.isNotBlank())Text("ملاحظة العميل: ${r.note}",fontSize=12.sp)
                    if(r.merchantNote.isNotBlank())Text("آخر رد: ${r.merchantNote}",fontSize=12.sp,color=SupplyMuted)
                    if(r.status in setOf("pending","change_requested","deferred")){
                        Row(horizontalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.fillMaxWidth()){
                            Button(onClick={decisionTarget=r;decision="accept";decisionNote=""},enabled=!busy,modifier=Modifier.weight(1f)){Text("قبول")}
                            OutlinedButton(onClick={decisionTarget=r;decision="request_changes";decisionNote=""},enabled=!busy,modifier=Modifier.weight(1f)){Text("طلب تعديل")}
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.fillMaxWidth()){
                            TextButton(onClick={decisionTarget=r;decision="reject";decisionNote=""},enabled=!busy){Text("رفض")}
                            TextButton(onClick={decisionTarget=r;decision="defer";decisionNote=""},enabled=!busy){Text("تأجيل")}
                            TextButton(onClick={decisionTarget=r;decision="ignore";decisionNote=""},enabled=!busy){Text("تجاهل")}
                        }
                    }
                    if(r.status=="accepted")Button(onClick={convertTarget=r},enabled=!busy&&options?.suppliers?.isNotEmpty()==true&&options?.locations?.isNotEmpty()==true,modifier=Modifier.fillMaxWidth()){Text("تحويل إلى أمر شراء")}
                    if(r.status=="converted" && r.directSaleStatus!="sold") {
                        Button(onClick={directSaleTarget=r;directSalePrice="";directSaleDiscount="0";directSaleTax="0"},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("شراء وبيع مباشرة على حساب العميل")}
                        Text("يتاح بعد استلام كامل الكمية وتسجيل فاتورة المورد وربط الصنف بوحدة المخزون الأساسية.",fontSize=11.sp,color=SupplyMuted)
                    }
                    if(r.directSaleStatus=="sold" && r.saleId.isNotBlank()) Text("تم البيع وتسجيله على حساب العميل. رقم البيع: "+r.saleId,fontSize=12.sp,color=SupplyInk)
                }
            }
        }
    }
    decisionTarget?.let{target->
        AlertDialog(onDismissRequest={if(!busy)decisionTarget=null},title={Text(when(decision){"accept"->"قبول الطلب";"reject"->"رفض الطلب";"request_changes"->"طلب تعديل";"defer"->"تأجيل الطلب";else->"تجاهل الطلب"})},
            text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text(if(decision in setOf("reject","request_changes"))"اكتب السبب ليصل إلى العميل." else "يمكنك إضافة ملاحظة اختيارية.");OutlinedTextField(decisionNote,{decisionNote=it.take(1000)},label={Text("السبب أو الملاحظة")},modifier=Modifier.fillMaxWidth(),minLines=2)}},
            confirmButton={Button(enabled=!busy&&(decision !in setOf("reject","request_changes")||decisionNote.isNotBlank()),onClick={
                busy=true;supplyIo.execute{val result=runCatching{reviewCustomerSupplyRequest(context,target.id,decision,decisionNote)}
                    supplyMain.post{busy=false;result.onSuccess{notice="تم تحديث حالة طلب ${target.name}.";refresh++}.onFailure{error=it.message?:"تعذر تنفيذ القرار."};decisionTarget=null}}
            }){Text("تأكيد")}},dismissButton={TextButton(onClick={decisionTarget=null},enabled=!busy){Text("إلغاء")}})
    }

    directSaleTarget?.let{target->
        AlertDialog(onDismissRequest={if(!busy)directSaleTarget=null},title={Text("اعتماد الشراء والبيع المباشر")},
            text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("لن ينفذ هذا الإجراء إلا بعد تسجيل فاتورة المورد واستلام الكمية كاملة. سيُنشأ البيع على حساب العميل وتُسجل حركة المخزون والحسابات في معاملة واحدة.",fontSize=12.sp,color=SupplyMuted)
                OutlinedTextField(directSalePrice,{directSalePrice=it.filter(Char::isDigit).take(15)},label={Text("سعر بيع الوحدة (اختياري)")},modifier=Modifier.fillMaxWidth())
                Text("اترك السعر فارغًا ليستخدم النظام سعر الوحدة الفعلي في فاتورة المورد. أدخل سعرًا أعلى إذا أردت إضافة ربح.",fontSize=11.sp,color=SupplyMuted)
                OutlinedTextField(directSaleDiscount,{directSaleDiscount=it.filter(Char::isDigit).take(15)},label={Text("الخصم الإجمالي")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(directSaleTax,{directSaleTax=it.filter(Char::isDigit).take(15)},label={Text("الضريبة الإجمالية")},modifier=Modifier.fillMaxWidth())
            }},
            confirmButton={Button(enabled=!busy&&(directSalePrice.isBlank()||directSalePrice.toLongOrNull()!=null)&&directSaleDiscount.toLongOrNull()!=null&&directSaleTax.toLongOrNull()!=null,onClick={
                busy=true;supplyIo.execute{
                    val result=runCatching{sellCustomerSupplyRequestDirect(context,target.id,directSalePrice.toLongOrNull(),directSaleDiscount.toLong(),directSaleTax.toLong())}
                    supplyMain.post{busy=false;result.onSuccess{notice="تم تسجيل البيع مباشرة على حساب العميل وإتمام الأثر المالي والمخزني.";refresh++}.onFailure{error=it.message?:"تعذر تنفيذ البيع المباشر. تحقق من الاستلام والفاتورة ووحدة الصنف."};directSaleTarget=null}
                }
            }){Text("اعتماد البيع")}},dismissButton={TextButton(onClick={directSaleTarget=null},enabled=!busy){Text("إلغاء")}})
    }
    convertTarget?.let{target->
        val opts=options
        AlertDialog(onDismissRequest={if(!busy)convertTarget=null},title={Text("تحويل الطلب إلى أمر شراء")},
            text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("سينشأ أمر شراء مسودة للمورد المحدد. لن يتغير المخزون أو الحسابات حتى تسجيل الاستلام والفاتورة الفعلية.",fontSize=12.sp,color=SupplyMuted)
                Box{OutlinedButton(onClick={supplierMenu=true},modifier=Modifier.fillMaxWidth()){Text(opts?.suppliers?.firstOrNull{it.id==supplierId}?.name?:"اختر المورد")}
                    DropdownMenu(supplierMenu,{supplierMenu=false}){opts?.suppliers?.forEach{s->DropdownMenuItem(text={Text(s.name)},onClick={supplierId=s.id;supplierMenu=false})}}}
                Box{OutlinedButton(onClick={locationMenu=true},modifier=Modifier.fillMaxWidth()){Text(opts?.locations?.firstOrNull{it.id==locationId}?.name?:"اختر موقع الاستلام")}
                    DropdownMenu(locationMenu,{locationMenu=false}){opts?.locations?.forEach{l->DropdownMenuItem(text={Text(l.name)},onClick={locationId=l.id;locationMenu=false})}}}
                Text("سيستخدم السعر المتوقع ${formatCommercialOperationalAmount(target.price,target.currency)} كتكلفة أولية قابلة للتعديل في أمر الشراء.",fontSize=12.sp)
            }},
            confirmButton={Button(enabled=!busy&&supplierId.isNotBlank()&&locationId.isNotBlank(),onClick={busy=true;supplyIo.execute{
                val result=runCatching{convertCustomerSupplyRequest(context,target.id,supplierId,locationId,target.price)}
                supplyMain.post{busy=false;result.onSuccess{notice="تم إنشاء أمر الشراء المسودة. أكمل ربط الصنف وتسجيل الفاتورة والاستلام من شاشة المشتريات.";refresh++}.onFailure{error=it.message?:"تعذر التحويل."};convertTarget=null}
            }}){Text("إنشاء أمر الشراء")}},dismissButton={TextButton(onClick={convertTarget=null},enabled=!busy){Text("إلغاء")}})
    }
}
