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
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

private val AssetInk=Color(0xFF183B35)
private val AssetMuted=Color(0xFF71817B)
private val AssetBorder=Color(0xFFE5EBE8)
private val assetIo=Executors.newSingleThreadExecutor()
private val assetMain=Handler(Looper.getMainLooper())
private fun assetStatusLabel(s:String)=when(s){"draft"->"مسودة";"active"->"نشط";"disposed"->"مستبعد";else->s}
private fun assetMethodLabel(s:String)=if(s=="double_declining")"القسط المتناقص المزدوج" else "القسط الثابت"
private fun chooseAssetDate(context:Context,current:String,onSelected:(String)->Unit){
 val c=Calendar.getInstance()
 runCatching{SimpleDateFormat("yyyy-MM-dd",Locale.US).parse(current)}.getOrNull()?.let{c.time=it}
 DatePickerDialog(context,{_,y,m,d->onSelected(String.format(Locale.US,"%04d-%02d-%02d",y,m+1,d))},
  c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommercialFixedAssetsScreen(context:Context,onBack:()->Unit){
 var assets by remember{mutableStateOf<List<CommercialFixedAsset>>(emptyList())}
 var loading by remember{mutableStateOf(true)}
 var busy by remember{mutableStateOf(false)}
 var error by remember{mutableStateOf("")}
 var notice by remember{mutableStateOf("")}
 var refresh by remember{mutableIntStateOf(0)}
 var statusFilter by remember{mutableStateOf("")}
 var filterMenu by remember{mutableStateOf(false)}
 var createOpen by remember{mutableStateOf(false)}
 var updateTarget by remember{mutableStateOf<CommercialFixedAsset?>(null)}
 var preview by remember{mutableStateOf<JSONObject?>(null)}
 var previewTitle by remember{mutableStateOf("")}

 LaunchedEffect(refresh,statusFilter){
  loading=true;error=""
  assetIo.execute{
   val result=runCatching{loadCommercialFixedAssets(context,statusFilter)}
   assetMain.post{loading=false;result.onSuccess{assets=it}.onFailure{error=it.message?:"تعذر تحميل سجل الأصول."}}
  }
 }
 Scaffold(containerColor=Color(0xFFF5F7F6),
  topBar={TopAppBar(title={Column{Text("سجل الأصول الثابتة",fontWeight=FontWeight.Bold,color=AssetInk);Text("تسجيل الأصول ومراجعة الإهلاك التقديري",fontSize=11.sp,color=AssetMuted)}},
   navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,contentDescription="رجوع",tint=AssetInk)}},
   actions={IconButton(onClick={refresh++},enabled=!loading&&!busy){Icon(Icons.Default.Refresh,contentDescription="تحديث",tint=AssetInk)}},
   colors=TopAppBarDefaults.topAppBarColors(containerColor=Color(0xFFF5F7F6)))},
  floatingActionButton={FloatingActionButton(onClick={createOpen=true},containerColor=Color(0xFF167D63),contentColor=Color.White){Icon(Icons.Default.Add,contentDescription="إضافة أصل")}}
 ){padding->
  LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
   item{
    Card(colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,AssetBorder),shape=RoundedCornerShape(16.dp)){
     Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
      Text("${assets.size} أصل في النشاط الحالي",fontWeight=FontWeight.Bold,color=AssetInk)
      Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){
       listOf("draft" to "مسودة","active" to "نشط","disposed" to "مستبعد").forEach{(key,label)->
        val count=assets.count{it.status==key}
        if(count>0)AssistChip(onClick={statusFilter=key},label={Text("$label $count",fontSize=10.sp)})
       }
      }
      Box{
       OutlinedButton(onClick={filterMenu=true},modifier=Modifier.fillMaxWidth()){Text("تصفية: "+if(statusFilter.isBlank())"كل الأصول" else assetStatusLabel(statusFilter))}
       DropdownMenu(filterMenu,{filterMenu=false}){
        DropdownMenuItem(text={Text("كل الأصول")},onClick={statusFilter="";filterMenu=false})
        listOf("draft","active","disposed").forEach{s->DropdownMenuItem(text={Text(assetStatusLabel(s))},onClick={statusFilter=s;filterMenu=false})}
       }
      }
      Text("هذا السجل لا ينشئ قيد شراء أو قيد إهلاك أو حركة مخزون. معاينة الإهلاك تقديرية فقط حتى ربطها بدورة المحاسبة.",fontSize=11.sp,color=AssetMuted)
     }
    }
   }
   if(loading)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
   if(error.isNotBlank())item{Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)){Column(Modifier.padding(12.dp)){Text(error);TextButton(onClick={refresh++}){Text("إعادة المحاولة")}}}}
   if(notice.isNotBlank())item{Text(notice,color=AssetInk,fontSize=12.sp)}
   if(!loading&&assets.isEmpty()&&error.isBlank())item{Text("لا توجد أصول مسجلة. أضف الأصل الأول للبدء.",color=AssetMuted)}
   items(assets,key={it.id}){asset->
    Card(colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,AssetBorder),shape=RoundedCornerShape(16.dp)){
     Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){
       Column(Modifier.weight(1f)){Text(asset.name,fontWeight=FontWeight.Bold,color=AssetInk,fontSize=16.sp);Text(asset.code+" • "+asset.category,fontSize=11.sp,color=AssetMuted)}
       AssistChip(onClick={updateTarget=asset},label={Text(assetStatusLabel(asset.status))})
      }
      Text("التكلفة: "+String.format(Locale.US,"%,d %s",asset.acquisitionCostMinor,asset.currencyCode),fontWeight=FontWeight.SemiBold,color=AssetInk,fontSize=13.sp)
      Text("القيمة المتبقية: "+String.format(Locale.US,"%,d %s",asset.salvageValueMinor,asset.currencyCode)+" • العمر: ${asset.usefulLifeMonths} شهر",fontSize=11.sp,color=AssetMuted)
      Text("طريقة الإهلاك: "+assetMethodLabel(asset.depreciationMethod),fontSize=11.sp,color=AssetMuted)
      if(asset.locationName.isNotBlank())Text("الموقع: "+asset.locationName,fontSize=11.sp,color=AssetMuted)
      if(asset.serialNumber.isNotBlank())Text("الرقم التسلسلي: "+asset.serialNumber,fontSize=11.sp,color=AssetMuted)
      Row(horizontalArrangement=Arrangement.spacedBy(7.dp),modifier=Modifier.fillMaxWidth()){
       if(asset.status=="active")OutlinedButton(onClick={
        busy=true
        assetIo.execute{val r=runCatching{previewCommercialFixedAssetDepreciation(context,asset.id)}
         assetMain.post{busy=false;r.onSuccess{preview=it;previewTitle=asset.name}.onFailure{error=it.message?:"تعذر حساب معاينة الإهلاك."}}
        }
       },modifier=Modifier.weight(1f),enabled=!busy){Text("معاينة الإهلاك")}
       OutlinedButton(onClick={updateTarget=asset},modifier=Modifier.weight(1f),enabled=!busy){Text(if(asset.status=="draft")"تفعيل الأصل" else if(asset.status=="active")"إدارة الأصل" else "التفاصيل")}
      }
     }
    }
   }
  }
 }

 if(createOpen){
  var code by remember{mutableStateOf("")};var name by remember{mutableStateOf("")}
  var category by remember{mutableStateOf("")};var serial by remember{mutableStateOf("")}
  var description by remember{mutableStateOf("")};var acquisition by remember{mutableStateOf("2026-01-01")}
  var available by remember{mutableStateOf("2026-01-01")};var cost by remember{mutableStateOf("")}
  var salvage by remember{mutableStateOf("0")};var life by remember{mutableStateOf("60")}
  var method by remember{mutableStateOf("straight_line")};var methodMenu by remember{mutableStateOf(false)}
  var notes by remember{mutableStateOf("")};var localError by remember{mutableStateOf("")}
  AlertDialog(onDismissRequest={if(!busy)createOpen=false},title={Text("تسجيل أصل ثابت")},
   text={Column(Modifier.fillMaxWidth().heightIn(max=540.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
    OutlinedTextField(code,{code=it.take(64)},label={Text("رمز الأصل *")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    OutlinedTextField(name,{name=it.take(180)},label={Text("اسم الأصل *")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    OutlinedTextField(category,{category=it.take(100)},label={Text("الفئة *")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    OutlinedTextField(serial,{serial=it.take(120)},label={Text("الرقم التسلسلي")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    OutlinedTextField(description,{description=it.take(4000)},label={Text("الوصف")},modifier=Modifier.fillMaxWidth(),minLines=2)
    OutlinedButton(onClick={chooseAssetDate(context,acquisition){acquisition=it}},modifier=Modifier.fillMaxWidth()){Text("تاريخ الشراء: $acquisition")}
    OutlinedButton(onClick={chooseAssetDate(context,available){available=it}},modifier=Modifier.fillMaxWidth()){Text("تاريخ بدء الاستخدام: $available")}
    OutlinedTextField(cost,{cost=it.filter(Char::isDigit).take(16)},label={Text("تكلفة الاقتناء بالوحدة الصغرى *")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    OutlinedTextField(salvage,{salvage=it.filter(Char::isDigit).take(16)},label={Text("القيمة المتبقية بالوحدة الصغرى")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    OutlinedTextField(life,{life=it.filter(Char::isDigit).take(3)},label={Text("العمر الإنتاجي بالأشهر (1-600)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    Box{OutlinedButton(onClick={methodMenu=true},modifier=Modifier.fillMaxWidth()){Text("طريقة الإهلاك: "+assetMethodLabel(method))}
     DropdownMenu(methodMenu,{methodMenu=false}){listOf("straight_line","double_declining").forEach{m->DropdownMenuItem(text={Text(assetMethodLabel(m))},onClick={method=m;methodMenu=false})}}
    }
    OutlinedTextField(notes,{notes=it.take(4000)},label={Text("ملاحظات")},modifier=Modifier.fillMaxWidth(),minLines=2)
    if(localError.isNotBlank())Text(localError,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
   }},
   confirmButton={Button(enabled=!busy&&code.isNotBlank()&&name.isNotBlank()&&category.isNotBlank()&&cost.toLongOrNull()!=null&&life.toIntOrNull()!=null,onClick={
    val amount=cost.toLongOrNull()?:0;val salvageAmount=salvage.toLongOrNull()?:0;val months=life.toIntOrNull()?:0
    if(amount<=0||salvageAmount<0||salvageAmount>=amount||months !in 1..600)localError="تحقق من التكلفة والقيمة المتبقية والعمر الإنتاجي."
    else if(acquisition>available)localError="تاريخ بدء الاستخدام يجب ألا يسبق تاريخ الشراء."
    else{
     busy=true;localError=""
     assetIo.execute{val r=runCatching{createCommercialFixedAsset(context,code,name,category,serial,description,acquisition,available,amount,salvageAmount,months,method,notes,newCommercialFixedAssetIdempotencyKey())}
      assetMain.post{busy=false;r.onSuccess{notice="تم تسجيل الأصل كمسودة. فعّله بعد مراجعة بياناته.";createOpen=false;refresh++}.onFailure{localError=it.message?:"تعذر حفظ الأصل."}}
     }
    }
   }){Text("حفظ المسودة")}},
   dismissButton={TextButton(onClick={createOpen=false},enabled=!busy){Text("إلغاء")}}
  )
 }

 updateTarget?.let{asset->
  var notes by remember(asset.id,asset.version){mutableStateOf(asset.notes)}
  var disposalDate by remember(asset.id,asset.version){mutableStateOf("2026-11-06")}
  var proceeds by remember(asset.id,asset.version){mutableStateOf("")}
  var localError by remember{mutableStateOf("")}
  AlertDialog(onDismissRequest={if(!busy)updateTarget=null},title={Text(if(asset.status=="draft")"تفعيل الأصل" else if(asset.status=="active")"إدارة الأصل" else "تفاصيل الأصل")},
   text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
    Text(asset.name+" • "+asset.code,fontWeight=FontWeight.SemiBold,color=AssetInk)
    OutlinedTextField(notes,{notes=it.take(4000)},label={Text("ملاحظات")},modifier=Modifier.fillMaxWidth(),minLines=2)
    if(asset.status=="active"){
     OutlinedButton(onClick={chooseAssetDate(context,disposalDate){disposalDate=it}},modifier=Modifier.fillMaxWidth()){Text("تاريخ الاستبعاد: $disposalDate")}
     OutlinedTextField(proceeds,{proceeds=it.filter(Char::isDigit).take(16)},label={Text("متحصلات الاستبعاد بالوحدة الصغرى")},modifier=Modifier.fillMaxWidth(),singleLine=true)
     Text("الاستبعاد هنا يحدّث سجل الأصل فقط، ولا ينشئ قيد ربح أو خسارة أو حركة نقدية.",fontSize=11.sp,color=AssetMuted)
    }
    if(localError.isNotBlank())Text(localError,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
   }},
   confirmButton={if(asset.status!="disposed")Button(enabled=!busy,onClick={
    val nextStatus=if(asset.status=="draft")"active" else "disposed"
    val amount=proceeds.toLongOrNull()
    if(nextStatus=="disposed"&&(amount==null||amount<0))localError="أدخل متحصلات الاستبعاد."
    else if(nextStatus=="disposed"&&disposalDate<asset.availableForUseDate)localError="تاريخ الاستبعاد لا يمكن أن يسبق بدء الاستخدام."
    else{
     busy=true;localError=""
     assetIo.execute{val r=runCatching{updateCommercialFixedAsset(context,asset,nextStatus,notes,if(nextStatus=="disposed")disposalDate else null,if(nextStatus=="disposed")amount else null)}
      assetMain.post{busy=false;r.onSuccess{notice=if(nextStatus=="active")"تم تفعيل الأصل." else "تم استبعاد الأصل من السجل.";updateTarget=null;refresh++}.onFailure{localError=it.message?:"تعذر تحديث الأصل."}}
     }
    }
   }){Text(if(asset.status=="draft")"تفعيل الأصل" else "تأكيد الاستبعاد")}},
   dismissButton={TextButton(onClick={updateTarget=null},enabled=!busy){Text("إغلاق")}}
  )
 }

 preview?.let{data->
  val periods=data.optJSONArray("periods")?:org.json.JSONArray()
  AlertDialog(onDismissRequest={preview=null},title={Text("معاينة إهلاك: $previewTitle")},
   text={Column(Modifier.fillMaxWidth().heightIn(max=440.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
    Text("إجمالي الإهلاك التقديري: "+String.format(Locale.US,"%,d %s",data.optLong("total_preview_depreciation_minor"),data.optString("currency_code")),fontWeight=FontWeight.Bold,color=AssetInk)
    Text("القيمة الدفترية بعد المعاينة: "+String.format(Locale.US,"%,d %s",data.optLong("closing_book_value_minor"),data.optString("currency_code")),fontSize=12.sp,color=AssetMuted)
    LazyColumn(verticalArrangement=Arrangement.spacedBy(5.dp)){
     items(periods.length()){i->
      val row=periods.optJSONObject(i)?:JSONObject()
      Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFF7FAF8)),border=BorderStroke(1.dp,AssetBorder)){
       Column(Modifier.fillMaxWidth().padding(9.dp)){
        Text(row.optString("period"),fontWeight=FontWeight.SemiBold,color=AssetInk)
        Text("إهلاك: "+String.format(Locale.US,"%,d",row.optLong("depreciation_minor"))+" • الإقفال: "+String.format(Locale.US,"%,d",row.optLong("closing_book_value_minor")),fontSize=11.sp,color=AssetMuted)
       }
      }
     }
    }
   }},
   confirmButton={TextButton(onClick={preview=null}){Text("إغلاق")}}
  )
 }
}
