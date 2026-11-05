package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors

private val adsIo = Executors.newSingleThreadExecutor()
private val adsMain = Handler(Looper.getMainLooper())
private fun adsHttp(path: String, payload: JSONObject, token: String? = null): String =
 SupabaseClient.request(
  path = path,
  method = "POST",
  payload = payload,
  token = token,
  readTimeoutMs = 20000,
  wrapNetworkErrors = false
 )
private fun adsToken(context: Context): String {
 val saved=commercialCloudSessionPreferences(context).getString("refresh_token",null).orEmpty()
 require(saved.isNotBlank()){"سجّل الدخول إلى حساب المنصة أولًا."}
 val session=JSONObject(adsHttp("/auth/v1/token?grant_type=refresh_token",JSONObject().put("refresh_token",saved)))
 val access=session.optString("access_token")
 require(access.isNotBlank()){"انتهت جلسة الدخول؛ سجّل الدخول مجددًا."}
 session.optString("refresh_token").takeIf{it.isNotBlank()}?.let{commercialCloudSessionPreferences(context).edit().putString("refresh_token",it).apply()}
 return access
}
internal data class AdCampaignRow(val id:String,val title:String,val product:String,val description:String,val reason:String,val original:String,val offer:String,val currency:String,val starts:String,val ends:String,val audience:String,val gender:String,val radius:String,val status:String)


internal fun loadCommercialAdsHomeFeed(context: Context, onResult: (Result<List<AdCampaignRow>>) -> Unit) {
 val cloud=context.getSharedPreferences("commercial_cloud_context_v1",Context.MODE_PRIVATE)
 val orgId=cloud.getString("organization_id",null).orEmpty()
 val activityCode=cloud.getString("activity_code",null).orEmpty()
 if(orgId.isBlank()){ onResult(Result.success(emptyList())); return }
 adsIo.execute {
  val result=runCatching {
   val token=adsToken(context)
   val raw=adsHttp("/rest/v1/rpc/commercial_ads_feed",JSONObject()
    .put("p_audience_type","trader")
    .put("p_activity_code",activityCode.ifBlank{JSONObject.NULL})
    .put("p_limit",5),token)
   val array=JSONArray(raw)
   buildList {
    for(i in 0 until array.length()) {
     val x=array.optJSONObject(i)?:continue
     add(AdCampaignRow(x.optString("id"),x.optString("title"),x.optString("product_name"),
      x.optString("description"),x.optString("discount_reason"),
      x.optString("original_price_minor").takeUnless{it=="null"}.orEmpty(),
      x.optString("offer_price_minor").takeUnless{it=="null"}.orEmpty(),
      x.optString("currency_code"),x.optString("starts_at"),x.optString("ends_at"),
      x.optString("audience_type"),x.optString("gender_target"),
      x.optString("radius_km").takeUnless{it=="null"}.orEmpty(),"published"))
    }
   }
  }
  adsMain.post { onResult(result) }
 }
}

@Composable
internal fun CommercialAdsHomePreview(context: Context, onOpenAds: () -> Unit) {
 var rows by remember { mutableStateOf<List<AdCampaignRow>>(emptyList()) }
 var failed by remember { mutableStateOf(false) }
 val cloud=context.getSharedPreferences("commercial_cloud_context_v1",Context.MODE_PRIVATE)
 val orgId=cloud.getString("organization_id",null).orEmpty()
 val activityCode=cloud.getString("activity_code",null).orEmpty()
 LaunchedEffect(orgId,activityCode) {
  if(orgId.isNotBlank()) loadCommercialAdsHomeFeed(context) { result ->
   result.onSuccess { rows=it }.onFailure { failed=true }
  }
 }
 if(rows.isNotEmpty()) {
  Card(Modifier.fillMaxWidth()) {
   Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
     Text("عروض وإعلانات المتاجر",Modifier.weight(1f),fontWeight=FontWeight.Bold)
     TextButton(onClick=onOpenAds){Text("إدارة الإعلانات")}
    }
    rows.forEachIndexed { index, ad ->
     Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
      Text(ad.title,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.primary)
      Text(ad.product,style=MaterialTheme.typography.bodyMedium)
      if(ad.description.isNotBlank()) Text(ad.description,style=MaterialTheme.typography.bodySmall,maxLines=2)
      if(ad.reason.isNotBlank()) Text("سبب العرض: "+ad.reason,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
     }
     if(index<rows.lastIndex) HorizontalDivider()
    }
   }
  }
 } else if(failed) {
  TextButton(onClick={ loadCommercialAdsHomeFeed(context) { result -> result.onSuccess { rows = it; failed = false }.onFailure { failed = true } } }) { Text("تعذر تحميل العروض — إعادة المحاولة") }
 }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommercialAdsScreen(context: Context, onBack: () -> Unit) {
 val cloud=context.getSharedPreferences("commercial_cloud_context_v1",Context.MODE_PRIVATE)
 val orgId=cloud.getString("organization_id",null).orEmpty()
 val locationId=cloud.getString("location_id",null).orEmpty()
 val activityId=cloud.getString("activity_id",null).orEmpty()
 var rows by remember{mutableStateOf<List<AdCampaignRow>>(emptyList())}
 var busy by remember{mutableStateOf(false)}
 var error by remember{mutableStateOf("")}
 var notice by remember{mutableStateOf("")}
 var editor by remember{mutableStateOf(false)}
 var title by remember{mutableStateOf("")}
 var product by remember{mutableStateOf("")}
 var description by remember{mutableStateOf("")}
 var reason by remember{mutableStateOf("")}
 var original by remember{mutableStateOf("")}
 var offer by remember{mutableStateOf("")}
 var starts by remember{mutableStateOf(Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())}
 var ends by remember{mutableStateOf(Instant.now().plus(7,ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString())}
 var audience by remember{mutableStateOf("all")}
 var gender by remember{mutableStateOf("any")}
 var minAge by remember{mutableStateOf("")}
 var maxAge by remember{mutableStateOf("")}
 var radius by remember{mutableStateOf("")}
 var validation by remember{mutableStateOf("")}

 fun execute(action:(String)->String, success:(String)->Unit={}) {
  if(busy)return
  busy=true;error="";notice=""
  adsIo.execute {
   val result=runCatching{action(adsToken(context))}
   adsMain.post { busy=false; result.onSuccess(success).onFailure{error=it.message?:"تعذر إكمال العملية."} }
  }
 }
 fun refresh() {
  if(orgId.isBlank()){error="اختر مؤسسة سحابية أولًا.";return}
  execute({token->adsHttp("/rest/v1/rpc/commercial_ads_list_mine",JSONObject().put("p_organization_id",orgId).put("p_limit",100),token)}) { raw ->
   val a=JSONArray(raw)
   rows=buildList {
    for(i in 0 until a.length()){
     val x=a.optJSONObject(i)?:continue
     add(AdCampaignRow(x.optString("id"),x.optString("title"),x.optString("product_name"),x.optString("description"),x.optString("discount_reason"),x.optString("original_price_minor").takeUnless{it=="null"}.orEmpty(),x.optString("offer_price_minor").takeUnless{it=="null"}.orEmpty(),x.optString("currency_code"),x.optString("starts_at"),x.optString("ends_at"),x.optString("audience_type"),x.optString("gender_target"),x.optString("radius_km").takeUnless{it=="null"}.orEmpty(),x.optString("status")))
    }
   }
   if(rows.isEmpty())notice="لا توجد إعلانات لهذا المتجر بعد."
  }
 }
 LaunchedEffect(Unit){refresh()}
 Scaffold(topBar={TopAppBar(title={Text("الإعلانات والعروض",fontWeight=FontWeight.Bold)},navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,contentDescription="رجوع")}},actions={IconButton(enabled=!busy,onClick={::refresh}){Icon(Icons.Default.Refresh,contentDescription="تحديث")}})},
 floatingActionButton={ExtendedFloatingActionButton(onClick={editor=true},icon={Icon(Icons.Default.Campaign,null)},text={Text("إنشاء إعلان")})}) { padding ->
  LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
   if(busy)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
   if(error.isNotBlank())item{Text(error,color=MaterialTheme.colorScheme.error)}
   if(notice.isNotBlank())item{Text(notice,color=MaterialTheme.colorScheme.onSurfaceVariant)}
   item{Text("الإعلان مرتبط بفرع المتجر المحدد. النطاق الجغرافي يعتمد على إحداثيات الفرع المحفوظة.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
   items(rows,key={it.id}){ad->
    Card(Modifier.fillMaxWidth()){
     Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
      Row { Text(ad.title,Modifier.weight(1f),fontWeight=FontWeight.Bold); Text(when(ad.status){"published"->"منشور";"paused"->"متوقف";"expired"->"منتهي";else->"مسودة"},color=MaterialTheme.colorScheme.primary) }
      Text("المنتج: "+ad.product)
      if(ad.description.isNotBlank())Text(ad.description)
      if(ad.reason.isNotBlank())Text("سبب التخفيض: "+ad.reason,style=MaterialTheme.typography.bodySmall)
      if(ad.original.isNotBlank()||ad.offer.isNotBlank())Text("السعر: "+ad.original+" ← "+ad.offer+" "+ad.currency)
      Text("الجمهور: "+when(ad.audience){"consumer"->"مستهلكون";"trader"->"تجار";"customer"->"عملاء";else->"الكل"}+" • الجنس: "+when(ad.gender){"male"->"ذكور";"female"->"إناث";else->"الكل"})
      Text("الفترة: "+ad.starts+" إلى "+ad.ends,style=MaterialTheme.typography.bodySmall)
      if(ad.radius.isNotBlank())Text("النطاق: "+ad.radius+" كم",style=MaterialTheme.typography.bodySmall)
      if(ad.status=="published")TextButton(enabled=!busy,onClick={execute({token->adsHttp("/rest/v1/rpc/commercial_ads_set_status",JSONObject().put("p_ad_id",ad.id).put("p_status","paused"),token)}){notice="تم إيقاف الإعلان.";refresh()}}){Icon(Icons.Default.PauseCircle,null);Text("إيقاف")}
      else if(ad.status!="expired")TextButton(enabled=!busy,onClick={execute({token->adsHttp("/rest/v1/rpc/commercial_ads_set_status",JSONObject().put("p_ad_id",ad.id).put("p_status","published"),token)}){notice="تم نشر الإعلان.";refresh()}}){Icon(Icons.Default.PlayCircle,null);Text("نشر")}
     }
    }
   }
  }
 }
 if(editor)AlertDialog(onDismissRequest={editor=false},title={Text("إنشاء إعلان أو عرض")},text={
  Column(Modifier.heightIn(max=560.dp).widthIn(max=520.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
   OutlinedTextField(title,{title=it.take(160)},label={Text("عنوان الإعلان")},singleLine=true,modifier=Modifier.fillMaxWidth())
   OutlinedTextField(product,{product=it.take(160)},label={Text("اسم المنتج أو العرض")},singleLine=true,modifier=Modifier.fillMaxWidth())
   OutlinedTextField(description,{description=it.take(2000)},label={Text("وصف العرض")},modifier=Modifier.fillMaxWidth())
   OutlinedTextField(reason,{reason=it.take(500)},label={Text("سبب التخفيض")},modifier=Modifier.fillMaxWidth())
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
    OutlinedTextField(original,{original=it.filter(Char::isDigit).take(15)},label={Text("السعر قبل")},modifier=Modifier.weight(1f),singleLine=true)
    OutlinedTextField(offer,{offer=it.filter(Char::isDigit).take(15)},label={Text("سعر العرض")},modifier=Modifier.weight(1f),singleLine=true)
   }
   Text("الجمهور المستهدف",fontWeight=FontWeight.Bold)
   Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf("all" to "الكل","consumer" to "مستهلكون","trader" to "تجار","customer" to "عملاء").forEach{(v,l)->FilterChip(selected=audience==v,onClick={audience=v},label={Text(l)})}}
   Text("الجنس",fontWeight=FontWeight.Bold)
   Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf("any" to "الكل","male" to "ذكور","female" to "إناث").forEach{(v,l)->FilterChip(selected=gender==v,onClick={gender=v},label={Text(l)})}}
   Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
    OutlinedTextField(minAge,{minAge=it.filter(Char::isDigit).take(3)},label={Text("من عمر")},modifier=Modifier.weight(1f),singleLine=true)
    OutlinedTextField(maxAge,{maxAge=it.filter(Char::isDigit).take(3)},label={Text("إلى عمر")},modifier=Modifier.weight(1f),singleLine=true)
    OutlinedTextField(radius,{radius=it.filter{ch->ch.isDigit()||ch=='.'}.take(6)},label={Text("نطاق كم")},modifier=Modifier.weight(1f),singleLine=true)
   }
   OutlinedTextField(starts,{starts=it.take(40)},label={Text("بداية الإعلان ISO-8601")},modifier=Modifier.fillMaxWidth(),singleLine=true)
   OutlinedTextField(ends,{ends=it.take(40)},label={Text("نهاية الإعلان ISO-8601")},modifier=Modifier.fillMaxWidth(),singleLine=true)
   Text("أدخل إحداثيات الفرع في إعداداته لتفعيل تحديد النطاق الجغرافي.",style=MaterialTheme.typography.bodySmall)
   if(validation.isNotBlank())Text(validation,color=MaterialTheme.colorScheme.error)
  }
 },confirmButton={Button(enabled=!busy,onClick={
  val o=original.toLongOrNull();val f=offer.toLongOrNull();val lo=minAge.toIntOrNull();val hi=maxAge.toIntOrNull();val rad=radius.toBigDecimalOrNull()
  validation=when{
   orgId.isBlank()||locationId.isBlank()->"اختر المؤسسة والفرع السحابي أولًا."
   title.trim().length<2||product.trim().isBlank()->"أدخل عنوان الإعلان واسم المنتج."
   runCatching{Instant.parse(starts)}.isFailure||runCatching{Instant.parse(ends)}.isFailure||runCatching{Instant.parse(ends).isAfter(Instant.parse(starts))}.getOrDefault(false).not()->"صيغة التاريخ أو الفترة غير صحيحة."
   o!=null&&f!=null&&f>o->"سعر العرض لا يجوز أن يتجاوز السعر السابق."
   lo!=null&&hi!=null&&lo>hi->"الحد الأدنى للعمر أكبر من الحد الأعلى."
   rad!=null&&rad.toDouble() !in 0.1..500.0->"النطاق الجغرافي يجب أن يكون بين 0.1 و500 كم."
   else->""
  }
  if(validation.isBlank()){
   val payload=JSONObject().put("p_organization_id",orgId).put("p_location_id",locationId)
    .put("p_title",title.trim()).put("p_product_name",product.trim()).put("p_description",description.trim()).put("p_discount_reason",reason.trim())
    .put("p_original_price_minor",o?:JSONObject.NULL).put("p_offer_price_minor",f?:JSONObject.NULL).put("p_currency_code","YER")
    .put("p_starts_at",starts).put("p_ends_at",ends).put("p_audience_type",audience).put("p_activity_code",activityId.ifBlank{JSONObject.NULL})
    .put("p_gender_target",gender).put("p_min_age",lo?:JSONObject.NULL).put("p_max_age",hi?:JSONObject.NULL).put("p_radius_km",rad?:JSONObject.NULL)
   execute({token->adsHttp("/rest/v1/rpc/commercial_ads_create",payload,token)}){editor=false;notice="تم إنشاء الإعلان.";title="";product="";description="";reason="";original="";offer="";refresh()}
  }
 }){Text("إنشاء الإعلان")}},dismissButton={TextButton(onClick={editor=false}){Text("إلغاء")}})
}
