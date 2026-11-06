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

private val MaintenanceInk=Color(0xFF183B35)
private val MaintenanceMuted=Color(0xFF71817B)
private val MaintenanceBorder=Color(0xFFE5EBE8)
private val maintenanceIo=Executors.newSingleThreadExecutor()
private val maintenanceMain=Handler(Looper.getMainLooper())
private fun maintenanceToday()=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Calendar.getInstance().time)
private fun maintenanceStatusLabel(s:String)=when(s){"scheduled"->"مجدولة";"in_progress"->"قيد التنفيذ";"completed"->"مكتملة";"cancelled"->"ملغاة";else->s}
private fun maintenanceTypeLabel(s:String)=when(s){"preventive"->"وقائية";"corrective"->"تصحيحية";else->"فحص"}
private fun chooseMaintenanceDate(context:Context,current:String,onSelected:(String)->Unit){
 val c=Calendar.getInstance();runCatching{SimpleDateFormat("yyyy-MM-dd",Locale.US).parse(current)}.getOrNull()?.let{c.time=it}
 DatePickerDialog(context,{_,y,m,d->onSelected(String.format(Locale.US,"%04d-%02d-%02d",y,m+1,d))},
  c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommercialFixedAssetMaintenanceScreen(context:Context,onBack:()->Unit){
 var assets by remember{mutableStateOf<List<CommercialFixedAsset>>(emptyList())}
 var maintenanceRows by remember{mutableStateOf<List<CommercialFixedAssetMaintenance>>(emptyList())}
 var loading by remember{mutableStateOf(true)};var busy by remember{mutableStateOf(false)}
 var error by remember{mutableStateOf("")};var notice by remember{mutableStateOf("")}
 var refresh by remember{mutableIntStateOf(0)};var statusFilter by remember{mutableStateOf("")}
 var filterMenu by remember{mutableStateOf(false)};var createOpen by remember{mutableStateOf(false)}
 var editTarget by remember{mutableStateOf<CommercialFixedAssetMaintenance?>(null)}
 LaunchedEffect(refresh,statusFilter){
  loading=true;error=""
  maintenanceIo.execute{
   val result=runCatching{loadCommercialFixedAssets(context,"active") to loadCommercialFixedAssetMaintenance(context,statusFilter)}
   maintenanceMain.post{loading=false;result.onSuccess{(a,m)->assets=a;maintenanceRows=m}.onFailure{error=it.message?:"تعذر تحميل جدول الصيانة."}}
  }
 }
 Scaffold(containerColor=Color(0xFFF5F7F6),
  topBar={TopAppBar(title={Column{Text("صيانة الأصول",fontWeight=FontWeight.Bold,color=MaintenanceInk);Text("مواعيد الصيانة وسجل التنفيذ",fontSize=11.sp,color=MaintenanceMuted)}},
   navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,contentDescription="رجوع",tint=MaintenanceInk)}},
   actions={IconButton(onClick={refresh++},enabled=!loading&&!busy){Icon(Icons.Default.Refresh,contentDescription="تحديث",tint=MaintenanceInk)}},
   colors=TopAppBarDefaults.topAppBarColors(containerColor=Color(0xFFF5F7F6)))},
  floatingActionButton={FloatingActionButton(onClick={createOpen=true},containerColor=Color(0xFF167D63),contentColor=Color.White){Icon(Icons.Default.Add,contentDescription="جدولة صيانة")}}
 ){padding->
  LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
   item{
    Card(colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,MaintenanceBorder),shape=RoundedCornerShape(16.dp)){
     Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
      Text("${maintenanceRows.size} طلب صيانة في النشاط الحالي",fontWeight=FontWeight.Bold,color=MaintenanceInk)
      Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){
       listOf("scheduled","in_progress","completed","cancelled").forEach{s->
        val n=maintenanceRows.count{it.status==s};if(n>0)AssistChip(onClick={statusFilter=s},label={Text("${maintenanceStatusLabel(s)} $n",fontSize=10.sp)})
       }
      }
      Box{OutlinedButton(onClick={filterMenu=true},modifier=Modifier.fillMaxWidth()){Text("تصفية: "+if(statusFilter.isBlank())"كل الحالات" else maintenanceStatusLabel(statusFilter))}
       DropdownMenu(filterMenu,{filterMenu=false}){
        DropdownMenuItem(text={Text("كل الحالات")},onClick={statusFilter="";filterMenu=false})
        listOf("scheduled","in_progress","completed","cancelled").forEach{s->DropdownMenuItem(text={Text(maintenanceStatusLabel(s))},onClick={statusFilter=s;filterMenu=false})}
       }
      }
      Text("تكلفة الصيانة تُسجل كمعلومة تشغيلية فقط؛ لا ينشأ منها قيد مصروف أو حركة نقدية تلقائياً.",fontSize=11.sp,color=MaintenanceMuted)
     }
    }
   }
   if(loading)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
   if(error.isNotBlank())item{Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)){Column(Modifier.padding(12.dp)){Text(error);TextButton(onClick={refresh++}){Text("إعادة المحاولة")}}}}
   if(notice.isNotBlank())item{Text(notice,color=MaintenanceInk,fontSize=12.sp)}
   if(!loading&&maintenanceRows.isEmpty()&&error.isBlank())item{Text("لا توجد مواعيد صيانة مسجلة.",color=MaintenanceMuted)}
   items(maintenanceRows,key={it.id}){item->
    Card(colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,MaintenanceBorder),shape=RoundedCornerShape(16.dp)){
     Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){
       Column(Modifier.weight(1f)){Text(item.title,fontWeight=FontWeight.Bold,color=MaintenanceInk,fontSize=15.sp);Text(item.assetCode+" • "+item.assetName,fontSize=11.sp,color=MaintenanceMuted)}
       AssistChip(onClick={editTarget=item},label={Text(maintenanceStatusLabel(item.status))})
      }
      Text("النوع: "+maintenanceTypeLabel(item.type)+" • الموعد: "+item.scheduledDate,fontSize=12.sp,color=MaintenanceInk)
      if(item.dueDate.isNotBlank())Text("آخر موعد: "+item.dueDate,fontSize=11.sp,color=MaintenanceMuted)
      if(item.vendorName.isNotBlank())Text("مقدم الخدمة: "+item.vendorName,fontSize=11.sp,color=MaintenanceMuted)
      if(item.status=="completed")Text("اكتملت: "+item.completedAt+" • التكلفة المسجلة: "+String.format(Locale.US,"%,d %s",item.costMinor,item.currencyCode),fontSize=11.sp,color=MaintenanceMuted)
      if(item.description.isNotBlank())Text(item.description,fontSize=12.sp,color=MaintenanceMuted)
      if(item.status!="completed"&&item.status!="cancelled")OutlinedButton(onClick={editTarget=item},modifier=Modifier.fillMaxWidth(),enabled=!busy){Text("تحديث حالة الصيانة")}
     }
    }
   }
  }
 }

 if(createOpen){
  var assetId by remember{mutableStateOf("")};var assetMenu by remember{mutableStateOf(false)}
  var type by remember{mutableStateOf("preventive")};var typeMenu by remember{mutableStateOf(false)}
  var title by remember{mutableStateOf("")};var description by remember{mutableStateOf("")}
  var scheduled by remember{mutableStateOf(maintenanceToday())};var due by remember{mutableStateOf("")}
  var vendor by remember{mutableStateOf("")};var cost by remember{mutableStateOf("0")};var notes by remember{mutableStateOf("")}
  var localError by remember{mutableStateOf("")}
  AlertDialog(onDismissRequest={if(!busy)createOpen=false},title={Text("جدولة صيانة أصل")},
   text={Column(Modifier.fillMaxWidth().heightIn(max=520.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
    Box{OutlinedButton(onClick={assetMenu=true},modifier=Modifier.fillMaxWidth()){Text(assets.firstOrNull{it.id==assetId}?.name?:"اختر الأصل النشط *")}
     DropdownMenu(assetMenu,{assetMenu=false}){assets.forEach{a->DropdownMenuItem(text={Text(a.code+" • "+a.name)},onClick={assetId=a.id;assetMenu=false})}}
    }
    Box{OutlinedButton(onClick={typeMenu=true},modifier=Modifier.fillMaxWidth()){Text("نوع الصيانة: "+maintenanceTypeLabel(type))}
     DropdownMenu(typeMenu,{typeMenu=false}){listOf("preventive","corrective","inspection").forEach{t->DropdownMenuItem(text={Text(maintenanceTypeLabel(t))},onClick={type=t;typeMenu=false})}}
    }
    OutlinedTextField(title,{title=it.take(180)},label={Text("عنوان الصيانة *")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    OutlinedTextField(description,{description=it.take(4000)},label={Text("وصف العمل")},modifier=Modifier.fillMaxWidth(),minLines=2)
    OutlinedButton(onClick={chooseMaintenanceDate(context,scheduled){scheduled=it}},modifier=Modifier.fillMaxWidth()){Text("موعد الصيانة: $scheduled")}
    OutlinedButton(onClick={chooseMaintenanceDate(context,due.ifBlank{scheduled}){due=it}},modifier=Modifier.fillMaxWidth()){Text(if(due.isBlank())"آخر موعد (اختياري)" else "آخر موعد: $due")}
    OutlinedTextField(vendor,{vendor=it.take(180)},label={Text("مقدم الخدمة")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    OutlinedTextField(cost,{cost=it.filter(Char::isDigit).take(16)},label={Text("تكلفة تقديرية بالوحدة الصغرى")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    OutlinedTextField(notes,{notes=it.take(4000)},label={Text("ملاحظات")},modifier=Modifier.fillMaxWidth(),minLines=2)
    if(localError.isNotBlank())Text(localError,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
   }},
   confirmButton={Button(enabled=!busy&&assetId.isNotBlank()&&title.isNotBlank()&&cost.toLongOrNull()!=null,onClick={
    val amount=cost.toLongOrNull()?:0
    if(amount<0)localError="التكلفة لا يمكن أن تكون سالبة."
    else if(due.isNotBlank()&&due<scheduled)localError="آخر موعد لا يمكن أن يسبق موعد الصيانة."
    else{
     busy=true;localError=""
     maintenanceIo.execute{val r=runCatching{createCommercialFixedAssetMaintenance(context,assetId,type,title,description,scheduled,due.ifBlank{null},vendor,amount,notes,newCommercialAssetMaintenanceIdempotencyKey())}
      maintenanceMain.post{busy=false;r.onSuccess{notice="تمت جدولة الصيانة.";createOpen=false;refresh++}.onFailure{localError=it.message?:"تعذر إنشاء طلب الصيانة."}}
     }
    }
   }){Text("حفظ الموعد")}},
   dismissButton={TextButton(onClick={createOpen=false},enabled=!busy){Text("إلغاء")}}
  )
 }

 editTarget?.let{item->
  var status by remember(item.id,item.version){mutableStateOf(item.status)}
  var completed by remember(item.id,item.version){mutableStateOf(maintenanceToday())}
  var vendor by remember(item.id,item.version){mutableStateOf(item.vendorName)}
  var cost by remember(item.id,item.version){mutableStateOf(item.costMinor.toString())}
  var notes by remember(item.id,item.version){mutableStateOf(item.notes)}
  var statusMenu by remember{mutableStateOf(false)};var localError by remember{mutableStateOf("")}
  AlertDialog(onDismissRequest={if(!busy)editTarget=null},title={Text("تحديث الصيانة: "+item.title)},
   text={Column(verticalArrangement=Arrangement.spacedBy(7.dp)){
    Box{OutlinedButton(onClick={statusMenu=true},modifier=Modifier.fillMaxWidth()){Text("الحالة: "+maintenanceStatusLabel(status))}
     DropdownMenu(statusMenu,{statusMenu=false}){listOf("scheduled","in_progress","completed","cancelled").forEach{s->DropdownMenuItem(text={Text(maintenanceStatusLabel(s))},onClick={status=s;statusMenu=false})}}
    }
    if(status=="completed")OutlinedButton(onClick={chooseMaintenanceDate(context,completed){completed=it}},modifier=Modifier.fillMaxWidth()){Text("تاريخ الإكمال: $completed")}
    OutlinedTextField(vendor,{vendor=it.take(180)},label={Text("مقدم الخدمة")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    OutlinedTextField(cost,{cost=it.filter(Char::isDigit).take(16)},label={Text("التكلفة الفعلية بالوحدة الصغرى")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    OutlinedTextField(notes,{notes=it.take(4000)},label={Text("ملاحظات")},modifier=Modifier.fillMaxWidth(),minLines=2)
    if(localError.isNotBlank())Text(localError,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
   }},
   confirmButton={Button(enabled=!busy,onClick={
    val amount=cost.toLongOrNull()
    if(amount==null||amount<0)localError="تحقق من التكلفة."
    else if(status=="completed"&&completed<item.scheduledDate)localError="تاريخ الإكمال لا يمكن أن يسبق الموعد."
    else{
     busy=true;localError=""
     maintenanceIo.execute{val r=runCatching{updateCommercialFixedAssetMaintenance(context,item,status,if(status=="completed")completed else null,vendor,amount,notes)}
      maintenanceMain.post{busy=false;r.onSuccess{notice="تم تحديث سجل الصيانة.";editTarget=null;refresh++}.onFailure{localError=it.message?:"تعذر تحديث الصيانة."}}
     }
    }
   }){Text("حفظ التحديث")}},
   dismissButton={TextButton(onClick={editTarget=null},enabled=!busy){Text("إلغاء")}}
  )
 }
}
