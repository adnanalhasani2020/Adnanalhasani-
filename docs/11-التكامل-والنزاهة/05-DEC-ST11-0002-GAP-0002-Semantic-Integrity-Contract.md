# DEC-ST11-0002 — GAP-0002 Semantic Integrity Contract

**Status:** DECIDED / APPROVED
**Baseline:** e354ddf71c2408e374a144bb4d926e43684b44f3
**Scope:** GAP-0002 only
**Implementation:** NOT INCLUDED IN THIS DECISION

## 1. Decision

اعتماد Runtime Semantic Authority كالعقد التنفيذي الوحيد لإثبات الحقيقة الدلالية عند حدود العمليات المحكومة، دون إنشاء Source of Truth تقني جديد أو افتراض تقنية تخزين بعينها.

### 1.1 Authoritative semantic source

المصدر authoritative لأي entity reference هو المجال المالك للحقيقة لذلك المفهوم كما هو محدد في سجل Source of Truth القائم في Architecture. لا تعتبر نسخة مشتقة، payload وارد، cache، audit record، أو UUID/type pair مصدراً للحقيقة.

يُعبّر التنفيذ لاحقاً عن ذلك عبر Semantic Authority contract؛ أما اختيار repository/database/storage فهو قرار تنفيذي لاحق ولا يُحسم هنا.

### 1.2 Semantic reference resolution

كل governed operation تحمل reference دلالياً من الشكل المفاهيمي:
(entity_type, entity_id, expected_owner_domain)

وعند boundary:
1. يتحقق runtime من الشكل الأساسي للمرجع.
2. يستدعي Semantic Authority للـentity_type مع entity_id.
3. يرفض المرجع إذا لم توجد entity authoritative.
4. إذا وجدت، يتحقق من owner domain المصرح به للعملية.
5. إذا كانت العملية تتطلب provenance، يتحقق من provenance وفق قاعدة العملية قبل السماح بالعبور.

لا يجوز اعتبار نجاح UUID/type validation نجاحاً في semantic resolution.

### 1.3 Existence enforcement

Entity not found = deterministic rejection.

لا ينشئ runtime entity افتراضية من reference غير معروف، ولا يعتبر payload الوارد دليلاً على وجود entity authoritative.

### 1.4 Ownership enforcement

Owner mismatch = deterministic rejection.

المالك يُستمد من authoritative semantic source، وليس من claim داخل operation payload. عبور الحدود بين المجالات لا ينقل ملكية الحقيقة.

### 1.5 Provenance enforcement

Provenance تكون required only where the governed operation specification marks it as required. عند كونها مطلوبة:
- يجب أن تكون reference إلى provenance authoritative قابلة للتحقق.
- يجب أن تكون provenance مرتبطة بالكيان/الأثر الذي تحكمه العملية.
- provenance المفقودة أو غير الصالحة أو غير المرتبطة = rejection.

وجود كائن Provenance صحيح شكلياً لا يكفي لإثبات provenance applicability.

### 1.6 Single runtime enforcement point

نقطة enforcement الوحيدة الموثوقة هي قبل semantic effect / state mutation للـgoverned operation داخل application runtime boundary، عبر بوابة واحدة تستدعي Semantic Authority ثم تطبق existence/ownership/provenance policy.

Domain constructors قد تستمر في validation البنيوي، لكنها ليست enforcement point للحقيقة الدلالية.

## 2. Rejected alternatives

- UUID/type as truth: مرفوض لأنه يثبت الشكل فقط.
- Payload as source of truth: مرفوض لأنه يسمح للمدخلات بتقرير وجود/ملكية الكيان.
- Derived cache/view as authority: مرفوض لأنه يخالف Source-of-Truth principle.
- Audit log as authority: مرفوض؛ Audit يسجل الأثر ولا يملك حقيقة المجال.
- Per-domain ad-hoc checks: مرفوضة كنقطة enforcement نهائية لأنها تسمح بتباين القواعد وتجاوزها.
- اختيار DB/SQLite/ORM الآن: مرفوض لأنه يحسم تقنية لم يعتمدها Architecture/Data Model.

## 3. Architecture / Data Model boundary

هذا القرار يعتمد على مبادئ الملكية الحالية ولا يغير مالك أي domain. التنفيذ اللاحق يحتاج فقط إلى Semantic Authority contract ونقاط ربطه. إذا تطلب ذلك إدخال علاقة durable جديدة بين domains أو provenance relation غير موجودة دلالياً، يجب تسجيل Decision Dependency منفصلة قبل التنفيذ.

لا يعتمد هذا القرار schema أو migration أو storage technology.

## 4. Approval for implementation

**GAP-0002 = READY FOR IMPLEMENTATION**, بشرط أن يلتزم التنفيذ بهذا العقد ولا يوسّع نطاقه إلى GAP-0006/0001/0003/0004.
