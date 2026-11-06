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
import androidx.compose.material.icons.filled.Add
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
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.Executors

private val CrmInk = Color(0xFF183B35)
private val CrmMuted = Color(0xFF71817B)
private val CrmBorder = Color(0xFFE5EBE8)
private val crmIo = Executors.newSingleThreadExecutor()
private val crmMain = Handler(Looper.getMainLooper())
private val crmStages = listOf("new","contacted","qualified","proposal","won","lost")
private fun crmStageLabel(stage:String)=when(stage){
 "new"->"جديد";"contacted"->"تم التواصل";"qualified"->"مؤهل";"proposal"->"عرض سعر";"won"->"تم الفوز";"lost"->"خسارة";else->stage
}
private fun crmSourceLabel(source:String)=when(source){
 "referral"->"توصية";"walk_in"->"زيارة مباشرة";"phone"->"اتصال";"social"->"تواصل اجتماعي";"website"->"الموقع";"campaign"->"حملة";else->"أخرى"
}
private fun crmActivityLabel(type:String)=when(type){
 "call"->"مكالمة";"meeting"->"اجتماع";"email"->"بريد";"task"->"مهمة";else->"ملاحظة"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommercialCrmScreen(context:Context,onBack:()->Unit){
 var leads by remember{mutableStateOf<List<CommercialCrmLead>>(emptyList())}
 var loading by remember{mutableStateOf(false)}
 var busy by remember{mutableStateOf(false)}
 var error by remember{mutableStateOf("")}
 var notice by remember{mutableStateOf("")}
 var refresh by remember{mutableIntStateOf(0)}
 var search by remember{mutableStateOf("")}
 var stageFilter by remember{mutableStateOf("")}
 var filterMenu by remember{mutableStateOf(false)}
 var createOpen by remember{mutableStateOf(false)}
 var editTarget by remember{mutableStateOf<CommercialCrmLead?>(null)}
 var activityTarget by remember{mutableStateOf<CommercialCrmLead?>(null)}
 var activityRows by remember{mutableStateOf<List<CommercialCrmActivity>>(emptyList())}
 var activityLoading by remember{mutableStateOf(false)}
 var activityEditor by remember{mutableStateOf<CommercialCrmLead?>(null)}

 fun reload(){
  if(loading||busy)return
  loading=true;error=""
  crmIo.execute{
   val result=runCatching{loadCommercialCrmLeads(context,stageFilter)}
   crmMain.post{loading=false;result.onSuccess{leads=it}.onFailure{error=it.message?:"تعذر تحميل العملاء المحتملين."}}
  }
 }
 LaunchedEffect(refresh,stageFilter){
  loading=true;error=""
  crmIo.execute{
   val result=runCatching{loadCommercialCrmLeads(context,stageFilter)}
   crmMain.post{loading=false;result.onSuccess{leads=it}.onFailure{error=it.message?:"تعذر تحميل العملاء المحتملين."}}
  }
 }
 val visible=leads.filter{search.isBlank()||listOf(it.name,it.company,it.phone,it.email,it.notes).any{x->x.contains(search.trim(),true)}}
 Scaffold(topBar={TopAppBar(
  title={Column{Text("إدارة علاقات العملاء",fontWeight=FontWeight.Bold,color=CrmInk,fontSize=18.sp);Text("العملاء المحتملون والمتابعات",fontSize=11.sp,color=CrmMuted)}},
  navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,contentDescription="رجوع",tint=CrmInk)}},
  actions={IconButton(onClick={refresh++},enabled=!loading&&!busy){Icon(Icons.Default.Refresh,contentDescription="تحديث",tint=CrmInk)}}
 )}){padding->
  LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
   item{
    Card(colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,CrmBorder),shape=RoundedCornerShape(16.dp)){
     Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){
       Column(Modifier.weight(1f)){Text("العملاء المحتملون",fontWeight=FontWeight.Bold,color=CrmInk);Text("${leads.size} سجل ضمن نطاق صلاحيتك",fontSize=11.sp,color=CrmMuted)}
       Button(onClick={createOpen=true},enabled=!busy){Icon(Icons.Default.Add,null);Spacer(Modifier.width(4.dp));Text("إضافة عميل")}
      }
      OutlinedTextField(search,{search=it},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("بحث بالاسم أو الشركة أو الهاتف")},leadingIcon={Icon(Icons.Default.Search,null)})
      Box{
       OutlinedButton(onClick={filterMenu=true},modifier=Modifier.fillMaxWidth()){
        Text("المرحلة: "+if(stageFilter.isBlank())"كل المراحل" else crmStageLabel(stageFilter))
       }
       DropdownMenu(expanded=filterMenu,onDismissRequest={filterMenu=false}){
        DropdownMenuItem(text={Text("كل المراحل")},onClick={stageFilter="";filterMenu=false})
        crmStages.forEach{s->DropdownMenuItem(text={Text(crmStageLabel(s))},onClick={stageFilter=s;filterMenu=false})}
       }
      }
      Text("تظهر للموظف سجلاته المسندة إليه أو التي أنشأها، وللمدير سجلات المؤسسة حسب الصلاحية.",fontSize=11.sp,color=CrmMuted)
     }
    }
   }
   if(loading)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
   if(error.isNotBlank())item{Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)){Column(Modifier.padding(12.dp)){Text(error);TextButton(onClick={refresh++}){Text("إعادة المحاولة")}}}}
   if(notice.isNotBlank())item{Text(notice,color=CrmInk,fontSize=12.sp)}
   if(!loading&&visible.isEmpty()&&error.isBlank())item{Text(if(search.isBlank())"لا توجد سجلات عملاء محتملين بعد. أضف أول سجل للبدء." else "لا توجد نتائج مطابقة.",color=CrmMuted)}
   items(visible,key={it.id}){lead->
    Card(colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,CrmBorder),shape=RoundedCornerShape(16.dp)){
     Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){
       Column(Modifier.weight(1f)){Text(lead.name,fontWeight=FontWeight.Bold,color=CrmInk,fontSize=16.sp);if(lead.company.isNotBlank())Text(lead.company,color=CrmMuted,fontSize=12.sp)}
       AssistChip(onClick={editTarget=lead},label={Text(crmStageLabel(lead.stage))})
      }
      if(lead.phone.isNotBlank())Text("الهاتف: "+lead.phone,fontSize=12.sp,color=CrmInk)
      if(lead.email.isNotBlank())Text("البريد: "+lead.email,fontSize=12.sp,color=CrmInk)
      Row(horizontalArrangement=Arrangement.SpaceBetween,modifier=Modifier.fillMaxWidth()){
       Text("المصدر: "+crmSourceLabel(lead.source),fontSize=11.sp,color=CrmMuted)
       Text("القيمة المتوقعة: "+String.format(Locale.US,"%,d %s",lead.expectedValueMinor,lead.currencyCode),fontSize=11.sp,color=CrmInk,fontWeight=FontWeight.SemiBold)
      }
      if(lead.nextFollowUpAt.isNotBlank())Text("المتابعة القادمة: "+lead.nextFollowUpAt.replace("T"," ").take(16),fontSize=11.sp,color=CrmMuted)
      if(lead.notes.isNotBlank())Text(lead.notes,fontSize=12.sp,color=CrmMuted)
      Row(horizontalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.fillMaxWidth()){
       OutlinedButton(onClick={editTarget=lead},modifier=Modifier.weight(1f),enabled=!busy){Text("تحديث المرحلة")}
       OutlinedButton(onClick={activityTarget=lead;activityLoading=true;crmIo.execute{
        val result=runCatching{loadCommercialCrmActivities(context,lead.id)}
        crmMain.post{activityLoading=false;result.onSuccess{activityRows=it}.onFailure{error=it.message?:"تعذر تحميل سجل المتابعة."}}
       }},modifier=Modifier.weight(1f),enabled=!busy){Text("سجل المتابعة")}
      }
      Button(onClick={activityEditor=lead},modifier=Modifier.fillMaxWidth(),enabled=!busy){Text("تسجيل اتصال أو مهمة متابعة")}
     }
    }
   }
  }
 }

 if(createOpen){
  var name by remember{mutableStateOf("")};var company by remember{mutableStateOf("")}
  var phone by remember{mutableStateOf("")};var email by remember{mutableStateOf("")}
  var source by remember{mutableStateOf("other")};var sourceMenu by remember{mutableStateOf(false)}
  var value by remember{mutableStateOf("0")};var notes by remember{mutableStateOf("")}
  var localError by remember{mutableStateOf("")}
  AlertDialog(onDismissRequest={if(!busy)createOpen=false},title={Text("إضافة عميل محتمل")},
   text={Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(7.dp)){
    OutlinedTextField(name,{name=it.take(180)},label={Text("اسم العميل *")},singleLine=true,modifier=Modifier.fillMaxWidth())
    OutlinedTextField(company,{company=it.take(180)},label={Text("الشركة أو النشاط")},singleLine=true,modifier=Modifier.fillMaxWidth())
    OutlinedTextField(phone,{phone=it.take(40)},label={Text("رقم الهاتف")},singleLine=true,modifier=Modifier.fillMaxWidth())
    OutlinedTextField(email,{email=it.take(254)},label={Text("البريد الإلكتروني")},singleLine=true,modifier=Modifier.fillMaxWidth())
    Box{OutlinedButton(onClick={sourceMenu=true},modifier=Modifier.fillMaxWidth()){Text("مصدر العميل: "+crmSourceLabel(source))}
     DropdownMenu(sourceMenu,{sourceMenu=false}){listOf("referral","walk_in","phone","social","website","campaign","other").forEach{s->DropdownMenuItem(text={Text(crmSourceLabel(s))},onClick={source=s;sourceMenu=false})}}
    }
    OutlinedTextField(value,{value=it.filter(Char::isDigit).take(15)},label={Text("قيمة متوقعة (بالوحدة الصغرى)")},singleLine=true,modifier=Modifier.fillMaxWidth())
    OutlinedTextField(notes,{notes=it.take(4000)},label={Text("ملاحظات")},modifier=Modifier.fillMaxWidth(),minLines=2)
    if(localError.isNotBlank())Text(localError,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
   }},
   confirmButton={Button(enabled=!busy&&name.isNotBlank()&&value.toLongOrNull()!=null,onClick={
    busy=true;localError="";val snap=listOf(name,company,phone,email,source,value,notes)
    crmIo.execute{val result=runCatching{createCommercialCrmLead(context,snap[0],snap[1],snap[2],snap[3],snap[4],snap[5].toLong(),snap[6],newCommercialCrmIdempotencyKey("lead"))}
     crmMain.post{busy=false;result.onSuccess{notice="تم حفظ العميل المحتمل.";createOpen=false;refresh++}.onFailure{localError=it.message?:"تعذر حفظ السجل."}}
    }
   }){Text("حفظ")}},
   dismissButton={TextButton(onClick={createOpen=false},enabled=!busy){Text("إلغاء")}}
  )
 }

 editTarget?.let{lead->
  var stage by remember(lead.id,lead.version){mutableStateOf(lead.stage)}
  var notes by remember(lead.id,lead.version){mutableStateOf(lead.notes)}
  var followUp by remember(lead.id,lead.version){mutableStateOf(lead.nextFollowUpAt)}
  var lostReason by remember(lead.id,lead.version){mutableStateOf(lead.lostReason)}
  var stageMenu by remember{mutableStateOf(false)}
  var localError by remember{mutableStateOf("")}
  AlertDialog(onDismissRequest={if(!busy)editTarget=null},title={Text("تحديث العميل المحتمل")},
   text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
    Box{OutlinedButton(onClick={stageMenu=true},modifier=Modifier.fillMaxWidth()){Text("المرحلة: "+crmStageLabel(stage))}
     DropdownMenu(stageMenu,{stageMenu=false}){crmStages.forEach{s->DropdownMenuItem(text={Text(crmStageLabel(s))},onClick={stage=s;stageMenu=false})}}
    }
    OutlinedTextField(followUp,{followUp=it.take(40)},label={Text("موعد المتابعة (ISO 8601 مع المنطقة الزمنية)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    OutlinedTextField(notes,{notes=it.take(4000)},label={Text("ملاحظات")},modifier=Modifier.fillMaxWidth(),minLines=2)
    if(stage=="lost")OutlinedTextField(lostReason,{lostReason=it.take(1000)},label={Text("سبب خسارة العميل *")},modifier=Modifier.fillMaxWidth(),minLines=2)
    if(localError.isNotBlank())Text(localError,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
   }},
   confirmButton={Button(enabled=!busy,onClick={
    val valid=followUp.isBlank()||runCatching{OffsetDateTime.parse(followUp.trim())}.isSuccess
    if(!valid)localError="أدخل موعدًا بصيغة ISO 8601 مثل 2026-11-06T15:00:00+03:00."
    else if(stage=="lost"&&lostReason.isBlank())localError="اكتب سبب خسارة العميل."
    else{
     busy=true;localError=""
     crmIo.execute{val result=runCatching{updateCommercialCrmLead(context,lead,stage,notes,followUp,lostReason)}
      crmMain.post{busy=false;result.onSuccess{notice="تم تحديث المرحلة والمتابعة.";editTarget=null;refresh++}.onFailure{localError=it.message?:"تعذر تحديث السجل."}}
     }
    }
   }){Text("حفظ التحديث")}},
   dismissButton={TextButton(onClick={editTarget=null},enabled=!busy){Text("إلغاء")}}
  )
 }

 activityEditor?.let{lead->
  var type by remember{mutableStateOf("call")};var typeMenu by remember{mutableStateOf(false)}
  var subject by remember{mutableStateOf("")};var body by remember{mutableStateOf("")};var due by remember{mutableStateOf("")}
  var localError by remember{mutableStateOf("")}
  AlertDialog(onDismissRequest={if(!busy)activityEditor=null},title={Text("تسجيل متابعة: "+lead.name)},
   text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
    Box{OutlinedButton(onClick={typeMenu=true},modifier=Modifier.fillMaxWidth()){Text("نوع النشاط: "+crmActivityLabel(type))}
     DropdownMenu(typeMenu,{typeMenu=false}){listOf("call","meeting","email","note","task").forEach{t->DropdownMenuItem(text={Text(crmActivityLabel(t))},onClick={type=t;typeMenu=false})}}
    }
    OutlinedTextField(subject,{subject=it.take(180)},label={Text("عنوان المتابعة *")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    OutlinedTextField(body,{body=it.take(4000)},label={Text("التفاصيل")},modifier=Modifier.fillMaxWidth(),minLines=2)
    OutlinedTextField(due,{due=it.take(40)},label={Text("موعد التنفيذ (ISO 8601 اختياري)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    if(localError.isNotBlank())Text(localError,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
   }},
   confirmButton={Button(enabled=!busy&&subject.isNotBlank(),onClick={
    val valid=due.isBlank()||runCatching{OffsetDateTime.parse(due.trim())}.isSuccess
    if(!valid)localError="تحقق من صيغة التاريخ والمنطقة الزمنية."
    else{
     busy=true;localError=""
     crmIo.execute{val result=runCatching{addCommercialCrmActivity(context,lead.id,type,subject,body,due,newCommercialCrmIdempotencyKey("activity"))}
      crmMain.post{busy=false;result.onSuccess{notice="تم تسجيل النشاط.";activityEditor=null;refresh++}.onFailure{localError=it.message?:"تعذر تسجيل النشاط."}}
     }
    }
   }){Text("تسجيل")}},
   dismissButton={TextButton(onClick={activityEditor=null},enabled=!busy){Text("إلغاء")}}
  )
 }

 activityTarget?.let{lead->
  AlertDialog(onDismissRequest={activityTarget=null},title={Text("سجل المتابعة: "+lead.name)},
   text={Column(Modifier.fillMaxWidth().heightIn(max=420.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
    if(activityLoading)LinearProgressIndicator(Modifier.fillMaxWidth())
    if(!activityLoading&&activityRows.isEmpty())Text("لا توجد اتصالات أو مهام مسجلة بعد.",color=CrmMuted)
    activityRows.forEach{row->
     Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFF7FAF8)),border=BorderStroke(1.dp,CrmBorder)){
      Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
       Text(row.subject,fontWeight=FontWeight.SemiBold,color=CrmInk)
       Text(crmActivityLabel(row.type)+" • "+row.createdAt.replace("T"," ").take(16),fontSize=11.sp,color=CrmMuted)
       if(row.dueAt.isNotBlank())Text("الموعد: "+row.dueAt.replace("T"," ").take(16),fontSize=11.sp,color=CrmMuted)
       if(row.body.isNotBlank())Text(row.body,fontSize=12.sp,color=CrmInk)
      }
     }
    }
   }},
   confirmButton={TextButton(onClick={activityTarget=null}){Text("إغلاق")}}
  )
 }
}
