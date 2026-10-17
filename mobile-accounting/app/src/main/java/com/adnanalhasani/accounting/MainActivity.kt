package com.adnanalhasani.accounting

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.OutputStreamWriter
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("accounting", MODE_PRIVATE) }
    private val localDb by lazy { LocalDatabase(this) }
    private var token: String? = null
    private var apiUrl = ""
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = prefs.getString("token", null)
        apiUrl = prefs.getString("api_url", "") ?: ""
        if (token == null && !localDb.hasLocalData()) login() else dashboard()
    }
    private fun label(s:String,size:Float=16f,bold:Boolean=false):TextView = TextView(this).apply {
        text=s;textSize=size;setTextColor(Color.rgb(25,55,42));gravity=Gravity.RIGHT
        if(bold)setTypeface(null,android.graphics.Typeface.BOLD)
        setPadding(0,10,0,10);textDirection=View.TEXT_DIRECTION_RTL
    }
    private fun field(h:String,v:String="")=EditText(this).apply { hint=h;setText(v);gravity=Gravity.RIGHT;textDirection=View.TEXT_DIRECTION_RTL }
    private fun btn(s:String,f:()->Unit)=Button(this).apply { text=s;setOnClickListener{f()} }
    private fun screen(title:String) {
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(22,22,22,22);layoutDirection=View.LAYOUT_DIRECTION_RTL;setBackgroundColor(Color.rgb(246,248,246)) }
        root.addView(label(title,25f,true))
        val scroll=ScrollView(this)
        body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;layoutDirection=View.LAYOUT_DIRECTION_RTL }
        scroll.addView(body);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        status=label("");root.addView(status);setContentView(root)
    }
    private fun login() {
        screen("حسابات المستودع")
        body.addView(label("إدارة الحسابات والفواتير والحركات المالية"))
        val url=field("رابط الخادم HTTPS",apiUrl);val user=field("اسم المستخدم","admin");val pass=field("كلمة المرور")
        pass.inputType=129
        body.addView(label("رابط الخادم"));body.addView(url);body.addView(label("اسم المستخدم"));body.addView(user);body.addView(label("كلمة المرور"));body.addView(pass)
        body.addView(btn("تجربة البرنامج") { demoMode() })
        body.addView(label("استكشف واجهة تجريبية دون حساب أو اتصال بالخادم. البيانات المعروضة أمثلة فقط."))
        body.addView(btn("تسجيل الدخول") {
            apiUrl=url.text.toString().trim().trimEnd('/')
            if(!apiUrl.startsWith("https://")){status.text="يجب استخدام رابط HTTPS آمن.";return@btn}
            status.text="جارٍ الاتصال..."
            executor.execute { try {
                val form="username="+URLEncoder.encode(user.text.toString(),"UTF-8")+"&password="+URLEncoder.encode(pass.text.toString(),"UTF-8")
                token=JSONObject(call("/auth/token","POST",form,null,"application/x-www-form-urlencoded")).getString("access_token")
                prefs.edit().putString("api_url",apiUrl).putString("token",token).apply()
                runOnUiThread{dashboard()}
            }catch(e:Exception){runOnUiThread{status.text="تعذر الدخول: "+(e.message?: "تحقق من البيانات")}} }
        })
    }
    private fun demoMode() {
        screen("تجربة البرنامج — بيانات وهمية")
        body.addView(label("أهلاً بك في النسخة التجريبية",21f,true))
        body.addView(label("هذه أمثلة توضيحية فقط. لا تُحفظ على الخادم ولا تمثل حسابات حقيقية."))
        body.addView(label("ملخص الحسابات",19f,true))
        body.addView(label("عدد الحسابات: 3"))
        body.addView(label("إجمالي الدخل: 125,000 ر.ي"))
        body.addView(label("إجمالي المصروفات: 42,500 ر.ي"))
        body.addView(label("صافي الحركة: 82,500 ر.ي",20f,true))
        body.addView(label("آخر الحركات",19f,true))
        body.addView(label("دخل — 75,000 ر.ي | مبيعات نقدية — مثال"))
        body.addView(label("مصروف — 12,500 ر.ي | مصاريف تشغيل — مثال"))
        body.addView(btn("الحسابات والأرصدة") {
            screen("الحسابات — بيانات تجريبية")
            body.addView(label("الصندوق الرئيسي — 80,000 ر.ي"))
            body.addView(label("حساب العملاء — 35,000 ر.ي"))
            body.addView(label("حساب المصروفات — 10,000 ر.ي"))
            body.addView(btn("رجوع للتجربة") { demoMode() })
        })
        body.addView(btn("الفواتير والدفعات") {
            screen("الفواتير والدفعات — تجربة")
            body.addView(label("فاتورة تجريبية رقم DEMO-001"))
            body.addView(label("العميل: عميل افتراضي"))
            body.addView(label("المبلغ: 25,000 ر.ي"))
            body.addView(label("دفعة مستلمة: 10,000 ر.ي"))
            body.addView(btn("رجوع للتجربة") { demoMode() })
        })
        body.addView(btn("العودة لتسجيل الدخول") { login() })
        status.text = "وضع التجربة — لا توجد بيانات حقيقية"
    }
    private fun call(path:String,method:String="GET",payload:String?=null,auth:String?=token,contentType:String="application/json"):String {
        val c=URL(apiUrl+path).openConnection() as HttpURLConnection
        c.requestMethod=method;c.connectTimeout=12000;c.readTimeout=12000;c.setRequestProperty("Accept","application/json")
        if(auth!=null)c.setRequestProperty("Authorization","Bearer "+auth)
        if(payload!=null){c.doOutput=true;c.setRequestProperty("Content-Type",contentType);OutputStreamWriter(c.outputStream,Charsets.UTF_8).use{it.write(payload)}}
        val code=c.responseCode;val stream=if(code in 200..299)c.inputStream else c.errorStream
        val data=stream?.bufferedReader(Charsets.UTF_8)?.use{it.readText()}?:"";c.disconnect()
        if(code !in 200..299)throw IllegalStateException(data.take(220))
        return data
    }
    private fun nav() {
        body.addView(btn("الرئيسية"){dashboard()})
        body.addView(btn("الحسابات والأرصدة"){accounts()})
        body.addView(btn("حركة دخل أو مصروف"){addTransaction()})
        body.addView(btn("سجل الحركات"){transactions()})
        body.addView(btn("الفواتير"){invoices()})
        body.addView(btn("إضافة فاتورة"){addInvoice()})
        body.addView(btn("الدفعات"){payments()})
        body.addView(btn("تسجيل دفعة"){addPayment()})
        body.addView(btn("التقارير"){reports()})
        body.addView(btn("حالة البيانات والمزامنة"){syncStatus()})
        body.addView(btn("إعدادات الاتصال"){settings()})
        body.addView(btn("تسجيل الخروج"){prefs.edit().remove("token").apply();token=null;login()})
    }
    private fun dashboard() {
        screen("لوحة التحكم")
        body.addView(label("مرحباً بك في حسابات المستودع",18f,true))
        nav()
        loadSummary()
    }
    private fun loadSummary() {
        executor.execute {
            try {
                val j = localDb.summary()
                runOnUiThread {
                    body.removeAllViews()
                    body.addView(label("ملخص محلي — البيانات المحفوظة على هذا الهاتف", 18f, true))
                    body.addView(label("عدد الحسابات: " + j.optInt("accounts_count")))
                    body.addView(label("إجمالي الدخل: " + j.optString("income") + " ر.ي"))
                    body.addView(label("إجمالي المصروفات: " + j.optString("expenses") + " ر.ي"))
                    body.addView(label("صافي الحركة: " + j.optString("net") + " ر.ي", 21f, true))
                    body.addView(label("عمليات بانتظار المزامنة: " + localDb.pendingCount()))
                    body.addView(btn("تحديث") { loadSummary() })
                    nav()
                    val recent = j.optJSONArray("recent_transactions") ?: JSONArray()
                    body.addView(label("آخر الحركات", 18f, true))
                    if (recent.length() == 0) body.addView(label("لا توجد حركات محفوظة محلياً"))
                    for (i in 0 until recent.length()) {
                        val t = recent.getJSONObject(i)
                        body.addView(label((if (t.optString("kind") == "income") "دخل" else "مصروف") + " — " + t.optString("amount") + " " + t.optString("currency") + "\n" + t.optString("description")))
                    }
                    status.text = "الملخص من SQLite — لا يتطلب اتصالاً"
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "تعذر قراءة الملخص المحلي: " + (e.message ?: "") }
            }
        }
    }
    private fun accounts() {
        screen("الحسابات والأرصدة")
        body.addView(btn("إضافة حساب") { addAccount() })
        body.addView(btn("رجوع") { dashboard() })
        executor.execute {
            try {
                val a = localDb.readEntities("accounts")
                runOnUiThread {
                    if (a.length() == 0) body.addView(label("لا توجد حسابات محفوظة على هذا الهاتف"))
                    for (i in 0 until a.length()) {
                        val x = a.getJSONObject(i)
                        body.addView(label("رقم محلي " + (i + 1) + " — " + x.optString("name") + "\nالرصيد: " + x.optString("balance") + " " + x.optString("currency"), 18f, true))
                    }
                    status.text = "قراءة محلية — بانتظار المزامنة: " + localDb.pendingCount()
                }
            } catch (e: Exception) { runOnUiThread { status.text = "خطأ في قاعدة البيانات: " + (e.message ?: "") } }
        }
    }
    private fun addAccount() {
        screen("إضافة حساب")
        val name=field("اسم الحساب");val currency=field("العملة","YER");val opening=field("الرصيد الافتتاحي","0")
        body.addView(label("اسم الحساب"));body.addView(name);body.addView(label("العملة (YER / SAR / USD)"));body.addView(currency);body.addView(label("الرصيد الافتتاحي"));body.addView(opening)
        body.addView(btn("حفظ الحساب"){val n=name.text.toString().trim();val c=currency.text.toString().trim().uppercase();val a=opening.text.toString().toBigDecimalOrNull()
            if(n.isEmpty()||c.length!=3||a==null){status.text="أدخل البيانات بشكل صحيح";return@btn}
            post("/accounts",JSONObject().put("name",n).put("currency",c).put("opening_balance",a).toString()){accounts()}
        });body.addView(btn("إلغاء"){accounts()})
    }
    private fun addTransaction() {
        screen("تسجيل حركة مالية")
        val accountsList=field("رقم الحساب");val kind=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("دخل","مصروف"))}
        val amount=field("المبلغ");val currency=field("العملة","YER");val desc=field("البيان")
        body.addView(label("رقم الحساب"));body.addView(accountsList);body.addView(label("نوع الحركة"));body.addView(kind);body.addView(label("المبلغ"));body.addView(amount);body.addView(label("العملة"));body.addView(currency);body.addView(label("البيان"));body.addView(desc)
        body.addView(btn("حفظ الحركة"){val id=accountsList.text.toString().toIntOrNull();val a=amount.text.toString().toBigDecimalOrNull()
            if(id==null||a==null||a<=java.math.BigDecimal.ZERO||desc.text.isBlank()){status.text="تحقق من البيانات";return@btn}
            executor.execute {
                val accountRecord = localDb.accountByLocalNumber(id)
                runOnUiThread {
                    if (accountRecord == null) { status.text = "رقم الحساب المحلي غير موجود. أضف حساباً أولاً."; return@runOnUiThread }
                    val p=JSONObject().put("account_id",id).put("account_local_id",accountRecord.optString("local_id")).put("kind",if(kind.selectedItemPosition==0)"income" else "expense").put("amount",a).put("currency",currency.text.toString().uppercase()).put("description",desc.text.toString().trim()).toString()
                    post("/transactions",p){dashboard()}
                }
            }
        });body.addView(btn("إلغاء"){dashboard()})
    }
    private fun transactions() {
        screen("سجل الحركات")
        body.addView(btn("تسجيل حركة جديدة") { addTransaction() })
        body.addView(btn("رجوع") { dashboard() })
        executor.execute {
            try {
                val a = localDb.readEntities("transactions")
                runOnUiThread {
                    if (a.length() == 0) body.addView(label("لا توجد حركات محفوظة محلياً"))
                    for (i in 0 until a.length()) {
                        val t = a.getJSONObject(i)
                        body.addView(label((if (t.optString("kind") == "income") "دخل" else "مصروف") + " — " + t.optString("amount") + " " + t.optString("currency") + "\n" + t.optString("description"), 17f))
                    }
                    status.text = "الحركات المحلية — بانتظار المزامنة: " + localDb.pendingCount()
                }
            } catch (e: Exception) { runOnUiThread { status.text = "تعذر قراءة الحركات: " + (e.message ?: "") } }
        }
    }
    private fun invoices() {
        screen("الفواتير")
        body.addView(btn("إضافة فاتورة") { addInvoice() })
        body.addView(btn("رجوع") { dashboard() })
        executor.execute {
            try {
                val a = localDb.readEntities("invoices")
                runOnUiThread {
                    if (a.length() == 0) body.addView(label("لا توجد فواتير محفوظة محلياً"))
                    for (i in 0 until a.length()) {
                        val x = a.getJSONObject(i)
                        body.addView(label("فاتورة " + x.optString("invoice_number") + "\nالعميل: " + x.optString("customer_name") + "\nالمبلغ: " + x.optString("amount") + " " + x.optString("currency"), 17f))
                    }
                    status.text = "الفواتير المحلية — بانتظار المزامنة: " + localDb.pendingCount()
                }
            } catch (e: Exception) { runOnUiThread { status.text = "تعذر قراءة الفواتير: " + (e.message ?: "") } }
        }
    }
    private fun addInvoice() {
        screen("إضافة فاتورة")
        val number=field("رقم الفاتورة");val customer=field("اسم العميل");val amount=field("المبلغ");val currency=field("العملة","YER")
        body.addView(label("رقم الفاتورة"));body.addView(number);body.addView(label("اسم العميل"));body.addView(customer);body.addView(label("المبلغ"));body.addView(amount);body.addView(label("العملة"));body.addView(currency)
        body.addView(btn("حفظ الفاتورة"){val a=amount.text.toString().toBigDecimalOrNull()
            if(number.text.isBlank()||customer.text.isBlank()||a==null||a<=java.math.BigDecimal.ZERO){status.text="تحقق من البيانات";return@btn}
            val p=JSONObject().put("invoice_number",number.text.toString().trim()).put("customer_name",customer.text.toString().trim()).put("amount",a).put("currency",currency.text.toString().uppercase()).toString()
            post("/invoices",p){invoices()}
        });body.addView(btn("إلغاء"){invoices()})
    }
    private fun payments() {
        screen("الدفعات")
        body.addView(btn("تسجيل دفعة") { addPayment() })
        body.addView(btn("رجوع") { dashboard() })
        executor.execute {
            try {
                val a = localDb.readEntities("payments")
                runOnUiThread {
                    if (a.length() == 0) body.addView(label("لا توجد دفعات محفوظة محلياً"))
                    for (i in 0 until a.length()) {
                        val x = a.getJSONObject(i)
                        body.addView(label((if (x.optString("direction") == "received") "مستلمة" else "مدفوعة") + " — " + x.optString("amount") + " " + x.optString("currency") + "\n" + x.optString("reference") + " • " + x.optString("state"), 17f))
                    }
                    status.text = "الدفعات المحلية — بانتظار المزامنة: " + localDb.pendingCount()
                }
            } catch (e: Exception) { runOnUiThread { status.text = "تعذر قراءة الدفعات: " + (e.message ?: "") } }
        }
    }
    private fun addPayment() {
        screen("تسجيل دفعة")
        val account=field("رقم الحساب");val direction=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("دفعة مستلمة","دفعة مدفوعة"))}
        val amount=field("المبلغ");val currency=field("العملة","YER");val reference=field("المرجع أو البيان")
        body.addView(label("رقم الحساب"));body.addView(account);body.addView(label("نوع الدفعة"));body.addView(direction);body.addView(label("المبلغ"));body.addView(amount);body.addView(label("العملة"));body.addView(currency);body.addView(label("المرجع"));body.addView(reference)
        body.addView(btn("حفظ الدفعة"){val id=account.text.toString().toIntOrNull();val a=amount.text.toString().toBigDecimalOrNull()
            if(id==null||a==null||a<=java.math.BigDecimal.ZERO||reference.text.isBlank()){status.text="تحقق من البيانات";return@btn}
            executor.execute {
                val accountRecord = localDb.accountByLocalNumber(id)
                runOnUiThread {
                    if (accountRecord == null) { status.text = "رقم الحساب المحلي غير موجود. أضف حساباً أولاً."; return@runOnUiThread }
                    val p=JSONObject().put("account_id",id).put("account_local_id",accountRecord.optString("local_id")).put("direction",if(direction.selectedItemPosition==0)"received" else "paid").put("amount",a).put("currency",currency.text.toString().uppercase()).put("reference",reference.text.toString().trim()).toString()
                    post("/payments",p){payments()}
                }
            }
        });body.addView(btn("إلغاء"){payments()})
    }
    private fun reports() {
        screen("التقارير المالية")
        body.addView(btn("تحديث التقرير") { reports() })
        body.addView(btn("رجوع") { dashboard() })
        executor.execute {
            try {
                val j = localDb.summary()
                runOnUiThread {
                    body.addView(label("تقرير محلي", 20f, true))
                    body.addView(label("عدد الحسابات: " + j.optInt("accounts_count")))
                    body.addView(label("إجمالي الدخل: " + j.optString("income") + " ر.ي", 18f, true))
                    body.addView(label("إجمالي المصروفات: " + j.optString("expenses") + " ر.ي", 18f, true))
                    body.addView(label("صافي الحركة: " + j.optString("net") + " ر.ي", 20f, true))
                    body.addView(label("عمليات بانتظار المزامنة: " + localDb.pendingCount()))
                    body.addView(label("هذا تقرير من بيانات الهاتف فقط؛ لا يعرض بعدُ بيانات الخادم غير المنزّلة."))
                    status.text = "تم إعداد التقرير محلياً"
                }
            } catch (e: Exception) { runOnUiThread { status.text = "تعذر إنشاء التقرير: " + (e.message ?: "") } }
        }
    }

    private fun syncStatus() {
        screen("حالة البيانات والمزامنة")
        body.addView(btn("تحديث الحالة") { syncStatus() })
        body.addView(btn("رجوع للرئيسية") { dashboard() })
        executor.execute {
            try {
                val counts = localDb.localCounts()
                val pending = localDb.pendingOperations()
                runOnUiThread {
                    body.addView(label("ملخص قاعدة البيانات على هذا الهاتف", 19f, true))
                    body.addView(label("الحسابات: " + counts.optInt("accounts")))
                    body.addView(label("الحركات: " + counts.optInt("transactions")))
                    body.addView(label("الفواتير: " + counts.optInt("invoices")))
                    body.addView(label("الدفعات: " + counts.optInt("payments")))
                    body.addView(label("عمليات بانتظار المزامنة: " + counts.optInt("pending"), 18f, true))
                    body.addView(label("حالة الاتصال: " + if (token == null) "وضع محلي دون تسجيل دخول" else "تم تسجيل الدخول سابقاً؛ المزامنة التلقائية غير مفعلة"))
                    body.addView(label("تنبيه: لا تحذف بيانات التطبيق قبل إكمال المزامنة والنسخ الاحتياطي.", 16f, true))
                    if (pending.isNotEmpty()) {
                        body.addView(label("أقدم العمليات المنتظرة", 18f, true))
                        pending.take(20).forEach { op ->
                            body.addView(label(op.entity + " • " + op.method + " • محاولات: " + op.attempts + "\\n" + op.operationId))
                        }
                    }
                    status.text = "تم فحص قاعدة البيانات المحلية"
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "تعذر فحص قاعدة البيانات: " + (e.message ?: "") }
            }
        }
    }
    private fun settings() {
        screen("إعدادات الاتصال")
        val url=field("رابط API HTTPS",apiUrl)
        body.addView(label("عنوان الخادم المشترك"));body.addView(url)
        body.addView(label("يجب أن يبدأ العنوان بـ https://. لا تدخل كلمات المرور هنا."))
        body.addView(btn("حفظ عنوان الخادم"){val v=url.text.toString().trim().trimEnd('/')
            if(!v.startsWith("https://")){status.text="استخدم HTTPS";return@btn}
            apiUrl=v;prefs.edit().putString("api_url",apiUrl).apply();status.text="تم حفظ العنوان"
        })
        body.addView(btn("رجوع"){dashboard()})
    }
    private fun post(path:String,payload:String,success:()->Unit) {
        status.text="جارٍ الحفظ محلياً..."
        executor.execute {
            try {
                val entity = when(path) {
                    "/accounts" -> "accounts"
                    "/transactions" -> "transactions"
                    "/invoices" -> "invoices"
                    "/payments" -> "payments"
                    else -> throw IllegalArgumentException("مسار حفظ غير معروف")
                }
                val json = JSONObject(payload)
                if (entity == "accounts") {
                    json.put("opening_balance", json.optString("opening_balance", "0"))
                    json.put("balance", json.optString("opening_balance", "0"))
                }
                localDb.saveEntity(entity, json)
                runOnUiThread {
                    Toast.makeText(this,"حُفظت البيانات على الهاتف. المزامنة المشتركة لم تُفعّل بعد.",Toast.LENGTH_LONG).show()
                    status.text = "محفوظ محلياً — عمليات بانتظار المزامنة: " + localDb.pendingCount()
                    success()
                }
            } catch(e:Exception) {
                runOnUiThread { status.text="تعذر الحفظ المحلي: "+(e.message?: "") }
            }
        }
    }
    override fun onDestroy(){executor.shutdown();super.onDestroy()}
}
