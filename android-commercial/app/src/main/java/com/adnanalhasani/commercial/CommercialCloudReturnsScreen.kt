package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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

private val ReturnInk = Color(0xFF183B35)
private val ReturnGreen = Color(0xFF167D63)
private val ReturnMuted = Color(0xFF71817B)
private val ReturnIo = Executors.newSingleThreadExecutor()
private val ReturnMain = Handler(Looper.getMainLooper())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommercialCloudReturnsScreen(context: Context, onBack: () -> Unit) {
    var rows by remember { mutableStateOf<List<CommercialCloudSaleReturn>>(emptyList()) }
    var selectedId by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var decisionTarget by remember { mutableStateOf<CommercialCloudSaleReturn?>(null) }
    var approve by remember { mutableStateOf(true) }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var inspectionTarget by remember { mutableStateOf<CommercialCloudReturnItem?>(null) }
    var condition by remember { mutableStateOf("resellable") }
    var restock by remember { mutableStateOf(true) }
    var inspectionNote by remember { mutableStateOf("") }
    var refundTarget by remember { mutableStateOf<CommercialCloudSaleReturn?>(null) }
    var refundMethod by remember { mutableStateOf("cash") }
    var refundEvidence by remember { mutableStateOf("") }
    var returnableSales by remember { mutableStateOf<List<CommercialCloudReturnableSale>>(emptyList()) }
    var salePickerOpen by remember { mutableStateOf(false) }
    var requestTarget by remember { mutableStateOf<CommercialCloudReturnableSale?>(null) }
    var requestReason by remember { mutableStateOf("customer_request") }
    var requestNote by remember { mutableStateOf("") }
    val requestedQuantities = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(refresh) {
        busy = true; error = ""
        ReturnIo.execute {
            val result = runCatching { loadCommercialCloudSaleReturns(context) }
            ReturnMain.post {
                result.onSuccess { rows = it; if (rows.none { r -> r.id == selectedId }) selectedId = "" }
                    .onFailure { error = it.message ?: "تعذر تحميل مرتجعات المبيعات." }
                busy = false
            }
        }
    }
    val filtered = rows.filter { r -> query.isBlank() || listOf(r.receiptNumber,r.customerName,r.reasonCode,r.status,r.refundStatus,r.restockStatus,r.id).any { it.contains(query.trim(), true) } }
    val selected = rows.firstOrNull { it.id == selectedId }
    fun executeAction(action: () -> Unit, success: String) {
        busy = true
        error = ""
        ReturnIo.execute {
            val result = runCatching { action() }
            ReturnMain.post {
                busy = false
                result.onSuccess { message = success; refresh++ }
                    .onFailure { error = it.message ?: "تعذر تنفيذ العملية."; message = "" }
            }
        }
    }

    Scaffold(containerColor = Color(0xFFF4F7F5), topBar = {
        TopAppBar(title = { Column {
            Text("مرتجعات المبيعات", color = ReturnInk, fontWeight = FontWeight.Bold)
            Text("الطلب • الموافقة • فحص الأصناف • إعادة المخزون", color = ReturnMuted, fontSize = 11.sp)
        } }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } },
            actions = { IconButton(onClick = { refresh++ }, enabled = !busy) { Icon(Icons.Default.Refresh, "تحديث") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF4F7F5)))
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = ReturnInk), shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("طلبات المرتجعات من المؤسسة الحالية", color = Color(0xFFBFD9D0), fontSize = 12.sp)
                            Text(if (busy) "…" else rows.size.toString(), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                            Text("نتائج البحث: ${filtered.size}", color = Color(0xFF8DE0B8), fontSize = 12.sp)
                        }
                    }
                }
            }
            item { OutlinedTextField(value=query,onValueChange={query=it},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("ابحث بالفاتورة أو العميل أو الحالة")},leadingIcon={Icon(Icons.Default.Search,null)},shape=RoundedCornerShape(14.dp)) }
            item {
                Button(
                    onClick = {
                        busy = true; error = ""; message = ""
                        ReturnIo.execute {
                            val result = runCatching { loadCommercialCloudReturnableSales(context) }
                            ReturnMain.post {
                                busy = false
                                result.onSuccess { returnableSales = it; salePickerOpen = true }
                                    .onFailure { error = it.message ?: "تعذر تحميل فواتير البيع المكتملة." }
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ReturnGreen),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("إنشاء طلب مرتجع من فاتورة مكتملة") }
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color=ReturnGreen) }
            if (error.isNotBlank()) item { Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFFFEFEC)),shape=RoundedCornerShape(14.dp)) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) { Text("تعذر تحميل المرتجعات",fontWeight=FontWeight.Bold,color=Color(0xFF9C3028)); Text(error,color=Color(0xFF7B3933),fontSize=12.sp); OutlinedButton(onClick={refresh++}) { Text("إعادة المحاولة") } } } }
            if (message.isNotBlank()) item { Text(message,color=ReturnGreen,fontSize=12.sp) }
            if (!busy && error.isBlank() && filtered.isEmpty()) item { Card(colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,Color(0xFFE1E9E5)),shape=RoundedCornerShape(16.dp)) { Column(Modifier.fillMaxWidth().padding(22.dp),horizontalAlignment=Alignment.CenterHorizontally) { Text("لا توجد طلبات مرتجعات",fontWeight=FontWeight.Bold,color=ReturnInk); Text("لا يتم إنشاء بيانات تجريبية تلقائيًا.",color=ReturnMuted,fontSize=12.sp) } } }
            items(filtered,key={it.id}) { r ->
                Card(onClick={selectedId=r.id;message=""},colors=CardDefaults.cardColors(containerColor=if(selectedId==r.id) Color(0xFFE8F5EF) else Color.White),border=BorderStroke(if(selectedId==r.id) 2.dp else 1.dp,if(selectedId==r.id) ReturnGreen else Color(0xFFE1E9E5)),shape=RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(15.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("مرتجع فاتورة #${r.receiptNumber.ifBlank{"—"}}",fontWeight=FontWeight.Bold,color=ReturnInk); Text(r.customerName,color=ReturnMuted,fontSize=12.sp) }; Surface(color=if(r.status in setOf("approved","completed")) Color(0xFFE8F5EF) else Color(0xFFFFF3DE),shape=RoundedCornerShape(8.dp)) { Text(label(r.status),Modifier.padding(horizontal=9.dp,vertical=5.dp),color=ReturnInk,fontSize=11.sp) } }
                        Text("السبب: ${reason(r.reasonCode)} · البنود: ${r.items.size}",color=ReturnMuted,fontSize=12.sp)
                        Text("المطلوب: ${money(r.requestedRefundMinor,r.currencyCode)} · الاسترداد: ${label(r.refundStatus)} · المخزون: ${label(r.restockStatus)}",color=ReturnGreen,fontWeight=FontWeight.SemiBold,fontSize=12.sp)
                    }
                }
            }
            if (selected != null) item {
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,Color(0xFFDCE7E1)),shape=RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("تفاصيل الطلب",fontSize=18.sp,fontWeight=FontWeight.Bold,color=ReturnInk)
                        Text("الفاتورة #${selected.receiptNumber} · ${selected.customerName}",color=ReturnMuted,fontSize=12.sp)
                        Text("السبب: ${reason(selected.reasonCode)} ${selected.reasonNote}",color=ReturnInk,fontSize=12.sp)
                        Divider()
                        selected.items.forEach { item ->
                            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Text(item.productName,fontWeight=FontWeight.SemiBold,color=ReturnInk)
                                Text("الكمية ${item.quantity} · المبلغ ${money(item.refundAmountMinor,selected.currencyCode)} · أعيد للمخزون ${item.restockedQuantity}",color=ReturnMuted,fontSize=11.sp)
                                Text("حالة الفحص: ${label(item.conditionCode)} · إعادة المخزون: ${if(item.restock) "نعم" else "لا"}",color=ReturnMuted,fontSize=11.sp)
                                if(selected.status=="approved" && item.restockedQuantity==0) OutlinedButton(enabled=!busy,onClick={inspectionTarget=item;condition=item.conditionCode.ifBlank{"resellable"};restock=item.restock;inspectionNote=item.note}) { Text("فحص الصنف") }
                            }
                        }
                        if(selected.status=="requested") {
                            Button(enabled=!busy,modifier=Modifier.fillMaxWidth(),onClick={decisionTarget=selected;approve=true;amount=selected.requestedRefundMinor.toString();note=""},colors=ButtonDefaults.buttonColors(containerColor=ReturnGreen)) { Text("مراجعة والموافقة") }
                            OutlinedButton(enabled=!busy,modifier=Modifier.fillMaxWidth(),onClick={decisionTarget=selected;approve=false;amount="0";note=""}) { Text("رفض الطلب") }
                        }
                        if(selected.status=="approved" && selected.restockStatus!="restocked" && selected.restockStatus!="not_required") {
                            Text("يجب إكمال فحص الأصناف أولًا. سيضيف الإجراء الكميات المؤهلة فقط إلى موقع المخزون المرتبط بالمرتجع.",color=Color(0xFF925500),fontSize=12.sp)
                            Button(enabled=!busy,onClick={
                                val target=selected
                                executeAction({restockCommercialCloudSaleReturn(context,target.id)},"تم تنفيذ إعادة المخزون أو تأكيد تنفيذها دون تكرار.")
                            },modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=ReturnGreen)) { Text("إعادة الكميات المؤهلة للمخزون") }
                        }
                        if(selected.status=="completed" && selected.refundStatus in setOf("pending","failed") &&
                            (selected.approvedRefundMinor ?: 0L)>0L &&
                            selected.restockStatus in setOf("restocked","not_required")) {
                            Text("قبل التسجيل: نفّذ رد المبلغ للعميل فعليًا بالطريقة المختارة، ثم أدخل مرجع الإثبات. هذا الإجراء يوثّق الدفع ويُنشئ قيد التسوية؛ لا يحوّل الأموال تلقائيًا.",color=Color(0xFF925500),fontSize=12.sp)
                            Button(enabled=!busy,onClick={refundTarget=selected;refundMethod="cash";refundEvidence=""},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=ReturnGreen)) { Text("تسجيل استرداد مالي منفّذ") }
                        } else if(selected.refundStatus=="pending") {
                            Text("رد المبلغ معلق. يلزم اكتمال القرار وفحص/إعادة المخزون قبل تسجيل الاسترداد.",color=Color(0xFF925500),fontSize=12.sp)
                        }
                    }
                }
            }
            item { Text("تُنفذ القرارات والفحص وإعادة المخزون عبر RPCs المعتمدة في Supabase. لا يتم تسجيل رد مالي على أنه مكتمل دون مسار دفع فعلي.",color=ReturnMuted,fontSize=11.sp) }
        }
    }


    if (salePickerOpen) AlertDialog(
        onDismissRequest = { if (!busy) salePickerOpen = false },
        title = { Text("اختر فاتورة مكتملة") },
        text = {
            if (returnableSales.isEmpty()) Text("لا توجد فواتير مكتملة تحتوي على أصناف قابلة للاختيار.")
            else LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(returnableSales, key = { it.id }) { sale ->
                    OutlinedButton(
                        onClick = {
                            requestTarget = sale
                            requestedQuantities.clear()
                            sale.items.forEach { requestedQuantities[it.id] = "0" }
                            requestReason = "customer_request"
                            requestNote = ""
                            salePickerOpen = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text("فاتورة #${sale.receiptNumber.ifBlank { "—" }}", fontWeight = FontWeight.Bold)
                            Text("${sale.customerName} · ${sale.items.size} صنف", fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { salePickerOpen = false }) { Text("إغلاق") } }
    )

    if (requestTarget != null) AlertDialog(
        onDismissRequest = { if (!busy) requestTarget = null },
        title = { Text("طلب مرتجع جديد") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("الفاتورة #${requestTarget!!.receiptNumber} · ${requestTarget!!.customerName}", fontWeight = FontWeight.SemiBold)
                Text("اختر سبب المرتجع وحدد كمية كل صنف. التحقق النهائي من الكميات المرتجعة يتم في قاعدة البيانات.", color = ReturnMuted, fontSize = 11.sp)
                listOf(
                    "customer_request" to "طلب العميل",
                    "damaged" to "تلف",
                    "wrong_item" to "صنف غير صحيح",
                    "not_as_described" to "غير مطابق للوصف",
                    "duplicate_charge" to "خصم مكرر",
                    "other" to "أخرى"
                ).forEach { (value, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = requestReason == value, onClick = { requestReason = value })
                        Text(label)
                    }
                }
                OutlinedTextField(
                    value = requestNote,
                    onValueChange = { requestNote = it.take(2000) },
                    label = { Text("تفاصيل إضافية (اختياري)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Divider()
                requestTarget!!.items.forEach { saleItem ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(saleItem.productName, color = ReturnInk, fontWeight = FontWeight.SemiBold)
                        Text("المباع: ${saleItem.soldQuantity} · المتبقي للإرجاع: ${saleItem.remainingQuantity} · قيمة السطر: ${money(saleItem.lineTotalMinor, requestTarget!!.currencyCode)}", color = ReturnMuted, fontSize = 11.sp)
                        OutlinedTextField(
                            value = requestedQuantities[saleItem.id] ?: "0",
                            onValueChange = { value ->
                                requestedQuantities[saleItem.id] = value.filter(Char::isDigit).take(9)
                            },
                            label = { Text("الكمية المراد إرجاعها (0 إلى ${saleItem.remainingQuantity})") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = !busy && requestTarget != null &&
                requestTarget!!.items.all { (requestedQuantities[it.id]?.toIntOrNull() ?: 0) <= it.remainingQuantity } &&
                requestTarget!!.items.any { (requestedQuantities[it.id]?.toIntOrNull() ?: 0) > 0 },
                onClick = {
                    val target = requestTarget!!
                    val quantities = requestedQuantities.mapNotNull { (id, raw) ->
                        val qty = raw.toIntOrNull() ?: 0
                        if (qty > 0) id to qty else null
                    }.toMap()
                    executeAction({
                        requestCommercialCloudSaleReturn(context, target.id, requestReason, requestNote, quantities)
                    }, "تم إنشاء طلب المرتجع في قاعدة البيانات، وهو بانتظار المراجعة.")
                    requestTarget = null
                },
                colors = ButtonDefaults.buttonColors(containerColor = ReturnGreen)
            ) { Text("إرسال الطلب") }
        },
        dismissButton = { TextButton(onClick = { if (!busy) requestTarget = null }) { Text("إلغاء") } }
    )

    if(decisionTarget!=null) AlertDialog(onDismissRequest={if(!busy)decisionTarget=null},title={Text(if(approve)"اعتماد المرتجع" else "رفض المرتجع")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(approve) OutlinedTextField(value=amount,onValueChange={amount=it.filter(Char::isDigit)},label={Text("المبلغ المعتمد بوحدة العملة الصغرى")},singleLine=true)
            OutlinedTextField(value=note,onValueChange={note=it.take(2000)},label={Text("ملاحظة القرار")},minLines=2)
        }
    },confirmButton={Button(enabled=!busy&&(!approve||(amount.toLongOrNull()?.let{it>=0&&it<=decisionTarget!!.requestedRefundMinor}==true)),onClick={
        val target=decisionTarget!!
        executeAction({decideCommercialCloudSaleReturn(context,target.id,if(approve)"approve" else "reject",amount.toLongOrNull()?:0L,note)} ,if(approve)"تم اعتماد الطلب؛ حالة رد المبلغ منفصلة." else "تم رفض الطلب.")
        decisionTarget=null
    }){Text("تأكيد")}},dismissButton={TextButton(onClick={decisionTarget=null}){Text("إلغاء")}})

    if(inspectionTarget!=null) AlertDialog(onDismissRequest={if(!busy)inspectionTarget=null},title={Text("فحص الصنف المرتجع")},text={
        Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(inspectionTarget!!.productName,fontWeight=FontWeight.SemiBold)
            listOf("resellable" to "صالح للبيع","damaged" to "تالف","written_off" to "مستبعد","uninspected" to "غير مفحوص").forEach{(v,l)->Row(verticalAlignment=Alignment.CenterVertically){RadioButton(selected=condition==v,onClick={condition=v;if(v!="resellable")restock=false});Text(l)}}
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(checked=restock,onCheckedChange={restock=it},enabled=condition=="resellable");Text("إعادة للمخزون")}
            OutlinedTextField(value=inspectionNote,onValueChange={inspectionNote=it.take(1000)},label={Text("ملاحظة الفحص")},minLines=2)
        }
    },confirmButton={Button(enabled=!busy,onClick={
        val target=inspectionTarget!!
        executeAction({inspectCommercialCloudSaleReturnItem(context,target.id,condition,restock,inspectionNote)},"تم حفظ الفحص.")
        inspectionTarget=null
    }){Text("حفظ الفحص")}},dismissButton={TextButton(onClick={inspectionTarget=null}){Text("إلغاء")}})

    if(refundTarget!=null) AlertDialog(
        onDismissRequest={if(!busy)refundTarget=null},
        title={Text("تأكيد تسجيل الاسترداد المالي")},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("المبلغ المعتمد: ${money(refundTarget!!.approvedRefundMinor ?: 0L,refundTarget!!.currencyCode)}",fontWeight=FontWeight.Bold,color=ReturnInk)
                Text("لا تتابع إلا إذا سلّمت المبلغ فعليًا للعميل أو أكدت الدفع لدى مزود الخدمة. هذا التسجيل لا ينفذ تحويلًا مصرفيًا.",color=Color(0xFF925500),fontSize=12.sp)
                listOf("cash" to "نقدًا","bank_transfer" to "تحويل بنكي","provider" to "مزود دفع").forEach{(v,l)->
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        RadioButton(selected=refundMethod==v,onClick={refundMethod=v})
                        Text(l)
                    }
                }
                OutlinedTextField(value=refundEvidence,onValueChange={refundEvidence=it.take(200)},label={Text("مرجع إثبات الدفع (إلزامي)")},singleLine=true,modifier=Modifier.fillMaxWidth())
            }
        },
        confirmButton={Button(enabled=!busy && refundEvidence.trim().length>=3,onClick={
            val target=refundTarget!!
            val method=refundMethod
            val evidence=refundEvidence.trim()
            executeAction({
                executeCommercialCloudSaleReturnRefund(context,target.id,method,evidence)
            },"تم توثيق الاسترداد المالي وترحيل القيد المحاسبي.")
            refundTarget=null
        },colors=ButtonDefaults.buttonColors(containerColor=ReturnGreen)){Text("تأكيد: تم الدفع فعليًا")}},
        dismissButton={TextButton(onClick={if(!busy)refundTarget=null}){Text("إلغاء")}}
    )
}

private fun reason(v:String)=when(v){"customer_request"->"طلب العميل";"damaged"->"تلف";"wrong_item"->"صنف غير صحيح";"not_as_described"->"غير مطابق للوصف";"duplicate_charge"->"خصم مكرر";"other"->"أخرى";else->v.ifBlank{"غير محدد"}}
private fun label(v:String)=when(v.lowercase(Locale.ROOT)){"requested"->"بانتظار القرار";"approved"->"موافق عليه";"rejected"->"مرفوض";"pending"->"معلق";"failed"->"فشل";"refunded"->"تم الاسترداد";"completed"->"مكتمل";"not_required"->"غير مطلوب";"resellable"->"صالح للبيع";"damaged"->"تالف";"written_off"->"مستبعد";"uninspected"->"غير مفحوص";"partial"->"جزئي";else->v.ifBlank{"غير محدد"}}
private fun money(v:Long,c:String)="${String.format(Locale.US,"%,d",v)} ${c.ifBlank{"—"}}"
