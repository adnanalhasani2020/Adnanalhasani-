# تكامل Supabase مع النواة التجارية

## الوضع الحالي

تخزين تطبيق `commercial_web` الحالي مبني على SQLite عبر `connect_database`. ملف `src/agent_core/supabase_rpc.py` يضيف ناقل RPC اختياريًا لـSupabase، ولا يغيّر مسارات البيع أو المخزون أو جلسات الهوية المحلية. هذا الفصل مقصود حتى لا نخلط حسابات SQLite مع جلسات Supabase أو نعرض بيانات متجر لآخر.

## الإعداد

في بيئة تشغيل آمنة، عيّن:

- `SUPABASE_URL`: عنوان مشروع Supabase باستخدام HTTPS.
- `SUPABASE_ANON_KEY`: المفتاح العام للمشروع (anon/publishable)، وليس `service_role`.

يجب تمرير access token صادر عن جلسة مستخدم مسجّل الدخول عند إنشاء `SupabaseRPCClient`. لا تضع access tokens أو مفاتيح المشروع في المستودع أو سجلات التطبيق. لا تستخدم `SUPABASE_ACCESS_TOKEN` كقيمة ثابتة في الإنتاج.

## مثال إنشاء مؤسسة

```python
from agent_core.supabase_rpc import SupabaseRPCClient

client = SupabaseRPCClient(access_token=user_access_token)
organization_id = client.create_organization(
    name="مؤسستي",
    slug="my-organization",
    organization_type="business",
    currency_code="YER",
    timezone="Asia/Aden",
    locale="ar",
    country_code="YE",
    module_keys=None,  # الوحدات الافتراضية المعتمدة من قاعدة البيانات
)
```

العميل يستدعي `commercial_create_organization` عبر PostgREST باستخدام JWT المستخدم؛ صلاحيات RPC وRLS في قاعدة البيانات تبقى المرجع. `module_keys=None` يترك اختيار الوحدات الافتراضية للدالة. لا تعتمد على قيمة UUID المعادة إذا انقطع الاتصال قبل وصول الاستجابة؛ تحقق من الحالة قبل إعادة المحاولة.

## نقطة API المتاحة

أضيف المسار `POST /api/supabase/organizations` إلى `commercial_web`. يتطلب ترويسة `Authorization: Bearer <Supabase user access token>` وجسم JSON يحتوي `name`, `slug`, `organization_type`, `currency_code`, `timezone`, `locale`، ويمكن أن يحتوي `country_code` و`module_keys`. يستدعي المسار RPC الذرية ويعيد `201` مع `organization_id` عند نجاح الاستدعاء. إذا كان التوكن مفقودًا يعيد `401`.

مثال جسم الطلب:

```json
{
  "name": "مؤسستي",
  "slug": "my-organization",
  "organization_type": "business",
  "currency_code": "YER",
  "timezone": "Asia/Aden",
  "locale": "ar",
  "country_code": "YE",
  "module_keys": null
}
```

## ما لم يُربط بعد

- عند ضبط `SUPABASE_URL` و`SUPABASE_ANON_KEY`، تستخدم واجهة `commercial_web` الآن Supabase Auth للبريد وكلمة المرور، ثم تنشئ جلسة تجارة محلية مرتبطة بمعرّف مستخدم Supabase. إذا لم يُضبط المتغيران، تبقى آلية SQLite المحلية متاحة للتطوير فقط. هذا لا يربط بيانات البيع والمخزون نفسها بـSupabase.
- لا توجد مزامنة تلقائية بين SQLite وSupabase، ولا ينبغي افتراض أن المبيعات المحلية تظهر في جداول Supabase.
- لا تُحوّل مسارات البيع والمخزون والمرتجعات أو الحسابات المالية إلى Supabase قبل إضافة مصادقة موحدة، وتحديد مصدر الحقيقة، وتغطية اختبارات العزل بين المؤسسات.
- اختبارات `tests/test_supabase_rpc.py` وحدات محلية بمكالمات HTTP وهمية؛ لا تثبت الاتصال الحي أو نجاح onboarding في جلسة فعلية.

## بوابة ما قبل الإنتاج

1. إضافة موفر تسجيل دخول Supabase حقيقي للواجهة أو طبقة API، مع تدوير/تجديد آمن للتوكنات.
2. اختبار `commercial_create_organization` في بيئة اختبار بمستخدمين منفصلين.
3. اختبار عزل مؤسستين، ورفض الوصول دون عضوية، وسلوك الأدوار المختلفة.
4. تحديد ما إذا كانت بيانات التجارة ستنتقل بالكامل إلى Supabase أو ستظل SQLite محلية؛ يمنع وجود مصدرين للحقيقة دون مزامنة مصممة ومختبرة.
5. تشغيل اختبارات pytest واختبارات قاعدة البيانات في CI وتسجيل الالتزام الذي جرى اختباره.

## تسجيل الدخول التجاري عبر Supabase

عند ضبط SUPABASE_URL وSUPABASE_ANON_KEY في بيئة تشغيل HTTPS، تستخدم شاشة إنشاء الحساب وتسجيل الدخول البريد الإلكتروني وكلمة المرور عبر Supabase Auth (/auth/v1/signup و/auth/v1/token?grant_type=password). لا تُرسل كلمة المرور إلى مخزن SQLite ولا تُستخدم مفاتيح service_role.

- إذا كان تأكيد البريد مفعّلًا في Supabase، يعرض التطبيق رسالة تطلب تأكيد البريد قبل تسجيل الدخول.
- بعد نجاح مصادقة Supabase، ينشأ ملف تجارة محلي مرتبط بمعرّف مستخدم Supabase في جدول commercial_supabase_identities، وتُصدر جلسة تطبيق محلية قابلة للإلغاء. كلمة المرور المحلية عشوائية وغير مستخدمة للمصادقة.
- إذا تم ضبط أحد متغيري Supabase فقط أو كان عنوانه غير صالح، يفشل المسار بدل الرجوع بصمت إلى تسجيل الدخول المحلي.
- لأغراض التطوير والاختبارات فقط، إذا لم يُضبط أي من متغيري Supabase، تبقى آلية SQLite المحلية القديمة متاحة. يجب ضبط المتغيرين في بيئة النشر حتى لا تستخدم تلك الآلية.

**حدود هذا الإنجاز:** بيانات المتجر والمنتجات والمخزون والبيع والإيصالات ما زالت تقرأ وتكتب في SQLite. هذه الخطوة توحّد موفر بيانات اعتماد تسجيل الدخول فقط؛ لا تعني ترحيل البيانات التجارية إلى Supabase ولا تفعيل المزامنة. جلسة التجارة المحلية مستقلة بعد تسجيل الدخول، لذلك يجب إكمال سياسة تجديد/إبطال الجلسات وربط بيانات التجارة قبل اعتبار التكامل جاهزًا للإنتاج.
