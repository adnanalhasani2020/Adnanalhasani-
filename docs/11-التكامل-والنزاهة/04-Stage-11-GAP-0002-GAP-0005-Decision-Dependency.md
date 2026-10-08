# Stage 11 — GAP-0002 / GAP-0005 Decision Dependency

**Status:** BLOCKED PENDING EXPLICIT DECISION  
**Stage 11 Gate:** `d3be7907e34dd75b13de7aa14676d328ab45203e`  
**Implementation baseline:** `b1ea7e30b9a0c7409bb2543b212308e6df4bc946`

## 1. Finding

فحص التنفيذ الحالي يثبت أن النموذج الحالي **structural/in-memory only**:

- domain entities validate UUID/type shape but do not resolve references against a runtime Source of Truth.
- لا توجد repository/store/runtime registry تسمح بإثبات semantic existence أو ownership عند عبور boundary.
- Provenance موجودة ككيان منفصل مع validation للحقول، لكنها ليست مرتبطة بحدود runtime operation بحيث يمكن فرض provenance validity عند الحاجة.
- لا توجد طبقة persistent operation store أو durable operation identity mechanism.
- `domain_exceptions.is_duplicate()` يكتشف duplicate فقط داخل iterable يمرره المستدعي؛ لا يوفر persistent replay/idempotency semantics عبر runtime restart.

## 2. GAP-0002 Dependency

لتحقيق Acceptance Criteria المعتمدة لـGAP-0002 يلزم على الأقل وجود **runtime authority for semantic references** يمكنه:

1. إثبات أن الكيان المشار إليه موجود فعلاً.
2. إثبات أن الكيان ضمن ownership/domain boundary المسموح بها.
3. تطبيق provenance rule عند العمليات التي تتطلب provenance.

هذا يتطلب تعريف/اختيار runtime Source of Truth أو repository/registry contract وربطه بنقاط التنفيذ. إدخاله الآن سيغير abstraction المعماري الحالي من pure structural objects إلى runtime semantic resolution.

**Decision Dependency:** اعتماد architecture/runtime contract لمصدر الحقيقة وآلية semantic reference resolution قبل تنفيذ GAP-0002.

## 3. GAP-0005 Dependency

لتحقيق Acceptance Criteria المعتمدة لـGAP-0005 يلزم:

1. persistent operation identity.
2. durable storage/state authoritative for operation identities.
3. replay detection after runtime/process restart.
4. deterministic idempotency behavior.
5. explicit handling of conflicting reuse of an operation identity.

هذا غير ممكن من خلال الحالة الحالية وحدها؛ `is_duplicate()` لا يحقق persistence أو replay detection عبر restart.

**Decision Dependency:** اعتماد persistence boundary + operation identity model + durability semantics قبل تنفيذ GAP-0005.

## 4. Data Model Impact

إذا تم اعتماد أي من الآتي، فهو تغيير دلالي يجب أن يمر عبر Decision مستقل قبل التنفيذ:

- operation identity as a first-class durable concept;
- persisted operation/replay record;
- semantic reference/ownership registry relation;
- provenance relation/constraint required for runtime enforcement.

لا يتم إدخال أي من هذه التغييرات ضمن هذا التنفيذ دون قرار مستقل.

## 5. Implementation Status

**GAP-0002:** BLOCKED — no implementation started.  
**GAP-0005:** BLOCKED — no implementation started.

No `src/` or `tests/` changes are authorized by this dependency record.

## 6. Required Decision Before Resume

يجب حسم الحد الأدنى التالي:

- ما هو runtime Source of Truth للـsemantic existence/ownership؟
- أين تقع نقطة enforcement؟
- ما هو provenance rule المحدد لكل governed operation؟
- ما هو persistent boundary للـoperation identity؟
- ما هو operation key/conflict rule؟
- ما هو durable replay/idempotency state؟
- هل هذه التغييرات تُعتمد كـArchitecture/Data Model decision مستقل؟

بعد اعتماد القرار، يمكن استئناف الترتيب:
**Decision → Requirement → Specification → Implementation Target → Test Case → Evidence**

ولا ينتقل التنفيذ إلى GAP-0006 قبل إغلاق/تحديد حالة GAP-0002 وGAP-0005 وفق Evidence.
