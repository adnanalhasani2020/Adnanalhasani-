# DEC-ST11-0003 — GAP-0005 Durable Operation Integrity Contract

**Status:** DECIDED / APPROVED
**Baseline:** e354ddf71c2408e374a144bb4d926e43684b44f3
**Scope:** GAP-0005 only
**Implementation:** NOT INCLUDED IN THIS DECISION

## 1. Decision

اعتماد Durable Operation Authority كمصدر الحسم الوحيد لهوية العملية وحالتها وإعادة التشغيل والتعارض، مع فصل العقد الدلالي عن اختيار تقنية التخزين.

### 1.1 Persistence boundary

كل governed operation تعبر من خلال durable operation authority قبل إحداث semantic effect. هذه السلطة تحتفظ بالحالة اللازمة لإثبات أن operation identity سبق قبولها وما كانت نتيجتها.

الـpersistence boundary هو operation-integrity state فقط؛ لا يقرر ملكية domain state ولا يحل محل domain Source of Truth.

### 1.2 Operation identity model

العملية الدائمة لها:
- operation_id: معرف ثابت للعملية، يحدده caller وفق contract ولا يتغير أثناء replay.
- operation_kind: النوع الدلالي للعملية.
- actor/context: السياق المحكوم اللازم لتفسير العملية.
- request_fingerprint: بصمة canonical للمدخلات المؤثرة في النتيجة.
- outcome: النتيجة المعتمدة للعملية بعد قبولها.
- status: الحالة المحكومة اللازمة للتمييز بين accepted/completed/rejected كما يحدد التنفيذ.

### 1.3 Operation key

Operation key = operation_id within the governed operation namespace.

لا يُسمح بإعادة استخدام نفس key لعملية ذات semantics مختلفة.

الـfingerprint لا يعرّف العملية؛ بل يثبت ما إذا كان نفس identity أُعيد تقديمها بنفس المدخلات المؤثرة.

### 1.4 Durable replay state

الحالة authoritative هي record durable واحد لكل operation key داخل namespace المحكوم، ويجب أن تبقى قابلة للقراءة بعد process/runtime restart.

يجب أن يحفظ record على الأقل:
operation_id + namespace + operation_kind + request_fingerprint + outcome/status.

### 1.5 Duplicate semantics

إذا وصل نفس operation key وكانت request_fingerprint مطابقة للسجل durable:
- لا يُنفذ semantic effect مرة ثانية.
- يعاد نفس outcome المعتمد أو equivalent deterministic replay result.
- يُصنف الطلب DUPLICATE / IDEMPOTENT REPLAY.

هذا هو السلوك المطلوب سواء كان replay داخل نفس runtime أو بعد restart.

### 1.6 Conflict semantics

إذا وصل نفس operation key لكن اختلفت operation_kind أو request_fingerprint أو أي حقل identity-defining داخل namespace:
- لا يعاد تنفيذ العملية.
- لا يُستبدل السجل السابق.
- يُرفض الطلب كـ CONFLICT مع السجل authoritative.

Conflict ليس duplicate، ولا يجوز حلّه تلقائياً بإنشاء operation identity بديلة.

### 1.7 Idempotency authority

Durable Operation Authority هو نقطة الحسم الوحيدة.

In-memory sets، audit logs، caller-side checks، أو domain_exceptions.is_duplicate() لا تعتبر authority ولا تكفي لإثبات replay safety.

### 1.8 Single runtime enforcement point

نقطة enforcement الوحيدة الموثوقة هي قبل semantic effect / state mutation للـgoverned operation داخل application runtime boundary، عبر بوابة واحدة:
1. canonicalize identity-defining input;
2. lookup durable operation record;
3. classify first submission / duplicate / conflict;
4. atomically reserve/record the operation before allowing its semantic effect;
5. return stored deterministic outcome on duplicate.

تفاصيل transaction/locking mechanism تُترك للتنفيذ والتقنية المعتمدة لاحقاً.

## 2. Rejected alternatives

- In-memory duplicate set: مرفوض لأنه يفشل بعد restart.
- Audit log as replay authority: مرفوض لأنه سجل تدقيق وليس operation state authority.
- Request fingerprint alone as identity: مرفوض لأنه يخلط بين identity وإعادة الإرسال.
- Caller-generated timestamps as operation identity: مرفوضة لأنها غير مستقرة وغير حاسمة.
- اختيار SQLite/DB/ORM الآن: مرفوض لأنه يحسم storage technology قبل القرار التقني المطلوب.

## 3. Architecture / Data Model boundary

هذا القرار يقر فقط بوجود durable operation-integrity concept وعقده الدلالي. لا يختار schema أو DB أو migration أو transaction technology.

قبل implementation يجب ترجمة هذا العقد إلى Data Model/Architecture artifacts مناسبة. أي قرار تقني متعلق بالتخزين أو atomicity/transaction mechanism يبقى ضمن TD-001 أو Decision Dependency تقنية مستقلة إذا ظهر أنه غير قابل للحسم ضمن التنفيذ.

## 4. Approval for implementation

**GAP-0005 = READY FOR IMPLEMENTATION**, بشرط عدم توسيع التنفيذ إلى GAP-0006/0001/0003/0004.
