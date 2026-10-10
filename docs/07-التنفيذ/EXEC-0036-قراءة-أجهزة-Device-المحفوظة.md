# EXEC-0036 — قراءة أجهزة Device المحفوظة

## الهدف
توفير مسار قراءة فقط لسجلات `devices` الموجودة في المخطط العلائقي الحالي، دون إنشاء أجهزة أو تعديلها أو تغيير حالة العمليات المعلقة.

## عقد البيانات
يعيد `DeviceReadApplication` الحقول الفعلية في الجدول: `device_id`, `device_fingerprint_ref`, `state`, `person_id`, `access_account_id`, `created_at`, `updated_at`, `provenance_id`, و`version_no`.
- `device_id` مفتاح أساسي.
- `person_id` و`access_account_id` مرجعان اختياريان مستقلان إلى جدولي `persons` و`access_accounts`.
- حالات المخطط المقبولة: `active`, `lost`, `revoked`, `retired`.
- لا يتطلب التنفيذ ترحيلاً جديداً؛ يعيد الحالة المحفوظة كما هي ولا يستنتج منها أي صلاحية.

## الواجهة
- `get(connection, device_id)`: استعادة جهاز محدد؛ المعرّف غير الصالح أو الجهاز غير الموجود يرفع `ValidationError`.
- `list_for_person(connection, person_id)`: سرد الأجهزة ذات مرجع Person المطابق فقط.
- `list_for_access_account(connection, access_account_id)`: سرد الأجهزة ذات مرجع Access Account المطابق فقط.
- القائمتان ترتبان النتائج حسب `created_at, device_id` وتعيدان tuple فارغة عند عدم وجود تطابق.

## الاختبارات
`tests/test_stage48_device_read_application.py` يغطي استعادة كل الحقول بما فيها المراجع الاختيارية، حالات UUID غير الصالحة، التمييز بين الجهاز غير الموجود والقائمة الفارغة، العزل حسب Person وحسب Access Account، الترتيب الحتمي، إعادة فتح قاعدة البيانات، وعدم تغير الصفوف نتيجة القراءة.

## الحدود
قراءة فقط. لا إنشاء جهاز أو تغيير حالة، ولا مصادقة أو جلسات أو تفويض أو صلاحيات، ولا تنفيذ أو إلغاء أو تعديل Pending Operations، ولا إنشاء سجلات مترابطة تلقائياً.
