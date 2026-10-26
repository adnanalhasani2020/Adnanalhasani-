package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

private val CustomerOrdersInk = Color(0xFF183B35)
private val CustomerOrdersGreen = Color(0xFF167D63)
private val CustomerOrdersMuted = Color(0xFF71817B)
private val CustomerOrdersBorder = Color(0xFFE5EBE8)
private val customerOrdersExecutor = Executors.newSingleThreadExecutor()
private val customerOrdersMain = Handler(Looper.getMainLooper())
private fun customerOrderMoney(value: Long, currency: String) = String.format(Locale.US, "%,d %s", value, currency)
private fun customerOrderStatus(status: String) = when(status) {
 "pending"->"بانتظار المراجعة"; "confirmed"->"مقبول"; "in_progress"->"قيد التنفيذ"; "fulfilled"->"تم البيع"; "cancelled"->"ملغى"; "rejected"->"مرفوض"; else->status
}
private fun merchantBookingStatus(status:String)=when(status){"requested"->"بانتظار القرار";"confirmed"->"مؤكد";"checked_in"->"تم تسجيل الوصول";"completed"->"مكتمل";"cancelled"->"ملغى";"rejected"->"مرفوض";"no_show"->"لم يحضر";else->status}
private fun merchantDate(value:String)=value.replace("T"," ").take(16)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomerOrderFulfillmentScreen(context: Context,onBack:()->Unit) {
 var orders by remember { mutableStateOf<List<CustomerOrderFulfillmentOrder>>(emptyList()) }
 var bookings by remember { mutableStateOf<List<MerchantBooking>>(emptyList()) }
 var locations by remember { mutableStateOf<List<CustomerOrderFulfillmentLocation>>(emptyList()) }
 var editableOfferings by remember { mutableStateOf<List<MerchantEditableOffering>>(emptyList()) }
 var editTarget by remember { mutableStateOf<CustomerOrderFulfillmentOrder?>(null) }
 var locationId by remember { mutableStateOf("") }
 var locationMenu by remember { mutableStateOf(false) }
 var tab by remember { mutableStateOf("orders") }
 var hidden by remember { mutableStateOf(false) }
 var query by remember { mutableStateOf("") }
 var filter by remember { mutableStateOf("all") }
 var filterMenu by remember { mutableStateOf(false) }
 var loading by remember { mutableStateOf(false) }
 var busy by remember { mutableStateOf<String?>(null) }
 var error by remember { mutableStateOf("") }
 var notice by remember { mutableStateOf("") }
 var refresh by remember { mutableIntStateOf(0) }
 var cashTarget by remember { mutableStateOf<CustomerOrderFulfillmentOrder?>(null) }
 var decisionTarget by remember { mutableStateOf<MerchantDecision?>(null) }
 var expanded by remember { mutableStateOf<String?>(null) }

 fun action(key:String,run:()->JSONObject,success:(JSONObject)->String={ "تم تأكيد العملية من الخادم." }) {
  if(busy!=null)return
  busy=key;error="";notice="جارٍ تنفيذ العملية..."
  customerOrdersExecutor.execute {
   val result=runCatching(run)
   customerOrdersMain.post {
    busy=null
    result.onSuccess { notice=success(it);refresh++ }.onFailure { notice="";error=it.message ?: "تعذر تنفيذ العملية. حدّث البيانات وأعد المحاولة." }
   }
  }
 }
 fun fulfill(order:CustomerOrderFulfillmentOrder) {
  action("fulfill:"+order.id,{fulfillCustomerPortalOrder(context,order.id,locationId,"customer-order-fulfill:"+order.id)}) {
   "اكتمل البيع "+it.optString("receipt_number")+" بمبلغ "+customerOrderMoney(it.optLong("total_minor"),it.optString("currency_code"))+". حالة الدفع: "+it.optString("payment_status")
  }
 }
 LaunchedEffect(refresh,hidden) {
  loading=true;error=""
  customerOrdersExecutor.execute {
   val a=runCatching{loadCustomerOrderFulfillmentQueue(context,hidden)}
   val b=runCatching{loadMerchantBookings(context,hidden)}
   val c=runCatching{loadCustomerOrderFulfillmentLocations(context)}
   val d=runCatching{loadMerchantEditableOfferings(context)}
   customerOrdersMain.post {
    loading=false
    a.onSuccess{orders=it}.onFailure{error=it.message ?: "تعذر تحميل الطلبات."}
    b.onSuccess{bookings=it}.onFailure{if(error.isBlank())error=it.message ?: "تعذر تحميل الحجوزات."}
    c.onSuccess{locations=it;if(it.none{x->x.id==locationId})locationId=it.firstOrNull()?.id.orEmpty()}.onFailure{if(error.isBlank())error=it.message ?: "تعذر تحميل مواقع المخزون."}
    d.onSuccess{editableOfferings=it}.onFailure{if(error.isBlank())error=it.message ?: "تعذر تحميل قائمة الأصناف."}
   }
  }
 }
 val q=query.trim()
 val filteredOrders=orders.filter{(filter=="all"||it.status==filter)&&(q.isBlank()||listOf(it.orderNumber,it.customerName,it.id,it.customerNote).any{v->v.contains(q,true)})}
 val filteredBookings=bookings.filter{(filter=="all"||it.status==filter)&&(q.isBlank()||listOf(it.bookingNumber,it.orderNumber,it.customerName,it.resourceName,it.id).any{v->v.contains(q,true)})}
 Scaffold(topBar={TopAppBar(title={Column{Text("إدارة طلبات العملاء والحجوزات",color=CustomerOrdersInk,fontWeight=FontWeight.Bold,fontSize=17.sp);Text("مراجعة آمنة وبيع من الخادم",color=CustomerOrdersMuted,fontSize=11.sp)}},navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"رجوع",tint=CustomerOrdersInk)}},actions={IconButton(onClick={if(!loading&&busy==null)refresh++},enabled=!loading&&busy==null){Icon(Icons.Default.Refresh,"تحديث",tint=CustomerOrdersInk)}})}) { padding ->
  LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
   item {
    Card(colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,CustomerOrdersBorder)) {
     Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
      Text("قبول الطلب لا ينشئ فاتورة ولا يخصم المخزون. إتمام البيع ينفذ عبر دورة البيع الحالية.",fontSize=12.sp,color=CustomerOrdersMuted)
      OutlinedTextField(query,{query=it},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("بحث برقم الطلب أو الحجز أو العميل")},leadingIcon={Icon(Icons.Default.Search,null)})
      Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
       FilterChip(tab=="orders",{tab="orders";filter="all"} ,label={Text("الطلبات ("+orders.size+")")})
       FilterChip(tab=="bookings",{tab="bookings";filter="all"},label={Text("الحجوزات ("+bookings.size+")")})
       FilterChip(hidden,{hidden=!hidden},label={Text(if(hidden)"المخفية" else "إظهار المخفية")})
      }
      Box { OutlinedButton(onClick={filterMenu=true},modifier=Modifier.fillMaxWidth()){Text("تصفية الحالة: "+if(filter=="all")"الكل" else if(tab=="orders")customerOrderStatus(filter) else merchantBookingStatus(filter))}
       DropdownMenu(filterMenu,{filterMenu=false}) {
        (if(tab=="orders")listOf("all","pending","confirmed","in_progress","fulfilled","rejected","cancelled") else listOf("all","requested","confirmed","checked_in","completed","rejected","cancelled","no_show")).forEach{s->
         DropdownMenuItem(text={Text(if(s=="all")"الكل" else if(tab=="orders")customerOrderStatus(s) else merchantBookingStatus(s))},onClick={filter=s;filterMenu=false})
        }
       }
      }
      if(tab=="orders"&&orders.any{it.fulfillmentSupported&&it.status in setOf("pending","confirmed","in_progress")}) Box {
       OutlinedButton(onClick={locationMenu=true},enabled=busy==null&&locations.isNotEmpty(),modifier=Modifier.fillMaxWidth()){Text(locations.firstOrNull{it.id==locationId}?.name ?: "اختر موقع المخزون")}
       DropdownMenu(locationMenu,{locationMenu=false}){locations.forEach{loc->DropdownMenuItem(text={Text(loc.name)},onClick={locationId=loc.id;locationMenu=false})}}
      }
     }
    }
   }
   if(loading)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
   if(error.isNotBlank())item{Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)){Column(Modifier.padding(12.dp)){Text(error);TextButton(onClick={refresh++},enabled=busy==null){Text("إعادة المحاولة")}}}}
   if(notice.isNotBlank())item{Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFE8F5EF))){Text(notice,Modifier.padding(12.dp),color=CustomerOrdersGreen,fontSize=12.sp)}}
   if(tab=="orders") {
    if(!loading&&filteredOrders.isEmpty())item{MerchantQueueEmpty("لا توجد طلبات مطابقة للمرشحات.")}
    items(filteredOrders,key={"o:"+it.id}){order->
     val open=expanded==order.id
     Card(colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,CustomerOrdersBorder),shape=RoundedCornerShape(16.dp)){
      Column(Modifier.fillMaxWidth().padding(13.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
       Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("طلب "+order.orderNumber,fontWeight=FontWeight.Bold,color=CustomerOrdersInk);Text(order.customerName.ifBlank{"عميل المؤسسة"},fontSize=12.sp,color=CustomerOrdersMuted);Text(merchantDate(order.createdAt),fontSize=11.sp,color=CustomerOrdersMuted)};Column(horizontalAlignment=Alignment.End){Text(customerOrderStatus(order.status),color=CustomerOrdersGreen,fontWeight=FontWeight.SemiBold);Text(order.paymentStatus,fontSize=11.sp,color=CustomerOrdersMuted)}}
       Row(verticalAlignment=Alignment.CenterVertically){Text(order.items.size.toString()+" بند",Modifier.weight(1f),fontSize=12.sp,color=CustomerOrdersMuted);Text(customerOrderMoney(order.totalMinor,order.currencyCode),fontWeight=FontWeight.Bold,color=CustomerOrdersInk)}
       Row {
        TextButton(onClick={expanded=if(open)null else order.id}){Text(if(open)"إغلاق التفاصيل" else "التفاصيل")}
        TextButton(onClick={action("visibility:"+order.id,{setMerchantCustomerOrderHidden(context,order.id,!order.hidden)}){if(order.hidden)"استُعيد الطلب دون تغيير حالته." else "أُخفي الطلب دون حذف سجله."}},enabled=busy==null){Text(if(order.hidden)"إظهار مجددًا" else "إخفاء من القائمة")}
       }
       if(open){
        if(order.customerNote.isNotBlank())Text("ملاحظة العميل: "+order.customerNote,fontSize=12.sp)
        if(order.decisionReason.isNotBlank())Text("سبب القرار: "+order.decisionReason,fontSize=12.sp,color=CustomerOrdersMuted)
        if(order.status=="fulfilled" && order.saleId.isNotBlank()) {
         Text("مستند البيع: "+order.receiptNumber+" • معرّف البيع: "+order.saleId,fontSize=12.sp,color=CustomerOrdersGreen,fontWeight=FontWeight.SemiBold)
         Text("تاريخ الاعتماد: "+merchantDate(order.fulfilledAt)+" • المدفوع: "+customerOrderMoney(order.paidMinor,order.currencyCode)+" • المتبقي: "+customerOrderMoney(order.outstandingMinor,order.currencyCode),fontSize=11.sp,color=CustomerOrdersMuted)
        }
        order.items.forEach{line->Column{Text(line.name,fontWeight=FontWeight.Medium);Text("الكمية "+line.quantity+" • سعر الوحدة "+customerOrderMoney(line.unitPriceMinor,order.currencyCode)+" • الإجمالي "+customerOrderMoney(line.lineTotalMinor,order.currencyCode),fontSize=11.sp,color=CustomerOrdersMuted)}}
        if(order.status=="pending") {
         if(order.orderType=="purchase") OutlinedButton(onClick={editTarget=order},enabled=busy==null,modifier=Modifier.fillMaxWidth()){Text("تعديل البنود قبل القبول")}
         Row(horizontalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.fillMaxWidth()){
         Button(onClick={decisionTarget=MerchantDecision(order.id,false,false)},enabled=busy==null,modifier=Modifier.weight(1f)){Text("قبول الطلب")}
         OutlinedButton(onClick={decisionTarget=MerchantDecision(order.id,false,true)},enabled=busy==null,modifier=Modifier.weight(1f)){Text("رفض الطلب")}
        }
        if(order.status in setOf("pending","confirmed","in_progress")&&order.fulfillmentSupported)Button(onClick={if(order.paymentStatus=="unpaid")cashTarget=order else fulfill(order)},enabled=busy==null&&locationId.isNotBlank(),modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.ReceiptLong,null);Spacer(Modifier.width(7.dp));Text("اعتماد وإتمام البيع")}
        else if(order.status in setOf("pending","confirmed","in_progress"))Text("الإتمام الآلي غير متاح لهذا النوع؛ لم تُنفذ حركة مالية أو مخزنية.",fontSize=11.sp,color=CustomerOrdersMuted)
       }
      }
     }
    }
   }else{
    if(!loading&&filteredBookings.isEmpty())item{MerchantQueueEmpty("لا توجد حجوزات مطابقة للمرشحات.")}
    items(filteredBookings,key={"b:"+it.id}){booking->
     val open=expanded==booking.id
     Card(colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,CustomerOrdersBorder),shape=RoundedCornerShape(16.dp)){
      Column(Modifier.fillMaxWidth().padding(13.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
       Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("حجز "+booking.bookingNumber,fontWeight=FontWeight.Bold,color=CustomerOrdersInk);Text(booking.customerName+" • "+booking.resourceName,fontSize=12.sp,color=CustomerOrdersMuted);Text(merchantDate(booking.startsAt)+" — "+merchantDate(booking.endsAt),fontSize=11.sp,color=CustomerOrdersMuted)};Text(merchantBookingStatus(booking.status),fontWeight=FontWeight.SemiBold,color=CustomerOrdersGreen)}
       Row(verticalAlignment=Alignment.CenterVertically){Text("الطلب المرتبط "+booking.orderNumber,Modifier.weight(1f),fontSize=12.sp);Text(customerOrderMoney(booking.totalMinor,booking.currencyCode),fontWeight=FontWeight.Bold)}
       Row {
        TextButton(onClick={expanded=if(open)null else booking.id}){Text(if(open)"إغلاق التفاصيل" else "التفاصيل والبنود")}
        TextButton(onClick={action("booking-visibility:"+booking.id,{setMerchantCustomerBookingHidden(context,booking.id,!booking.hidden)}){if(booking.hidden)"استُعيد الحجز." else "أُخفي الحجز دون حذف بياناته."}},enabled=busy==null){Text(if(booking.hidden)"إظهار مجددًا" else "إخفاء من القائمة")}
       }
       if(open){
        Text("حالة الطلب المرتبط: "+customerOrderStatus(booking.orderStatus)+" • الدفع: "+booking.paymentStatus,fontSize=12.sp)
        if(booking.notes.isNotBlank())Text("ملاحظات: "+booking.notes,fontSize=12.sp)
        if(booking.decisionReason.isNotBlank())Text("سبب القرار: "+booking.decisionReason,fontSize=12.sp,color=CustomerOrdersMuted)
        booking.items.forEach{Text(it.name+" × "+it.quantity+" — "+customerOrderMoney(it.lineTotalMinor,booking.currencyCode),fontSize=12.sp)}
        if(booking.status=="requested")Row(horizontalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.fillMaxWidth()){
         Button(onClick={decisionTarget=MerchantDecision(booking.id,true,false)},enabled=busy==null,modifier=Modifier.weight(1f)){Text("تأكيد الحجز")}
         OutlinedButton(onClick={decisionTarget=MerchantDecision(booking.id,true,true)},enabled=busy==null,modifier=Modifier.weight(1f)){Text("رفض الحجز")}
        }
        Text("قرار الحجز يحدّث الحجز والطلب المرتبط ذريًا ويتحقق من السعة على الخادم.",fontSize=11.sp,color=CustomerOrdersMuted)
       }
      }
     }
    }
   }
  }
 }
 cashTarget?.let{order->AlertDialog(onDismissRequest={cashTarget=null},title={Text("تأكيد استلام المبلغ نقدًا")},text={Text("سيُسجّل البيع والتحصيل وحركة المخزون بقيمة "+customerOrderMoney(order.totalMinor,order.currencyCode)+". تابع فقط إذا استلمت المبلغ كاملًا.")},confirmButton={Button(onClick={cashTarget=null;fulfill(order)},enabled=busy==null&&locationId.isNotBlank()){Text("استلمت المبلغ — إتمام البيع")}},dismissButton={TextButton(onClick={cashTarget=null}){Text("رجوع")}})}
 decisionTarget?.let{target->
  var reason by remember(target.id,target.reject){mutableStateOf("")}
  AlertDialog(onDismissRequest={if(busy==null)decisionTarget=null},title={Text(if(target.booking){if(target.reject)"رفض الحجز" else "تأكيد الحجز"}else{if(target.reject)"رفض الطلب" else "قبول الطلب"})},
   text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text(if(target.reject)"سبب الرفض مطلوب وسيحفظ في سجل التدقيق." else "القبول لا ينشئ فاتورة ولا يخصم المخزون.");if(target.reject)OutlinedTextField(reason,{if(it.length<=500)reason=it},label={Text("سبب الرفض")},modifier=Modifier.fillMaxWidth())}},
   confirmButton={Button(enabled=busy==null&&(!target.reject||reason.isNotBlank()),onClick={val d=if(target.reject)"reject" else "accept";action((if(target.booking)"booking:" else "order:")+target.id,{if(target.booking)decideMerchantCustomerBooking(context,target.id,d,reason)else decideMerchantCustomerOrder(context,target.id,d,reason)}){"تم تنفيذ القرار. الحالة الجديدة: "+it.optString("status")};decisionTarget=null}){Text("تأكيد القرار")}},
   dismissButton={TextButton(onClick={decisionTarget=null},enabled=busy==null){Text("رجوع")}})
 }
 editTarget?.let { order ->
  MerchantOrderLineEditor(order,editableOfferings,busy!=null,{ if(busy==null)editTarget=null }) { lines,reason ->
   action("edit-order:"+order.id,{saveMerchantCustomerOrderLines(context,order.id,lines,reason)}) {
    "حُفظت البنود على الخادم. الإجمالي الجديد: "+customerOrderMoney(it.optLong("total_minor"),it.optString("currency_code",order.currencyCode))
   }
   editTarget=null
  }
 }
}


private data class MerchantDecision(val id:String,val booking:Boolean,val reject:Boolean)
@Composable private fun MerchantQueueEmpty(message:String){Card(colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,CustomerOrdersBorder)){Column(Modifier.fillMaxWidth().padding(22.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(message,color=CustomerOrdersMuted);Text("حدّث البيانات أو غيّر المرشحات.",fontSize=11.sp,color=CustomerOrdersMuted)}}}

@Composable
private fun MerchantOrderLineEditor(
 order: CustomerOrderFulfillmentOrder,
 offerings: List<MerchantEditableOffering>,
 busy: Boolean,
 onDismiss: () -> Unit,
 onSave: (List<Pair<String,String>>,String) -> Unit
) {
 val quantities=remember(order.id){mutableStateMapOf<String,String>().apply{order.items.forEach{put(it.offeringId,it.quantity)}}}
 var query by remember{mutableStateOf("")}
 var reason by remember{mutableStateOf("")}
 var newQuantity by remember{mutableStateOf("1")}
 var menu by remember{mutableStateOf(false)}
 val matches=offerings.filter{it.name.contains(query,true)&&it.currencyCode.equals(order.currencyCode,true)&&it.id !in quantities.keys}.take(25)
 AlertDialog(onDismissRequest=onDismiss,title={Text("تعديل بنود الطلب "+order.orderNumber)},
  text={Column(Modifier.fillMaxWidth().heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(7.dp)){
   Text("التعديل متاح قبل قبول الطلب فقط. يتحقق الخادم من الأصناف والأسعار والعملة ويعيد احتساب الإجمالي.",fontSize=12.sp,color=CustomerOrdersMuted)
   order.items.filter{it.offeringId in quantities.keys}.forEach{line->
    Column { Text(line.name,fontWeight=FontWeight.SemiBold);Row(verticalAlignment=Alignment.CenterVertically){
     OutlinedTextField(quantities[line.offeringId].orEmpty(),{quantities[line.offeringId]=it},label={Text("الكمية")},modifier=Modifier.weight(1f),singleLine=true)
     TextButton(onClick={quantities.remove(line.offeringId)}){Text("حذف")}
    }}
   }
   OutlinedTextField(query,{query=it},label={Text("بحث لإضافة صنف")},modifier=Modifier.fillMaxWidth(),singleLine=true)
   Box { OutlinedButton(onClick={menu=true},enabled=matches.isNotEmpty(),modifier=Modifier.fillMaxWidth()){Text("إضافة صنف ("+matches.size+")")}
    DropdownMenu(menu,{menu=false},modifier=Modifier.heightIn(max=240.dp)){matches.forEach{o->DropdownMenuItem(text={Column{Text(o.name);Text(customerOrderMoney(o.priceMinor,o.currencyCode)+" / "+o.unitCode,fontSize=11.sp,color=CustomerOrdersMuted)}},onClick={quantities[o.id]=newQuantity;menu=false;query=""})}}
   }
   OutlinedTextField(newQuantity,{newQuantity=it},label={Text("الكمية الافتراضية")},singleLine=true)
   OutlinedTextField(reason,{if(it.length<=500)reason=it},label={Text("سبب التعديل (اختياري)")},modifier=Modifier.fillMaxWidth())
   if(quantities.isEmpty())Text("يجب إبقاء بند واحد على الأقل.",color=MaterialTheme.colorScheme.error)
  }},
  confirmButton={Button(enabled=!busy&&quantities.isNotEmpty()&&quantities.values.all{it.toBigDecimalOrNull()?.let{q->q>java.math.BigDecimal.ZERO&&q<=java.math.BigDecimal("100000")&&q.stripTrailingZeros().scale()<=0}==true},onClick={onSave(quantities.entries.map{it.key to it.value},reason)}){Text("حفظ وإعادة احتساب")}},
  dismissButton={TextButton(onClick=onDismiss,enabled=!busy){Text("إلغاء")}}
 )
}
