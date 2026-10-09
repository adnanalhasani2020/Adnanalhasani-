# قرار تصميم: فرض اتساق Activity بين Sale وOffering

## القرار

استخدام SQLite triggers في migration جديدة بدل إعادة بناء جدول `sales` لإضافة composite foreign key. تُفرض المطابقة عند INSERT وUPDATE في `sales`، ويُرفض UPDATE على `offerings.activity_id` إذا كان سيترك أي Sale تابعة في Activity مختلف.

## المتطلب والدليل

تفرض SPEC-0005 §10.1 و§§28.6–28.7 وInvariants 16–17 وAC-04 أن تحافظ Sale على Activity context الخاص بالـOffering المرتبط بها. ترجمة هذا المتطلب إلى النموذج العلائقي الحالي هي `sales.activity_id = offerings.activity_id` لكل Sale.

## تقييم البدائل

- **Composite foreign key:** الخيار العلائقي الأكثر تصريحًا، لكنه يتطلب مفتاحًا فريدًا على `offerings(offering_id, activity_id)` وإعادة بناء `sales` لإضافة القيد. الجدول `invoices` يشير إلى `sales(sale_id)`، وآلية الاتصال تفعّل `PRAGMA foreign_keys=ON`. لذلك إعادة البناء تحتاج التعامل بدقة مع اعتماد invoices، ترتيب القيود، وإعادة إنشاء الفهارس والقيود؛ لا نحتاج إلى هذا الخطر لهذا الإصلاح المحدود.
- **SQLite triggers (المختار):** يفرض القاعدة داخل قاعدة البيانات، بما يشمل SQL المباشر، دون إعادة بناء `sales` أو تغيير تعريف FK الحالي الذي تستخدمه `invoices`. التغيير إضافي فقط ولا ينقل أو يحذف أو يعدّل أي صف قائم.
- **التحقق التطبيقي فقط:** مرفوض لأنه قابل للتجاوز بالكتابة المباشرة، ولا يضمن جميع مسارات الكتابة.

## ضمانات الترحيل وحدوده

1. لا يعدّل الترحيل أي صف موجود ولا يصحح mismatches تلقائيًا.
2. تمنع triggers إدخال Sale غير متوافقة، وتعديل `sales.offering_id/activity_id` إلى حالة غير متوافقة، وتعديل `offerings.activity_id` إذا كان سيكسر اتساق أي Sale مرتبطة.
3. يبقى أي mismatch تاريخي موجود قبل الترحيل كما هو حتى تتم مراجعته ومعالجته صراحةً؛ لا يجوز افتراض أن تشغيل migration وحده يصحح بيانات قديمة.
4. استعلام التدقيق قبل/بعد النشر:

```sql
SELECT s.sale_id, s.offering_id,
       s.activity_id AS sale_activity_id,
       o.activity_id AS offering_activity_id
FROM sales AS s
JOIN offerings AS o ON o.offering_id = s.offering_id
WHERE s.activity_id <> o.activity_id;
```

5. لا توجد قاعدة بيانات تشغيلية مرفقة بالمستودع؛ لا يمكن لهذا التغيير الادعاء بأنه فحص قاعدة بيانات خارجية أو سجلات الإنتاج. اختبارات الترحيل ستستخدم قاعدة قديمة مُعبّأة بسجلات sales وinvoices لإثبات أن migration تحفظ البيانات والمراجع.

## التحقق المطلوب

اختبارات قبول/رفض INSERT وUPDATE، تغيير Activity في Offering مع Sales تابعة، عدة Offerings لمنتج عبر Activities مختلفة، قاعدة موجودة تحتوي sales وinvoices، إعادة فتح القاعدة، بقاء مراجع invoices، ونجاح `PRAGMA foreign_key_check`.
