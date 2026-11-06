package com.adnanalhasani.commercial

import android.app.DatePickerDialog
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

private val CrmOppInk=Color(0xFF183B35)
private val CrmOppMuted=Color(0xFF71817B)
private val CrmOppBorder=Color(0xFFE5EBE8)
private val crmOppIo=Executors.newSingleThreadExecutor()
private val crmOppMain=Handler(Looper.getMainLooper())
private val crmOppStages=listOf("qualification","needs_analysis","proposal","negotiation","won","lost")
private fun crmOppStageLabel(s:String)=when(s){
 "qualification"->"تأهيل";"needs_analysis"->"تحليل الاحتياج";"proposal"->"عرض سعر";"negotiation"->"تفاوض";"won"->"ناجحة";"lost"->"خاسرة";else->s
}
private fun chooseCrmCloseDate(context:Context,current:String,onSelected:(String)->Unit){
 val c=Calendar.getInstance()
 runCatching{SimpleDateFormat("yyyy-MM-dd",Locale.US).parse(current)}.getOrNull()?.let{c.time=it}
 DatePickerDialog(context,{_,y,m,d->onSelected(String.format(Locale.US,"%04d-%02d-%02d",y,m+1,d))},
  c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommercialCrmOpportunitiesScreen(context:Context,onBack:()->Unit){
 var leads by remember{mutableStateOf<List<CommercialCrmLead>>(emptyList())}
 var opportunities by remember{mutableStateOf<List<CommercialCrmOpportunity>>(emptyList())}
 var loading by remember{mutableStateOf(true)}
 var busy by remember{mutableStateOf(false)}
 var error by remember{mutableStateOf("")}
 var notice by remember{mutableStateOf("")}
 var refresh by remember{mutableIntStateOf(0)}
 var stageFilter by remember{mutableStateOf("")}
 var filterMenu by remember{mutableStateOf(false)}
 var createOpen by remember{mutableStateOf(false)}
 var editTarget by remember{mutableStateOf<CommercialCrmOpportunity?>(null)}

 LaunchedEffect(refresh,stageFilter){
  loading=true;error=""
  crmOppIo.execute{
   val result=runCatching{loadCommercialCrmLeads(context) to loadCommercialCrmOpportunities(context,stageFilter)}
   crmOppMain.post{loading=false;result.onSuccess{(l,o)->leads=l;opportunities=o}.onFailure{error=it.message?:"تعذر تحميل فرص المبيعات."}}
  }
 }
 Scaffold(
  containerColor=Color(0xFFF5F7F6),
  topBar={TopAppBar(title={Column{Text("فرص المبيعات",fontWeight=FontWeight.Bold,color=CrmOppInk);Text("خط سير الفرص حتى إغلاق الصفقة",fontSize=11.sp,color=CrmOppMuted)}},
   navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,contentDescription="رجوع",tint=CrmOppInk)}},
   actions={IconButton(onClick={refresh++},enabled=!loading&&!busy){Icon(Icons.Default.Refresh,contentDescription="تحديث",tint=CrmOppInk)}},
   colors=TopAppBarDefaults.topAppBarColors(containerColor=Color(0xFFF5F7F6)))},
  floatingActionButton={FloatingActionButton(onClick={createOpen=true},containerColor=Color(0xFF167D63),contentColor=Color.White){Icon(Icons.Default.Add,contentDescription="فرصة جديدة")}}
 ){padding->
  LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
   item{
    Card(colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,CrmOppBorder),shape=RoundedCornerShape(16.dp)){
     Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
      Text("${opportunities.size} فرصة في النطاق الحالي",fontWeight=FontWeight.Bold,color=CrmOppInk)
      Row(horizontalArrangement=Arrangement.spacedBy(5.dp),modifier=Modifier.fillMaxWidth()){
       crmOppStages.forEach{s->
        val count=opportunities.count{it.stage==s}
        if(count>0)AssistChip(onClick={stageFilter=s},label={Text("${crmOppStageLabel(s)} $count",fontSize=10.sp)})
       }
      }
      Box{
       OutlinedButton(onClick={filterMenu=true},modifier=Modifier.fillMaxWidth()){Text("تصفية: "+if(stageFilter.isBlank())"كل المراحل" else crmOppStageLabel(stageFilter))}
       DropdownMenu(filterMenu,{filterMenu=false}){
        DropdownMenuItem(text={Text("كل المراحل")},onClick={stageFilter="";filterMenu=false})
        crmOppStages.forEach{s->DropdownMenuItem(text={Text(crmOppStageLabel(s))},onClick={stageFilter=s;filterMenu=false})}
       }
      }
      Text("تُسجل الفرصة على عميل محتمل موجود. لا يتم إنشاء عميل مالي أو فاتورة أو حركة مخزون عند تسجيل الفرصة.",fontSize=11.sp,color=CrmOppMuted)
     }
    }
   }
   if(loading)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
   if(error.isNotBlank())item{Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)){Column(Modifier.padding(12.dp)){Text(error);TextButton(onClick={refresh++}){Text("إعادة المحاولة")}}}}
   if(notice.isNotBlank())item{Text(notice,color=CrmOppInk,fontSize=12.sp)}
   if(!loading&&opportunities.isEmpty()&&error.isBlank())item{Text("لا توجد فرص في هذه المرحلة. أضف فرصة مرتبطة بعميل محتمل.",color=CrmOppMuted)}
   items(opportunities,key={it.id}){opp->
    Card(colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,CrmOppBorder),shape=RoundedCornerShape(16.dp)){
     Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){
       Column(Modifier.weight(1f)){Text(opp.title,fontWeight=FontWeight.Bold,color=CrmOppInk,fontSize=16.sp);Text("العميل المحتمل: "+opp.leadName,fontSize=12.sp,color=CrmOppMuted)}
       AssistChip(onClick={editTarget=opp},label={Text(crmOppStageLabel(opp.stage))})
      }
      Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
       Text("احتمال الإغلاق: ${opp.probability}%",fontSize=12.sp,color=CrmOppMuted)
       Text(String.format(Locale.US,"%,d %s",opp.expectedValueMinor,opp.currencyCode),fontSize=13.sp,fontWeight=FontWeight.Bold,color=CrmOppInk)
      }
      if(opp.expectedCloseDate.isNotBlank())Text("تاريخ الإغلاق المتوقع: "+opp.expectedCloseDate,fontSize=11.sp,color=CrmOppMuted)
      if(opp.notes.isNotBlank())Text(opp.notes,fontSize=12.sp,color=CrmOppMuted)
      OutlinedButton(onClick={editTarget=opp},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("تحديث الفرصة")}
     }
    }
   }
  }
 }

 if(createOpen){
  var leadId by remember{mutableStateOf("")};var title by remember{mutableStateOf("")}
  var stage by remember{mutableStateOf("qualification")};var stageMenu by remember{mutableStateOf(false)}
  var probability by remember{mutableStateOf("10")};var value by remember{mutableStateOf("0")}
  var closeDate by remember{mutableStateOf("")};var notes by remember{mutableStateOf("")}
  var leadMenu by remember{mutableStateOf(false)};var localError by remember{mutableStateOf("")}
  AlertDialog(onDismissRequest={if(!busy)createOpen=false},title={Text("إضافة فرصة مبيعات")},
   text={Column(verticalArrangement=Arrangement.spacedBy(7.dp)){
    Box{OutlinedButton(onClick={leadMenu=true},modifier=Modifier.fillMaxWidth()){Text(leads.firstOrNull{it.id==leadId}?.name?:"اختر العميل المحتمل *")}
     DropdownMenu(leadMenu,{leadMenu=false}){leads.forEach{l->DropdownMenuItem(text={Text(l.name)},onClick={leadId=l.id;leadMenu=false})}}
    }
    OutlinedTextField(title,{title=it.take(180)},label={Text("اسم الفرصة *")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    Box{OutlinedButton(onClick={stageMenu=true},modifier=Modifier.fillMaxWidth()){Text("المرحلة: "+crmOppStageLabel(stage))}
     DropdownMenu(stageMenu,{stageMenu=false}){crmOppStages.filter{it !in setOf("won","lost")}.forEach{s->DropdownMenuItem(text={Text(crmOppStageLabel(s))},onClick={stage=s;stageMenu=false})}}
    }
    OutlinedTextField(probability,{probability=it.filter(Char::isDigit).take(3)},label={Text("احتمال الإغلاق %")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    OutlinedTextField(value,{value=it.filter(Char::isDigit).take(15)},label={Text("القيمة المتوقعة بالوحدة الصغرى")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    OutlinedButton(onClick={chooseCrmCloseDate(context,closeDate){closeDate=it}},modifier=Modifier.fillMaxWidth()){Text(if(closeDate.isBlank())"تاريخ الإغلاق المتوقع (اختياري)" else "تاريخ الإغلاق: $closeDate")}
    OutlinedTextField(notes,{notes=it.take(4000)},label={Text("ملاحظات")},modifier=Modifier.fillMaxWidth(),minLines=2)
    if(localError.isNotBlank())Text(localError,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
   }},
   confirmButton={Button(enabled=!busy&&leadId.isNotBlank()&&title.isNotBlank()&&probability.toIntOrNull()?.let { it in 0..100 } == true&&value.toLongOrNull()!=null,onClick={
    busy=true;localError=""
    crmOppIo.execute{val result=runCatching{createCommercialCrmOpportunity(context,leadId,title,stage,probability.toInt(),value.toLong(),closeDate.ifBlank{null},notes,newCommercialCrmIdempotencyKey())}
     crmOppMain.post{busy=false;result.onSuccess{notice="تم تسجيل فرصة المبيعات.";createOpen=false;refresh++}.onFailure{localError=it.message?:"تعذر حفظ الفرصة."}}
    }
   }){Text("حفظ الفرصة")}},
   dismissButton={TextButton(onClick={createOpen=false},enabled=!busy){Text("إلغاء")}}
  )
 }

 editTarget?.let{opp->
  var stage by remember(opp.id,opp.version){mutableStateOf(opp.stage)}
  var probability by remember(opp.id,opp.version){mutableStateOf(opp.probability.toString())}
  var value by remember(opp.id,opp.version){mutableStateOf(opp.expectedValueMinor.toString())}
  var closeDate by remember(opp.id,opp.version){mutableStateOf(opp.expectedCloseDate)}
  var notes by remember(opp.id,opp.version){mutableStateOf(opp.notes)}
  var lostReason by remember(opp.id,opp.version){mutableStateOf(opp.lostReason)}
  var stageMenu by remember{mutableStateOf(false)};var localError by remember{mutableStateOf("")}
  AlertDialog(onDismissRequest={if(!busy)editTarget=null},title={Text("تحديث فرصة المبيعات")},
   text={Column(verticalArrangement=Arrangement.spacedBy(7.dp)){
    Box{OutlinedButton(onClick={stageMenu=true},modifier=Modifier.fillMaxWidth()){Text("المرحلة: "+crmOppStageLabel(stage))}
     DropdownMenu(stageMenu,{stageMenu=false}){crmOppStages.forEach{s->DropdownMenuItem(text={Text(crmOppStageLabel(s))},onClick={stage=s;stageMenu=false})}}
    }
    OutlinedTextField(probability,{probability=it.filter(Char::isDigit).take(3)},label={Text("احتمال الإغلاق %")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    OutlinedTextField(value,{value=it.filter(Char::isDigit).take(15)},label={Text("القيمة المتوقعة بالوحدة الصغرى")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    OutlinedButton(onClick={chooseCrmCloseDate(context,closeDate){closeDate=it}},modifier=Modifier.fillMaxWidth()){Text(if(closeDate.isBlank())"تاريخ الإغلاق المتوقع" else "تاريخ الإغلاق: $closeDate")}
    OutlinedTextField(notes,{notes=it.take(4000)},label={Text("ملاحظات")},modifier=Modifier.fillMaxWidth(),minLines=2)
    if(stage=="lost")OutlinedTextField(lostReason,{lostReason=it.take(1000)},label={Text("سبب خسارة الفرصة *")},modifier=Modifier.fillMaxWidth(),minLines=2)
    if(localError.isNotBlank())Text(localError,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
   }},
   confirmButton={Button(enabled=!busy,onClick={
    val prob=probability.toIntOrNull();val amount=value.toLongOrNull()
    if(prob==null||prob !in 0..100||amount==null||amount<0)localError="تحقق من الاحتمال والقيمة."
    else if(stage=="lost"&&lostReason.isBlank())localError="اكتب سبب خسارة الفرصة."
    else{
     busy=true;localError=""
     crmOppIo.execute{val result=runCatching{updateCommercialCrmOpportunity(context,opp,stage,prob,amount,closeDate,notes,lostReason)}
      crmOppMain.post{busy=false;result.onSuccess{notice="تم تحديث الفرصة.";editTarget=null;refresh++}.onFailure{localError=it.message?:"تعذر تحديث الفرصة."}}
     }
    }
   }){Text("حفظ التحديث")}},
   dismissButton={TextButton(onClick={editTarget=null},enabled=!busy){Text("إلغاء")}}
  )
 }
}
