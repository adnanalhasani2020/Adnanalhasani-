# مراجعة دفعة 5 — Offline / Multi-Device / History / Privacy

## الحالة

**PASS — جاهزة للمراجعة البشرية المباشرة.**

المرجع المعتمد قبل الدفعة:
`9fc420ba92eb958ba099d58a97e38c69ec4437f6`

النطاق:
1. Offline / Multi-Device
2. Pending Operations / Conflicts / Finality
3. History / Correction / Lifecycle
4. Privacy / Classification / Purpose / Retention / Deletion

**لا يوجد مفهوم دوميني جديد في Batch 5.**

---

## 1. Offline / Multi-Device

تم تثبيت:

- Offline Operation ≠ Domain Finality.
- Device ≠ Source of Truth.
- Pending Operation ≠ Domain Truth.
- Conflict ≠ Source of Truth ثالث.
- Device state ≠ Domain state.
- Session ≠ Authorization.

يمكن أن تبدأ العملية محلياً وتبقى Pending، أو تنتظر تحقق Owner Domain، أو تتطلب تأكيد سلطة قبل اعتبارها نهائية. لم يُفرض Online أو Offline كقاعدة شاملة.

لا يتضمن النموذج قراراً حول CRDT أو OT أو Event Sourcing implementation أو Sync Protocol أو Message Broker أو Database Replication.

---

## 2. Finality Matrix

| العملية/المجال | التصنيف المفاهيمي | قاعدة النهائية |
|---|---|---|
| Identity correction | D | يتأثر بسياق الهوية وDEC المفتوح؛ لا يثبت محلياً كقاعدة عامة |
| Access change | D | يحتاج قواعد Access/Authority المناسبة |
| Membership / Role change | D | يعتمد على Owner Domain وسياق السلطة |
| Inventory | B | يمكن بدء التعديل Offline؛ Inventory Owner يثبت الحقيقة النهائية |
| Sale | B | يمكن إنشاء محاولة محلية؛ Commerce يثبت الحالة النهائية |
| Payment | B/C | يمكن بدء Payment محلياً؛ finality المالية لا تثبت محلياً |
| Settlement | C | التسوية النهائية تحتاج اعتراف المجال المالي المناسب |
| Ledger Entry | C | الاعتراف المالي النهائي بيد Finance |
| Clinical Record | B | يمكن إنشاء/تعديل محلياً؛ Health يثبت Clinical Truth |
| Result/Report | B | يمكن الإرسال/التسجيل محلياً؛ Health يثبت الحالة النهائية |
| Prescription | B/C | يمكن إعدادها محلياً؛ النهائية تتبع السلطة الصحية المطلوبة |
| Family Relationship | D | قد تتأثر بقواعد إثبات/سياق قانوني مفتوح |
| Delegation | B/C | قد يبدأ الطلب محلياً؛ المنح/السحب النهائي بيد سلطة Authorization |
| Authorization Grant | C | السلطة النهائية لا تثبت محلياً لمجرد وجود نسخة |
| Approval | C | قرار الموافقة النهائي يحتاج اعتراف جهة القرار عند انطباق ذلك |
| Conversation | A/B | يمكن إنشاء/تحديث سياق محلي، مع بقاء الحالة النهائية حسب Communication |
| Message | A/B | يمكن composition/send attempt محلياً؛ delivered ليست نتيجة محلية مفترضة |
| Agent Action | B/C | يمكن preparation/submission محلياً؛ الأثر النهائي بيد Owner Domain |

**D** = يعتمد على المجال أو DEC مفتوح.

هذه مصفوفة دلالية وليست سياسة اتصال شبكي.

---

## 3. Multi-Device

يمكن للشخص استخدام عدة أجهزة دون إنشاء Person جديد.

العلاقات المهمة:
- Device — Access Account
- Device — Session
- Device — Pending Operation
- Pending Operation — Domain Object
- Pending Operation — Conflict
- Conflict — Owner Domain

في تعارض Device A وDevice B:
1. العمليات المحلية قد تكون Pending.
2. Conflict يصف عدم التوافق.
3. Owner Domain يملك الحقيقة.
4. لا يصبح الجهاز أو العملية أو Conflict مصدراً بديلاً.
5. لا تحدد الدفعة خوارزمية الحسم.

فقد Device أو سحب الوصول منه لا يمحو Person ولا Domain History.

---

## 4. Conflict

Conflict ليس مالكاً للحقيقة.

### المعنى

يمثل حالة تعارض بين عمليات/تمثيلات مرتبطة بحقيقة دومينية واحدة عندما لا يمكن اعتبارها متوافقة وفق قواعد المجال.

### الملكية والحسم

- Conflict يحتاج سياقاً/مجالاً لتصنيفه ومتابعته.
- Owner Domain للحقيقة المتعارضة يحسم معناها النهائي.
- لا ينشئ Conflict Truth ثالثة.
- ينتهي عندما يحسم التعارض أو يصبح غير ذي صلة وفق قواعد المجال.

Conflict أقرب إلى حالة مشتقة/تشغيلية من كونه Source of Truth دومينياً.

---

## 5. History / Correction

### Domain History

هو تاريخ تغير الحقيقة نفسها داخل Owner Domain.

لا يوجد Version Entity عام جديد.

قواعد أساسية:
- Correction ≠ Delete History.
- Revocation ≠ Erasure of the granting event.
- Expiry ≠ deletion of historical validity.
- Reversal/correction المالي لا يمحو الأثر المالي التاريخي المعترف به.
- Correction الصحي لا يمحو التاريخ السريري القابل للتفسير.
- تصحيح Message لا يعيد كتابة Domain Truth التي أشارت إليها.

### Lifecycle

المفاهيم الحالية تملك الحالات التي تحتاجها فقط؛ لا تُضاف حالة لمجرد التوحيد.

يمكن أن تظهر بحسب المجال:
- create / active
- amend / correct
- cancel / revoke
- expire / end
- reverse

ولا يلزم أن يدعم كل مفهوم جميع هذه الحالات.

---

## 6. Domain History vs Provenance vs Audit

| المفهوم | السؤال الذي يجيب عنه |
|---|---|
| Domain History | كيف تغيرت الحقيقة الدومينية نفسها؟ |
| Provenance | من أين جاءت المعلومة/كيف أُنتجت أو اشتُقت؟ |
| Audit Record | ما الذي يجب تسجيله لأغراض الرقابة والتدقيق؟ |

لا واحد منها بديل عن الآخر.

---

## 7. Privacy / Classification

تم اعتماد مبدأ تصنيف مشترك دون إنشاء كيان Privacy مستقل.

تصنيف عملي مفاهيمي مؤقت:
- Public
- Internal
- Sensitive
- Highly Sensitive / Restricted

ليس كل ما يخص المستخدم بنفس الحساسية.

أمثلة:
- Identity/Identifiers: قد تكون Sensitive.
- Health: عادة Highly Sensitive/Restricted.
- Finance: Sensitive أو Highly Sensitive بحسب السياق.
- Family/child data: قد تكون Sensitive أو Highly Sensitive.
- Messages: الحساسية تتبع المحتوى.
- Authorization/Delegation/Approval: قد تكون حساسة.
- Agent Action/Audit/Provenance: قد تكون حساسة بسبب كشف الفعل أو المصدر أو الوصول.

لا تُحسم التسميات النهائية أو المتطلبات القانونية عالمياً في هذه الدفعة.

---

## 8. Authorization vs Purpose

تم تثبيت التمييز:

**Authorization = هل توجد سلطة مناسبة؟**

**Purpose/Access Context = لماذا/في أي سياق يُستخدم الوصول؟**

وجود Authorization لا يعني أن كل استخدام للبيانات مناسب لكل غرض.

لم تتم إضافة Purpose ككيان مستقل؛ يبقى قيداً/سياقاً دلالياً حتى يثبت استقلاله.

---

## 9. Retention / Deletion

الاحتفاظ قيد دلالي يختلف حسب:
- المجال.
- نوع الحقيقة.
- الالتزام.
- الحاجة التاريخية.
- المتطلبات القانونية ذات الصلة.

بعد انتهاء الاحتفاظ، النتيجة قد تكون حذفاً فعلياً، حذفاً/تعطيلاً تشغيلياً، إخفاءً/تقييد وصول، أو استمرار الاحتفاظ.

لا يوجد قرار قانوني عالمي.

### قاعدة مهمة

- حذف Access Account لا يعني حذف Domain History.
- فقد/حذف Device لا يعني حذف Domain History.
- إزالة وصول لا تعني محو الحقيقة.
- حذف Message لا يعني حذف Clinical/Financial Truth التي أشارت إليها.
- السجلات الصحية/المالية/التدقيقية قد تتطلب احتفاظاً مستقلاً بحسب المجال والاختصاص.

---

## 10. Source of Truth

بعد Batch 5:

- Identity → Identity.
- Finance → Finance.
- Health → Health.
- Authorization → Authorization.
- Communication → Communication.
- Device/Pending/Conflict → ليست Domain Truth.
- History تبقى داخل Owner Domain.
- Provenance لا يملك الحقيقة.
- Audit لا يملك الحقيقة.
- Derived Data لا تصبح Truth ثانية.

لا يوجد تعارض جديد مع Batch 2 أو Batch 3 أو Batch 4.

---

## 11. السيناريوهات المفاهيمية

| # | السيناريو | النتيجة |
|---:|---|---|
| 1 | Device A ينشئ عملية محلية | Pending، لا Finality تلقائية |
| 2 | Device B يعدل نفس الحقيقة | قد ينشأ Conflict |
| 3 | تعارض جهازين على Inventory | Inventory Owner يحسم؛ Conflict لا يملك المخزون |
| 4 | تعارض على Authorization | Authorization Owner يحسم؛ Conflict ليس صلاحية ثالثة |
| 5 | تعارض صحي | Health يحسم Clinical Truth |
| 6 | تعارض مالي | Finance/Payments يحسم الحقيقة المالية |
| 7 | Pending Payment | لا يساوي Settlement أو Ledger Finality |
| 8 | Pending Prescription | لا تصبح Clinical Truth نهائية محلياً |
| 9 | Pending Approval | لا يثبت قراراً نهائياً بمجرد وجوده على Device |
| 10 | Message queued | لا تعني delivered |
| 11 | Lost Device | لا يحذف Person أو Domain History |
| 12 | Session انتهت | لا تحذف Authorization أو Domain Truth |
| 13 | تصحيح Person | لا يلزم محو التاريخ القابل للتفسير |
| 14 | سحب Identifier | يحافظ على معنى الفترة السابقة |
| 15 | انتهاء Membership | لا يمحو العضوية التاريخية |
| 16 | تعديل Offering | لا يعيد تفسير Sale التاريخية تلقائياً |
| 17 | عكس Payment/Settlement | يبقى التاريخ المالي قابلاً للتفسير |
| 18 | تصحيح Ledger Entry | لا يعني حذف الأثر المالي المعترف به |
| 19 | تصحيح Clinical Record | لا يعني حذف التاريخ السريري |
| 20 | Result/Report amended | التعديل لا يمحو النسخة/التاريخ السابق دلالياً |
| 21 | Prescription revoked | لا يمحو واقعة الإصدار السابقة |
| 22 | Family Relationship ended | لا يمحو العلاقة التاريخية |
| 23 | Authorization revoked | يؤثر في المستقبل ولا يمحو واقعة المنح |
| 24 | Message corrected | لا تصبح Clinical/Financial Truth مختلفة تلقائياً |
| 25 | Audit Record | يوثق التدقيق ولا يملك Domain Truth |
| 26 | Provenance | يشرح الأصل ولا يملك الحقيقة |
| 27 | Authorization موجود لكن الغرض غير مناسب | وجود السلطة وحده لا يحسم Purpose/Context |
| 28 | انتهاء Retention | النتيجة تعتمد على المجال والالتزام والاختصاص |
| 29 | حذف Access Account | لا يمحو Health/Finance History المطلوبة |
| 30 | حذف Device | لا يمحو Domain History |
| 31 | Agent Action Offline | لا يثبت الأثر النهائي في Owner Domain |
| 32 | Approval Offline | Pending حتى الاعتراف النهائي حيث يلزم |
| 33 | Family/child data | الحساسية تعتمد على المحتوى والسياق وليست قاعدة موحدة لكل Family Relationship |
| 34 | Message تحتوي Health data | الحساسية تتبع المحتوى؛ Message لا تصبح Clinical Record |
| 35 | Derived Availability قديمة | لا تصبح Inventory Truth |
| 36 | Conflict انتهى | ينتهي التعارض دون امتلاك الحقيقة بعد ذلك |

**النتيجة: 36/36 PASS.**

---

## 12. مفاهيم لم تُضف

لم تتم إضافة:
- Version Entity عامة.
- Purpose Entity.
- Privacy Policy Entity.
- Conflict Resolution Entity.
- Sync Entity.
- Device Truth.
- Deletion Record كحقيقة دومينية عامة.

السبب: لا يوجد في هذه الدفعة ما يثبت استقلالاً دلالياً ضرورياً لهذه المفاهيم.

---

## 13. مؤجل عمداً

تم تأجيل:
- التفاصيل القانونية الدقيقة للاحتفاظ والحذف.
- قواعد القاصر/الوصاية حسب الاختصاص.
- تحديد أسماء تصنيفات الخصوصية النهائية.
- قواعد نهائية لكل مجال عندما تكون مرتبطة بقرارات DEC المفتوحة.
- أي اختيار لخوارزمية أو بروتوكول مزامنة.

هذه النقاط لا تمنع اكتمال النموذج المفاهيمي الحالي.

---

## 14. التتبع

**59/59 = 100%.**

لا متطلبات جديدة، ولا تغيير إلى Approved.

تم تعميق المتطلبات المتصلة بـ:
- Multi-device / Session / Pending / Conflict.
- Financial finality.
- Health finality.
- Family / Authorization.
- History / Correction.
- Privacy / Purpose / Retention.
- Provenance / Audit.

---

## الحكم

**PASS — Batch 5 جاهزة للمراجعة البشرية المباشرة.**

- لا مفهوم دوميني جديد.
- 36/36 سيناريو PASS.
- 59/59 متطلباً قابلاً للتتبع.
- Source of Truth متسق مع Batches 1–4.
- Offline/Pending/Conflict لا تملك Domain Truth.
- Correction لا يمحو History.
- Provenance/Audit منفصلان عن Domain History.
- Privacy/Retention بقيتا قيوداً مفاهيمية، دون Privacy Engine أو قواعد قانونية عالمية.
- Stage 5 تبقى مفتوحة.
- لا Stage 6.
- لا ACH إغلاق.
