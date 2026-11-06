package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.util.concurrent.Executors

private val supportTicketIo=Executors.newSingleThreadExecutor()
private val supportTicketMain=Handler(Looper.getMainLooper())
private val supportInk=Color(0xFF172B25)
private val supportMuted=Color(0xFF66756F)
private val supportBorder=Color(0xFFE3EAE6)
private val supportGreen=Color(0xFF167D63)

@Composable
internal fun CommercialSupportTicketsScreen(context:Context,onBack:()->Unit){
 val appContext=context.applicationContext
 var tickets by remember{mutableStateOf<List<CommercialSupportTicket>>(emptyList())}
 var customers by remember{mutableStateOf<List<CommercialCloudCustomer>>(emptyList())}
 var members by remember{mutableStateOf<List<CommercialSupportMember>>(emptyList())}
 var role by remember{mutableStateOf("staff")}
 var filter by remember{mutableStateOf("")}
 var loading by remember{mutableStateOf(true)}
 var busy by remember{mutableStateOf(false)}
 var error by remember{mutableStateOf("")}
 var notice by remember{mutableStateOf("")}
 var refresh by remember{mutableIntStateOf(0)}
 var createOpen by remember{mutableStateOf(false)}
 var selectedTicket by remember{mutableStateOf<CommercialSupportTicket?>(null)}
 var messages by remember{mutableStateOf<List<CommercialSupportMessage>>(emptyList())}
 var messagesLoading by remember{mutableStateOf(false)}

 LaunchedEffect(refresh,filter){
  loading=true;error=""
  supportTicketIo.execute{
   val ticketResult=runCatching{loadCommercialSupportTickets(appContext,filter)}
   val customerResult=runCatching{loadCommercialCloudCustomers(appContext)}.getOrDefault(emptyList())
   val memberResult=runCatching{loadCommercialSupportMembers(appContext)}.getOrDefault(emptyList())
   val roleResult=runCatching{loadCommercialSupportRole(appContext)}.getOrDefault("staff")
   supportTicketMain.post{
    loading=false
    ticketResult.onSuccess{tickets=it}.onFailure{error=it.message?:"تعذر تحميل تذاكر الدعم."}
    customers=customerResult
    members=memberResult
    role=roleResult
   }
  }
 }

 Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()){
   TextButton(onClick=onBack){Text("رجوع")}
   Spacer(Modifier.width(8.dp))
   Column(Modifier.weight(1f)){
    Text("الدعم وخدمة العملاء",fontWeight=FontWeight.Bold,color=supportInk,fontSize=19.sp)
    Text("تسجيل البلاغات ومتابعتها داخل نشاطك",color=supportMuted,fontSize=11.sp)
   }
   Button(onClick={createOpen=true},enabled=!busy){Text("تذكرة جديدة")}
  }
  Row(horizontalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())){
   listOf("" to "الكل","open" to "مفتوحة","in_progress" to "قيد المعالجة","waiting_customer" to "بانتظار العميل","resolved" to "تم الحل","closed" to "مغلقة").forEach{(value,label)->
    FilterChip(selected=filter==value,onClick={filter=value},label={Text(label,fontSize=10.sp)})
   }
  }
  if(notice.isNotBlank())Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFEAF6EF))){Text(notice,Modifier.padding(12.dp),color=supportGreen,fontSize=12.sp)}
  if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
  if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
  if(!loading&&tickets.isEmpty())Text("لا توجد تذاكر في هذا القسم.",color=supportMuted,modifier=Modifier.padding(18.dp))
  LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=16.dp)){
   items(tickets,key={it.id}){ticket->
    Card(
     onClick={
      selectedTicket=ticket;messagesLoading=true;messages=emptyList();error=""
      supportTicketIo.execute{
       val result=runCatching{loadCommercialSupportMessages(appContext,ticket.id)}
       supportTicketMain.post{messagesLoading=false;result.onSuccess{messages=it}.onFailure{error=it.message?:"تعذر تحميل محادثة التذكرة."}}
      }
     },
     colors=CardDefaults.cardColors(containerColor=Color.White),
     border=BorderStroke(1.dp,supportBorder),shape=RoundedCornerShape(15.dp)
    ){
     Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){
       Text("#${ticket.ticketNo}",color=supportGreen,fontWeight=FontWeight.Bold,fontSize=12.sp)
       Spacer(Modifier.width(8.dp))
       Text(ticket.subject,color=supportInk,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
       Text(supportStatus(ticket.status),color=supportGreen,fontSize=10.sp)
      }
      Text(ticket.customerName.ifBlank{"بدون ربط عميل"}+" • "+supportCategory(ticket.category),color=supportMuted,fontSize=11.sp)
      Text(ticket.description,maxLines=2,color=supportInk,fontSize=12.sp)
      Row(verticalAlignment=Alignment.CenterVertically){
       Text("الأولوية: "+supportPriority(ticket.priority),color=supportMuted,fontSize=10.sp)
       Spacer(Modifier.width(12.dp))
       Text("المسؤول: "+ticket.assignedTo.take(8),color=supportMuted,fontSize=10.sp)
      }
     }
    }
   }
  }
 }

 if(createOpen){
  var subject by remember{mutableStateOf("")}
  var description by remember{mutableStateOf("")}
  var category by remember{mutableStateOf("general")}
  var priority by remember{mutableStateOf("normal")}
  var customerId by remember{mutableStateOf("")}
  var assignedTo by remember{mutableStateOf("")}
  var categoryMenu by remember{mutableStateOf(false)}
  var priorityMenu by remember{mutableStateOf(false)}
  var customerMenu by remember{mutableStateOf(false)}
  var assigneeMenu by remember{mutableStateOf(false)}
  var localError by remember{mutableStateOf("")}
  AlertDialog(
   onDismissRequest={if(!busy)createOpen=false},
   title={Text("إنشاء تذكرة دعم")},
   text={Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){
    OutlinedTextField(subject,{subject=it.take(180)},label={Text("عنوان البلاغ")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    OutlinedTextField(description,{description=it.take(4000)},label={Text("تفاصيل المشكلة")},modifier=Modifier.fillMaxWidth(),minLines=3)
    Box{
     OutlinedButton(onClick={categoryMenu=true},modifier=Modifier.fillMaxWidth()){Text("التصنيف: "+supportCategory(category))}
     DropdownMenu(categoryMenu,{categoryMenu=false}){
      listOf("general","product","payment","delivery","technical","complaint","return","other").forEach{value->
       DropdownMenuItem(text={Text(supportCategory(value))},onClick={category=value;categoryMenu=false})
      }
     }
    }
    Box{
     OutlinedButton(onClick={priorityMenu=true},modifier=Modifier.fillMaxWidth()){Text("الأولوية: "+supportPriority(priority))}
     DropdownMenu(priorityMenu,{priorityMenu=false}){
      listOf("low","normal","high","urgent").forEach{value->
       DropdownMenuItem(text={Text(supportPriority(value))},onClick={priority=value;priorityMenu=false})
      }
     }
    }
    Box{
     OutlinedButton(onClick={customerMenu=true},modifier=Modifier.fillMaxWidth()){
      Text("العميل: "+(customers.firstOrNull{it.id==customerId}?.label?:"بدون ربط عميل"))
     }
     DropdownMenu(customerMenu,{customerMenu=false}){
      DropdownMenuItem(text={Text("بدون ربط عميل")},onClick={customerId="";customerMenu=false})
      customers.forEach{customer->DropdownMenuItem(text={Text(customer.label)},onClick={customerId=customer.id;customerMenu=false})}
     }
    }
    if(role in setOf("owner","admin","manager")){
     Box{
      OutlinedButton(onClick={assigneeMenu=true},modifier=Modifier.fillMaxWidth()){
       Text("المسؤول: "+(members.firstOrNull{it.userId==assignedTo}?.label?:"تعييني تلقائياً"))
      }
      DropdownMenu(assigneeMenu,{assigneeMenu=false}){
       DropdownMenuItem(text={Text("تعييني تلقائياً")},onClick={assignedTo="";assigneeMenu=false})
       members.forEach{member->DropdownMenuItem(text={Text(member.label)},onClick={assignedTo=member.userId;assigneeMenu=false})}
      }
     }
    }
    if(localError.isNotBlank())Text(localError,color=MaterialTheme.colorScheme.error,fontSize=11.sp)
   }},
   confirmButton={Button(enabled=!busy&&subject.isNotBlank()&&description.isNotBlank(),onClick={
    busy=true;localError=""
    supportTicketIo.execute{
     val result=runCatching{createCommercialSupportTicket(appContext,customerId.takeIf{it.isNotBlank()},subject,description,category,priority,assignedTo.takeIf{it.isNotBlank()},newCommercialSupportIdempotencyKey())}
     supportTicketMain.post{busy=false;result.onSuccess{createOpen=false;notice="تم إنشاء التذكرة رقم #${it.optLong("ticket_no")}.";refresh++}.onFailure{localError=it.message?:"تعذر إنشاء التذكرة."}}
    }
   }){Text(if(busy)"جارٍ الحفظ…" else "حفظ التذكرة")}},
   dismissButton={TextButton(onClick={if(!busy)createOpen=false}){Text("إلغاء")}}
  )
 }

 selectedTicket?.let{ticket->
  var selectedStatus by remember(ticket.id,ticket.version){mutableStateOf(ticket.status)}
  var selectedPriority by remember(ticket.id,ticket.version){mutableStateOf(ticket.priority)}
  var selectedAssignee by remember(ticket.id,ticket.version){mutableStateOf(ticket.assignedTo)}
  var assigneeMenu by remember{mutableStateOf(false)}
  var statusMenu by remember{mutableStateOf(false)}
  var priorityMenu by remember{mutableStateOf(false)}
  var messageText by remember(ticket.id){mutableStateOf("")}
  var localError by remember{mutableStateOf("")}
  val canManage=role in setOf("owner","admin","manager")
  val statusOptions=buildList{
   add("open");add("in_progress");add("waiting_customer");add("resolved")
   if(canManage)add("closed")
  }
  AlertDialog(
   onDismissRequest={if(!busy)selectedTicket=null},
   title={Text("#${ticket.ticketNo} • ${ticket.subject}")},
   text={Column(Modifier.fillMaxWidth().heightIn(max=540.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
    Text(ticket.description,color=supportInk,fontSize=12.sp)
    Text("العميل: "+ticket.customerName.ifBlank{"غير مرتبط"},color=supportMuted,fontSize=11.sp)
    if(canManage){
     Box{
      OutlinedButton(onClick={assigneeMenu=true},modifier=Modifier.fillMaxWidth()){
       Text("المسؤول: "+(members.firstOrNull{it.userId==selectedAssignee}?.label?:selectedAssignee.take(8)))
      }
      DropdownMenu(assigneeMenu,{assigneeMenu=false}){
       members.forEach{member->DropdownMenuItem(text={Text(member.label)},onClick={selectedAssignee=member.userId;assigneeMenu=false})}
      }
     }
    }
    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
     Box(Modifier.weight(1f)){
      OutlinedButton(onClick={statusMenu=true},modifier=Modifier.fillMaxWidth()){Text(supportStatus(selectedStatus),fontSize=11.sp)}
      DropdownMenu(statusMenu,{statusMenu=false}){
       statusOptions.forEach{value->DropdownMenuItem(text={Text(supportStatus(value))},onClick={selectedStatus=value;statusMenu=false})}
      }
     }
     Box(Modifier.weight(1f)){
      OutlinedButton(onClick={priorityMenu=true},modifier=Modifier.fillMaxWidth()){Text(supportPriority(selectedPriority),fontSize=11.sp)}
      DropdownMenu(priorityMenu,{priorityMenu=false}){
       listOf("low","normal","high","urgent").forEach{value->DropdownMenuItem(text={Text(supportPriority(value))},onClick={selectedPriority=value;priorityMenu=false})}
      }
     }
    }
    Button(
     enabled=!busy&&(selectedStatus!=ticket.status||selectedPriority!=ticket.priority||selectedAssignee!=ticket.assignedTo),
     modifier=Modifier.fillMaxWidth(),
     onClick={
      busy=true;localError=""
      supportTicketIo.execute{
       val result=runCatching{updateCommercialSupportTicket(appContext,ticket,selectedStatus,selectedPriority,selectedAssignee.takeIf{it.isNotBlank()})}
       supportTicketMain.post{busy=false;result.onSuccess{selectedTicket=null;notice="تم تحديث التذكرة.";refresh++}.onFailure{localError=it.message?:"تعذر تحديث التذكرة."}}
      }
     }
    ){Text("حفظ حالة التذكرة")}
    Divider()
    Text("الملاحظات الداخلية",fontWeight=FontWeight.SemiBold,color=supportInk,fontSize=13.sp)
    if(messagesLoading)LinearProgressIndicator(Modifier.fillMaxWidth())
    else if(messages.isEmpty())Text("لا توجد ملاحظات بعد.",color=supportMuted,fontSize=11.sp)
    else LazyColumn(Modifier.fillMaxWidth().heightIn(max=170.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
     items(messages,key={it.id}){msg->
      Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFF5F8F6)),border=BorderStroke(1.dp,supportBorder)){
       Column(Modifier.fillMaxWidth().padding(8.dp)){
        Text(msg.body,color=supportInk,fontSize=12.sp)
        Text(msg.createdBy.take(8)+" • "+msg.createdAt.replace('T',' ').take(16),color=supportMuted,fontSize=9.sp)
       }
      }
     }
    }
    OutlinedTextField(messageText,{messageText=it.take(4000)},label={Text("أضف ملاحظة داخلية")},modifier=Modifier.fillMaxWidth(),minLines=2)
    if(localError.isNotBlank())Text(localError,color=MaterialTheme.colorScheme.error,fontSize=11.sp)
    Button(
     enabled=!busy&&messageText.isNotBlank()&&ticket.status!="closed",
     modifier=Modifier.fillMaxWidth(),
     onClick={
      busy=true;localError=""
      supportTicketIo.execute{
       val result=runCatching{addCommercialSupportMessage(appContext,ticket.id,messageText,newCommercialSupportIdempotencyKey())}
       val refreshed=runCatching{loadCommercialSupportMessages(appContext,ticket.id)}
       val refreshedTickets=runCatching{loadCommercialSupportTickets(appContext,filter)}
       supportTicketMain.post{
        busy=false
        result.onSuccess{
         messageText=""
         refreshed.onSuccess{messages=it}
         refreshedTickets.onSuccess{rows->tickets=rows;selectedTicket=rows.firstOrNull{row->row.id==ticket.id}?:ticket}
         notice="تمت إضافة الملاحظة."
         refresh++
        }.onFailure{localError=it.message?:"تعذر إضافة الملاحظة."}
       }
      }
     }
    ){Text("إرسال الملاحظة")}
   }},
   confirmButton={TextButton(onClick={if(!busy)selectedTicket=null}){Text("إغلاق")}}
  )
 }
}

private fun supportStatus(value:String):String=when(value){
 "open"->"مفتوحة";"in_progress"->"قيد المعالجة";"waiting_customer"->"بانتظار العميل"
 "resolved"->"تم الحل";"closed"->"مغلقة";else->value
}
private fun supportPriority(value:String):String=when(value){
 "low"->"منخفضة";"normal"->"عادية";"high"->"مرتفعة";"urgent"->"عاجلة";else->value
}
private fun supportCategory(value:String):String=when(value){
 "general"->"عام";"product"->"منتج";"payment"->"دفع";"delivery"->"توصيل"
 "technical"->"تقنية";"complaint"->"شكوى";"return"->"مرتجع";"other"->"أخرى";else->value
}
