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
    private fun btn(s:String,f:()->Unit)=Button(this).apply {
        text=s; textSize=15f; setTextColor(Color.WHITE)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.rgb(24, 112, 82)); cornerRadius = 18f
        }
        elevation = 3f
        isAllCaps = false
        setPadding(18, 12, 18, 12)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, 6, 0, 6)
        layoutParams = lp
        setOnClickListener{f()}
    }
    private fun screen(title:String) {
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(22,22,22,22);layoutDirection=View.LAYOUT_DIRECTION_RTL;setBackgroundColor(Color.rgb(246,248,246)) }
        root.addView(label(title,25f,true).apply { setTextColor(Color.rgb(18, 91, 66)); setPadding(0, 8, 0, 18) })
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
        body.addView(btn("العملاء"){catalogScreen("customers", "العملاء", listOf("الاسم", "الهاتف", "العنوان"))})
        body.addView(btn("الموردون"){catalogScreen("suppliers", "الموردون", listOf("اسم المورد", "الهاتف", "العنوان"))})
        body.addView(btn("الأصناف والمخزون"){catalogScreen("products", "الأصناف والمخزون", listOf("اسم الصنف", "رمز الصنف", "الوحدة", "الكمية", "سعر الوحدة"))})
        body.addView(btn("حركة المخزون"){catalogScreen("stock", "حركة المخزون", listOf("الصنف", "نوع الحركة: وارد/صادر", "الكمية", "المرجع"))})
        body.addView(btn("المشتريات"){catalogScreen("purchases", "المشتريات", listOf("رقم المستند", "المورد", "الصنف", "الكمية", "المبلغ", "العملة"))})
        body.addView(btn("المبيعات"){catalogScreen("sales", "المبيعات", listOf("رقم المستند", "العميل", "الصنف", "الكمية", "المبلغ", "العملة"))})
        body.addView(btn("بحث شامل"){searchScreen()})
        body.addView(btn("كشف حساب"){accountStatement()})
        body.addView(btn("النسخ الاحتياطي"){backupScreen()})
        body.addView(btn("سجل النشاط والتعديلات"){auditScreen()})
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
                    body.addView(label("الحركة حسب العملة", 18f, true))
                    val currencyTotals = j.optJSONObject("by_currency") ?: JSONObject()
                    if (currencyTotals.length() == 0) body.addView(label("لا توجد حركات مالية بعد"))
                    val currencyKeys = currencyTotals.keys()
                    while (currencyKeys.hasNext()) {
                        val currency = currencyKeys.next()
                        val totals = currencyTotals.getJSONObject(currency)
                        body.addView(label("$currency • دخل: ${totals.optString("income")} • مصروف: ${totals.optString("expenses")} • الصافي: ${totals.optString("net")}", 16f, true))
                    }
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
        val accounts = localDb.readEntities("accounts")
        if (accounts.length() == 0) {
            body.addView(label("أضف حساباً أولاً قبل تسجيل الحركة"))
            body.addView(btn("إضافة حساب") { addAccount() })
            body.addView(btn("رجوع") { dashboard() })
            return
        }
        val choices = (0 until accounts.length()).map { i ->
            val a = accounts.getJSONObject(i)
            a.optString("name") + " • " + a.optString("currency")
        }
        val account = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, choices) }
        val kind = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("دخل", "مصروف")) }
        val amount = field("المبلغ")
        val desc = field("البيان")
        body.addView(label("الحساب")); body.addView(account)
        body.addView(label("نوع الحركة")); body.addView(kind)
        body.addView(label("المبلغ")); body.addView(amount)
        body.addView(label("البيان")); body.addView(desc)
        body.addView(btn("حفظ الحركة") {
            val selected = accounts.getJSONObject(account.selectedItemPosition)
            val a = amount.text.toString().toBigDecimalOrNull()
            if (a == null || a <= java.math.BigDecimal.ZERO || desc.text.isBlank()) {
                status.text = "أدخل مبلغاً موجباً وبيان الحركة"; return@btn
            }
            val p = JSONObject()
                .put("account_id", if (selected.isNull("server_id")) 0 else selected.optInt("server_id"))
                .put("account_local_id", selected.optString("local_id"))
                .put("kind", if (kind.selectedItemPosition == 0) "income" else "expense")
                .put("amount", a.toPlainString())
                .put("currency", selected.optString("currency", "YER"))
                .put("description", desc.text.toString().trim())
            post("/transactions", p.toString()) { dashboard() }
        })
        body.addView(btn("إلغاء") { dashboard() })
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
                        body.addView(btn("تفاصيل الفاتورة والدفعات") { invoiceDetails(x) })
                    }
                    status.text = "الفواتير المحلية — بانتظار المزامنة: " + localDb.pendingCount()
                }
            } catch (e: Exception) { runOnUiThread { status.text = "تعذر قراءة الفواتير: " + (e.message ?: "") } }
        }
    }
    private fun invoiceDetails(invoice: JSONObject) {
        screen("تفاصيل الفاتورة")
        val currency = invoice.optString("currency", "YER")
        val total = invoice.optString("amount", "0").toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        var paid = java.math.BigDecimal.ZERO
        body.addView(label("رقم الفاتورة: " + invoice.optString("invoice_number"), 20f, true))
        body.addView(label("العميل: " + invoice.optString("customer_name")))
        body.addView(label("إجمالي الفاتورة: " + total.toPlainString() + " " + currency))
        val payments = localDb.readEntities("payments")
        body.addView(label("الدفعات المرتبطة", 18f, true))
        var linked = 0
        for (i in 0 until payments.length()) {
            val payment = payments.getJSONObject(i)
            if (payment.optString("invoice_local_id") != invoice.optString("local_id")) continue
            linked++
            val amount = payment.optString("amount", "0").toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            if (payment.optString("direction") == "received" && payment.optString("currency", currency) == currency) paid = paid.add(amount)
            body.addView(label((if (payment.optString("direction") == "received") "مستلمة" else "مدفوعة") +
                " • " + amount.toPlainString() + " " + payment.optString("currency", currency) +
                " • " + payment.optString("reference")))
        }
        if (linked == 0) body.addView(label("لا توجد دفعات مرتبطة بهذه الفاتورة"))
        val remaining = total.subtract(paid).max(java.math.BigDecimal.ZERO)
        body.addView(label("المدفوع المستلم بنفس العملة: " + paid.toPlainString() + " " + currency, 17f, true))
        body.addView(label("المتبقي: " + remaining.toPlainString() + " " + currency, 20f, true))
        body.addView(label("الحساب محلي مبدئي؛ لا يعتبر قيداً محاسبياً نهائياً قبل تفعيل الخادم.", 14f))
        body.addView(btn("رجوع للفواتير") { invoices() })
    }

    private fun addInvoice() {
        screen("إضافة فاتورة")
        val number=field("رقم الفاتورة");val customer=field("اسم العميل");val amount=field("المبلغ");val currency=field("العملة","YER")
        body.addView(label("رقم الفاتورة"));body.addView(number);body.addView(label("اسم العميل"));body.addView(customer);body.addView(label("المبلغ"));body.addView(amount);body.addView(label("العملة"));body.addView(currency)
        body.addView(btn("حفظ الفاتورة"){val a=amount.text.toString().toBigDecimalOrNull()
            if(number.text.isBlank()||customer.text.isBlank()||a==null||a<=java.math.BigDecimal.ZERO){status.text="تحقق من البيانات";return@btn}
            val customerName = customer.text.toString().trim()
            val p = JSONObject().put("invoice_number", number.text.toString().trim())
                .put("customer_name", customerName).put("amount", a.toPlainString())
                .put("currency", currency.text.toString().uppercase())
            val customers = localDb.readCatalogRecords("customers")
            for (i in 0 until customers.length()) {
                val c = customers.getJSONObject(i)
                if (c.optString("الاسم").equals(customerName, true)) {
                    p.put("customer_local_id", c.optString("local_id")); break
                }
            }
            post("/invoices", p.toString()) { invoices() }
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
        val accounts = localDb.readEntities("accounts")
        if (accounts.length() == 0) {
            body.addView(label("أضف حساباً أولاً قبل تسجيل الدفعة"))
            body.addView(btn("إضافة حساب") { addAccount() })
            body.addView(btn("رجوع") { dashboard() })
            return
        }
        val choices = (0 until accounts.length()).map { i ->
            val a = accounts.getJSONObject(i)
            a.optString("name") + " • " + a.optString("currency")
        }
        val account = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, choices) }
        val direction = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("دفعة مستلمة", "دفعة مدفوعة")) }
        val amount = field("المبلغ")
        val reference = field("المرجع أو البيان")
        val invoiceNumber = field("رقم الفاتورة (اختياري للربط)")
        body.addView(label("الحساب")); body.addView(account)
        body.addView(label("نوع الدفعة")); body.addView(direction)
        body.addView(label("المبلغ")); body.addView(amount)
        body.addView(label("رقم الفاتورة إن وجدت")); body.addView(invoiceNumber)
        body.addView(label("المرجع")); body.addView(reference)
        body.addView(btn("حفظ الدفعة") {
            val selected = accounts.getJSONObject(account.selectedItemPosition)
            val a = amount.text.toString().toBigDecimalOrNull()
            if (a == null || a <= java.math.BigDecimal.ZERO || reference.text.isBlank()) {
                status.text = "تحقق من المبلغ والمرجع"; return@btn
            }
            val p = JSONObject()
                .put("account_id", if (selected.isNull("server_id")) 0 else selected.optInt("server_id"))
                .put("account_local_id", selected.optString("local_id"))
                .put("direction", if (direction.selectedItemPosition == 0) "received" else "paid")
                .put("amount", a.toPlainString())
                .put("currency", selected.optString("currency", "YER"))
                .put("reference", reference.text.toString().trim())
            val invoiceNo = invoiceNumber.text.toString().trim()
            if (invoiceNo.isNotBlank()) {
                val invoices = localDb.readEntities("invoices")
                var found = false
                for (i in 0 until invoices.length()) {
                    val invoice = invoices.getJSONObject(i)
                    if (invoice.optString("invoice_number").equals(invoiceNo, true)) {
                        p.put("invoice_local_id", invoice.optString("local_id"))
                        found = true; break
                    }
                }
                if (!found) { status.text = "رقم الفاتورة غير موجود؛ اتركه فارغاً أو أدخل رقماً صحيحاً"; return@btn }
            }
            post("/payments", p.toString()) { payments() }
        })
        body.addView(btn("إلغاء") { dashboard() })
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
                    body.addView(label("الحركة حسب العملة", 18f, true))
                    val currencyTotals = j.optJSONObject("by_currency") ?: JSONObject()
                    if (currencyTotals.length() == 0) body.addView(label("لا توجد حركات مالية بعد"))
                    val currencyKeys = currencyTotals.keys()
                    while (currencyKeys.hasNext()) {
                        val currency = currencyKeys.next()
                        val totals = currencyTotals.getJSONObject(currency)
                        body.addView(label("$currency • دخل: ${totals.optString("income")} • مصروف: ${totals.optString("expenses")} • الصافي: ${totals.optString("net")}", 16f, true))
                    }
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

    private fun catalogScreen(type: String, title: String, fields: List<String>) {
        screen(title)
        val query = field("بحث في " + title)
        body.addView(query)
        body.addView(btn("بحث") { renderCatalog(type, title, fields, query.text.toString()) })
        body.addView(btn("إضافة سجل") { catalogForm(type, title, fields) })
        body.addView(btn("رجوع للرئيسية") { dashboard() })
        renderCatalog(type, title, fields, "")
    }

    private fun renderCatalog(type: String, title: String, fields: List<String>, query: String) {
        body.removeAllViews()
        val search = field("بحث في " + title, query)
        body.addView(search)
        body.addView(btn("تطبيق البحث") { renderCatalog(type, title, fields, search.text.toString()) })
        body.addView(btn("إضافة سجل") { catalogForm(type, title, fields) })
        body.addView(btn("رجوع للرئيسية") { dashboard() })
        executor.execute {
            val rows = localDb.readCatalogRecords(type)
            runOnUiThread {
                var shown = 0
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val text = fields.map { row.optString(it) }.joinToString(" • ")
                    if (query.isNotBlank() && !text.contains(query, true)) continue
                    shown++
                    body.addView(label(text.ifBlank { "سجل بدون تفاصيل" }, 16f, true))
                    body.addView(btn("حذف هذا السجل") {
                        localDb.deleteCatalogRecord(type, row.optString("local_id"))
                        renderCatalog(type, title, fields, query)
                    })
                }
                if (shown == 0) body.addView(label("لا توجد سجلات مطابقة. أضف سجلاً جديداً للبدء."))
                status.text = "هذه البيانات محفوظة محلياً على الهاتف"
            }
        }
    }

    private fun catalogForm(type: String, title: String, fields: List<String>) {
        screen("إضافة — " + title)
        val inputs = fields.map { f ->
            val input = field(f)
            body.addView(label(f)); body.addView(input); input
        }
        body.addView(btn("حفظ السجل") {
            val values = inputs.mapIndexed { i, v -> fields[i] to v.text.toString().trim() }
            if (values.any { it.second.isBlank() }) {
                status.text = "أكمل الحقول المطلوبة"; return@btn
            }
            val json = JSONObject()
            values.forEach { (k, v) -> json.put(k, v) }

            // Keep product quantities linked to stock movements, purchases and sales.
            if (type in setOf("stock", "purchases", "sales")) {
                val itemName = json.optString(if (type == "stock") "الصنف" else "الصنف")
                val qty = json.optString("الكمية").toBigDecimalOrNull()
                if (itemName.isBlank() || qty == null || qty <= java.math.BigDecimal.ZERO) {
                    status.text = "اسم الصنف والكمية الموجبة مطلوبان"
                    return@btn
                }
                val products = localDb.readCatalogRecords("products")
                var matched: JSONObject? = null
                for (i in 0 until products.length()) {
                    val product = products.getJSONObject(i)
                    if (product.optString("اسم الصنف").equals(itemName, true)) { matched = product; break }
                }
                if (matched == null) {
                    status.text = "الصنف غير موجود. أضفه أولاً في شاشة الأصناف."
                    return@btn
                }
                val product = matched!!
                val current = product.optString("الكمية", "0").toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
                val increase = type == "purchases" || (type == "stock" && json.optString("نوع الحركة: وارد/صادر").contains("وارد", true))
                val delta = if (increase) qty else qty.negate()
                val updated = current.add(delta)
                if (updated < java.math.BigDecimal.ZERO) {
                    status.text = "الكمية غير كافية في المخزون"
                    return@btn
                }
                product.put("الكمية", updated.stripTrailingZeros().toPlainString())
                localDb.saveCatalogRecord("products", product)
                json.put("product_local_id", product.optString("local_id"))
                json.put("quantity_delta", delta.toPlainString())
                json.put("quantity_after", updated.toPlainString())
            }
            localDb.saveCatalogRecord(type, json)
            Toast.makeText(this, "تم الحفظ محلياً وتحديث كمية الصنف", Toast.LENGTH_SHORT).show()
            catalogScreen(type, title, fields)
        })
        body.addView(btn("إلغاء") { catalogScreen(type, title, fields) })
    }

    private fun searchScreen() {
        screen("بحث شامل")
        val q = field("اكتب اسم حساب أو وصف حركة أو عميل أو صنف")
        body.addView(q)
        body.addView(btn("بحث الآن") {
            val term = q.text.toString().trim()
            if (term.isBlank()) { status.text = "اكتب كلمة للبحث"; return@btn }
            body.addView(label("نتائج البحث المحلي عن: " + term, 18f, true))
            executor.execute {
                val results = mutableListOf<String>()
                for (table in listOf("accounts", "transactions", "invoices", "payments")) {
                    val rows = localDb.readEntities(table)
                    for (i in 0 until rows.length()) {
                        val obj = rows.getJSONObject(i)
                        if (obj.toString().contains(term, true)) results.add(table + " • " + obj.toString().take(220))
                    }
                }
                for (type in listOf("customers", "suppliers", "products", "stock", "purchases", "sales")) {
                    val rows = localDb.readCatalogRecords(type)
                    for (i in 0 until rows.length()) if (rows.getJSONObject(i).toString().contains(term, true)) results.add(type + " • " + rows.getJSONObject(i).toString().take(220))
                }
                runOnUiThread {
                    if (results.isEmpty()) body.addView(label("لا توجد نتائج محلية"))
                    results.take(100).forEach { body.addView(label(it)) }
                    status.text = "البحث يشمل بيانات الهاتف فقط"
                }
            }
        })
        body.addView(btn("رجوع") { dashboard() })
    }

    private fun accountStatement() {
        screen("كشف حساب")
        val accounts = localDb.readEntities("accounts")
        val choices = (0 until accounts.length()).map { i ->
            val a = accounts.getJSONObject(i)
            a.optString("name") + " (" + a.optString("currency") + ")"
        }
        if (choices.isEmpty()) {
            body.addView(label("أضف حساباً أولاً لعرض كشفه"))
        } else {
            val spinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, choices) }
            body.addView(label("اختر الحساب")); body.addView(spinner)
            body.addView(btn("عرض الكشف") {
                val selected = accounts.getJSONObject(spinner.selectedItemPosition)
                val localId = selected.optString("local_id")
                body.addView(label("الحساب: " + selected.optString("name"), 18f, true))
                val currency = selected.optString("currency", "YER")
                body.addView(label("الرصيد الافتتاحي: " + selected.optString("opening_balance") + " " + currency))
                body.addView(label("الرصيد الحالي المحسوب: " + localDb.accountBalance(localId, currency, selected.optString("opening_balance")).toPlainString() + " " + currency, 18f, true))
                val tx = localDb.readEntities("transactions")
                var count = 0
                for (i in 0 until tx.length()) {
                    val t = tx.getJSONObject(i)
                    if (t.optString("account_local_id") == localId) {
                        count++
                        body.addView(label((if (t.optString("kind") == "income") "دخل" else "مصروف") + " • " + t.optString("amount") + " " + t.optString("currency") + " • " + t.optString("description")))
                    }
                }
                val payments = localDb.readEntities("payments")
                for (i in 0 until payments.length()) {
                    val p = payments.getJSONObject(i)
                    if (p.optString("account_local_id") == localId) {
                        count++
                        body.addView(label((if (p.optString("direction") == "received") "دفعة مستلمة" else "دفعة مدفوعة") + " • " + p.optString("amount") + " " + p.optString("currency") + " • " + p.optString("reference")))
                    }
                }
                if (count == 0) body.addView(label("لا توجد حركات مرتبطة بهذا الحساب"))
                status.text = "كشف محلي مبدئي؛ لا يشمل بيانات الخادم غير المنزّلة"
            })
        }
        body.addView(btn("رجوع") { dashboard() })
    }

    private fun auditScreen() {
        screen("سجل النشاط والتعديلات")
        body.addView(label("سجل محلي للعمليات التي نفذها هذا التطبيق على هذا الهاتف.", 15f))
        body.addView(btn("تحديث السجل") { auditScreen() })
        body.addView(btn("رجوع للرئيسية") { dashboard() })
        executor.execute {
            val entries = localDb.recentAudit(200)
            runOnUiThread {
                if (entries.length() == 0) body.addView(label("لا توجد أنشطة مسجلة بعد"))
                for (i in 0 until entries.length()) {
                    val entry = entries.getJSONObject(i)
                    val date = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.optLong("created_at")))
                    body.addView(label(date + "\n" + entry.optString("action") + " • " + entry.optString("entity") +
                        "\n" + entry.optString("details").take(180), 14f))
                }
                status.text = "السجل محلي ولا يمثل تدقيقاً مركزياً على الخادم"
            }
        }
    }

    private fun backupScreen() {
        screen("النسخ الاحتياطي")
        body.addView(label("يمكنك تصدير ملخص JSON للمشاركة أو الاحتفاظ به. لا ترفع الملف إلى جهة غير موثوقة.", 16f))
        body.addView(btn("إنشاء ملف نسخة احتياطية") {
            try {
                val backup = JSONObject()
                for (table in listOf("accounts", "transactions", "invoices", "payments")) backup.put(table, localDb.readEntities(table))
                for (type in listOf("customers", "suppliers", "products", "stock", "purchases", "sales")) backup.put(type, localDb.readCatalogRecords(type))
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "نسخة احتياطية للمحاسبة")
                    putExtra(android.content.Intent.EXTRA_TEXT, backup.toString(2))
                }
                startActivity(android.content.Intent.createChooser(intent, "مشاركة النسخة الاحتياطية"))
                status.text = "تم تجهيز بيانات النسخة للمشاركة؛ احفظها في مكان آمن"
            } catch (e: Exception) {
                status.text = "تعذر إنشاء النسخة: " + (e.message ?: "")
            }
        })
        body.addView(btn("رجوع") { dashboard() })
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
