package com.adnanalhasani.accounting

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.OutputStreamWriter
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("accounting", MODE_PRIVATE) }
    private var token: String? = null
    private var apiUrl = ""
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = prefs.getString("token", null)
        apiUrl = prefs.getString("api_url", "") ?: ""
        if (token == null) login() else dashboard()
    }
    private fun label(s: String, size: Float = 17f): TextView = TextView(this).apply {
        text = s; textSize = size; setTextColor(Color.rgb(25, 55, 42)); gravity = Gravity.RIGHT
        setPadding(0, 12, 0, 12); textDirection = View.TEXT_DIRECTION_RTL
    }
    private fun field(hintText: String, value: String = "") = EditText(this).apply {
        hint = hintText; setText(value); gravity = Gravity.RIGHT; textDirection = View.TEXT_DIRECTION_RTL
    }
    private fun button(s: String, f: () -> Unit) = Button(this).apply { text = s; setOnClickListener { f() } }
    private fun screen(title: String) {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24); layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(Color.rgb(246,248,246)) }
        root.addView(label(title, 26f))
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        status = label("")
        root.addView(status); setContentView(root)
    }
    private fun login() {
        screen("حسابات المستودع")
        body.addView(label("إدارة الحسابات والحركات المالية"))
        val url = field("رابط الخادم HTTPS", apiUrl)
        val user = field("اسم المستخدم", "admin")
        val pass = field("كلمة المرور")
        pass.inputType = 129
        body.addView(label("رابط الخادم")); body.addView(url)
        body.addView(label("اسم المستخدم")); body.addView(user)
        body.addView(label("كلمة المرور")); body.addView(pass)
        body.addView(button("تسجيل الدخول") {
            apiUrl = url.text.toString().trim().trimEnd('/')
            if (!apiUrl.startsWith("https://")) { status.text = "يجب استخدام رابط HTTPS آمن."; return@button }
            status.text = "جارٍ الاتصال..."
            executor.execute {
                try {
                    val form = "username=" + URLEncoder.encode(user.text.toString(),"UTF-8") + "&password=" + URLEncoder.encode(pass.text.toString(),"UTF-8")
                    val result = JSONObject(call("/auth/token","POST",form,null,"application/x-www-form-urlencoded"))
                    token = result.getString("access_token")
                    prefs.edit().putString("api_url",apiUrl).putString("token",token).apply()
                    runOnUiThread { dashboard() }
                } catch (e: Exception) { runOnUiThread { status.text = "فشل الدخول: " + (e.message ?: "تحقق من البيانات والخادم") } }
            }
        })
    }
    private fun call(path: String, method: String = "GET", payload: String? = null, auth: String? = token, contentType: String = "application/json"): String {
        val c = URL(apiUrl + path).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 12000; c.readTimeout = 12000
        c.setRequestProperty("Accept","application/json")
        if (auth != null) c.setRequestProperty("Authorization","Bearer " + auth)
        if (payload != null) {
            c.doOutput = true; c.setRequestProperty("Content-Type",contentType)
            OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(payload) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val data = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) throw IllegalStateException(data.take(200))
        return data
    }
    private fun dashboard() {
        screen("لوحة الحسابات")
        body.addView(button("تحديث الملخص") { loadSummary() })
        body.addView(button("عرض الحسابات") { accounts() })
        body.addView(button("إضافة حساب") { addAccount() })
        body.addView(button("تسجيل حركة دخل أو مصروف") { addTransaction() })
        body.addView(button("الفواتير") { invoices() })
        body.addView(button("تسجيل الخروج") { prefs.edit().remove("token").apply(); token=null; login() })
        loadSummary()
    }
    private fun loadSummary() {
        status.text = "جارٍ تحميل البيانات..."
        executor.execute {
            try {
                val j = JSONObject(call("/summary"))
                runOnUiThread {
                    body.removeAllViews()
                    body.addView(label("ملخص النشاط المالي",21f))
                    body.addView(label("عدد الحسابات: " + j.optInt("accounts_count")))
                    body.addView(label("إجمالي الدخل: " + j.optString("income") + " ر.ي"))
                    body.addView(label("المصروفات: " + j.optString("expenses") + " ر.ي"))
                    body.addView(label("صافي الحركة: " + j.optString("net") + " ر.ي",21f))
                    body.addView(button("عرض الحسابات") { accounts() })
                    body.addView(button("إضافة حساب") { addAccount() })
                    body.addView(button("تسجيل حركة دخل أو مصروف") { addTransaction() })
                    body.addView(button("الفواتير") { invoices() })
                    body.addView(button("تسجيل الخروج") { prefs.edit().remove("token").apply(); token=null; login() })
                    status.text = "تم التحديث"
                }
            } catch (e: Exception) { runOnUiThread { status.text = "تعذر تحميل البيانات: " + (e.message ?: "") } }
        }
    }
    private fun accounts() {
        screen("الحسابات")
        body.addView(button("رجوع") { dashboard() })
        executor.execute {
            try {
                val arr = org.json.JSONArray(call("/accounts"))
                runOnUiThread {
                    body.addView(button("إضافة حساب") { addAccount() })
                    if (arr.length()==0) body.addView(label("لا توجد حسابات مسجلة."))
                    for (i in 0 until arr.length()) {
                        val a=arr.getJSONObject(i)
                        body.addView(label(a.optString("name") + " — الرصيد: " + a.optString("balance") + " " + a.optString("currency")))
                    }
                    status.text="تم التحميل"
                }
            } catch(e:Exception) { runOnUiThread { status.text="خطأ: " + (e.message ?: "") } }
        }
    }
    private fun addAccount() {
        screen("إضافة حساب")
        val name=field("اسم الحساب"); val currency=field("العملة","YER"); val opening=field("الرصيد الافتتاحي","0")
        body.addView(label("اسم الحساب")); body.addView(name); body.addView(label("العملة")); body.addView(currency); body.addView(label("الرصيد الافتتاحي")); body.addView(opening)
        body.addView(button("حفظ") {
            val amount=opening.text.toString().toBigDecimalOrNull()
            if(name.text.isBlank() || currency.text.length!=3 || amount==null) { status.text="تحقق من البيانات."; return@button }
            post("/accounts",JSONObject().put("name",name.text.toString().trim()).put("currency",currency.text.toString().uppercase()).put("opening_balance",amount).toString()) { accounts() }
        })
        body.addView(button("إلغاء") { dashboard() })
    }
    private fun addTransaction() {
        screen("تسجيل حركة")
        val account=field("رقم الحساب"); val kind=Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("دخل","مصروف")) }
        val amount=field("المبلغ"); val currency=field("العملة","YER"); val desc=field("البيان")
        body.addView(label("رقم الحساب")); body.addView(account); body.addView(label("نوع الحركة")); body.addView(kind); body.addView(label("المبلغ")); body.addView(amount); body.addView(label("العملة")); body.addView(currency); body.addView(label("البيان")); body.addView(desc)
        body.addView(button("حفظ الحركة") {
            val id=account.text.toString().toIntOrNull(); val amt=amount.text.toString().toBigDecimalOrNull()
            if(id==null || amt==null || amt<=java.math.BigDecimal.ZERO || desc.text.isBlank()) { status.text="تحقق من البيانات."; return@button }
            val p=JSONObject().put("account_id",id).put("kind",if(kind.selectedItemPosition==0) "income" else "expense").put("amount",amt).put("currency",currency.text.toString().uppercase()).put("description",desc.text.toString().trim()).toString()
            post("/transactions",p) { dashboard() }
        })
        body.addView(button("إلغاء") { dashboard() })
    }
    private fun invoices() {
        screen("الفواتير")
        body.addView(button("رجوع") { dashboard() })
        executor.execute {
            try {
                val arr=org.json.JSONArray(call("/invoices"))
                runOnUiThread {
                    body.addView(button("إضافة فاتورة") { addInvoice() })
                    if(arr.length()==0) body.addView(label("لا توجد فواتير."))
                    for(i in 0 until arr.length()) {
                        val x=arr.getJSONObject(i)
                        body.addView(label(x.optString("invoice_number") + " — " + x.optString("customer_name") + " — " + x.optString("amount") + " " + x.optString("currency")))
                    }
                    status.text="تم التحميل"
                }
            } catch(e:Exception) { runOnUiThread { status.text="خطأ: " + (e.message ?: "") } }
        }
    }
    private fun addInvoice() {
        screen("إضافة فاتورة")
        val number=field("رقم الفاتورة"); val customer=field("اسم العميل"); val amount=field("المبلغ"); val currency=field("العملة","YER")
        body.addView(label("رقم الفاتورة")); body.addView(number); body.addView(label("العميل")); body.addView(customer); body.addView(label("المبلغ")); body.addView(amount); body.addView(label("العملة")); body.addView(currency)
        body.addView(button("حفظ الفاتورة") {
            val amt=amount.text.toString().toBigDecimalOrNull()
            if(number.text.isBlank() || customer.text.isBlank() || amt==null || amt<=java.math.BigDecimal.ZERO) { status.text="تحقق من البيانات."; return@button }
            val p=JSONObject().put("invoice_number",number.text.toString().trim()).put("customer_name",customer.text.toString().trim()).put("amount",amt).put("currency",currency.text.toString().uppercase()).toString()
            post("/invoices",p) { invoices() }
        })
    }
    private fun post(path:String,payload:String,success:()->Unit) {
        status.text="جارٍ الحفظ..."
        executor.execute {
            try { call(path,"POST",payload); runOnUiThread { Toast.makeText(this,"تم الحفظ",Toast.LENGTH_SHORT).show(); success() } }
            catch(e:Exception) { runOnUiThread { status.text="لم يتم الحفظ: " + (e.message ?: "") } }
        }
    }
    override fun onDestroy() { executor.shutdown(); super.onDestroy() }
}
