# SPEC-0019 — Offline وPending وFinality

## 1. الغرض والنطاق
تحدد هذه المواصفة الحدود الدلالية للعمل دون اتصال والعمليات المعلقة والنهائية عبر المجالات، مع إبقاء Device وPending Operation حالات تشغيلية لا تملك Domain Truth.
تغطي REQ-0046 وREQ-NFR-0056 فقط ضمن الملكية الحالية، ولا تحسم سياسة offline المالية.

## 2. REQ Traceability والملكية
| REQ | Primary | Supporting | التغطية |
|---|---|---|---|
| REQ-FUNC-0046 | SPEC-0019 | — | فصل الحالة المحلية عن النهائية |
| REQ-NFR-0056 | SPEC-0019 | SPEC-0024 | حدود السلوك دون اتصال وقابلية استخدامه |
لا تغيّر هذه المواصفة Primary/Supporting ownership.

## 3. Dependencies
- SPEC-0005: Sale/Commerce finality boundary.
- SPEC-0008: Payment/Settlement boundary.
- SPEC-0010: Health/Clinical Truth boundary.
- SPEC-0017: Agent Action boundary.
- Domain Constraint: Owner Domain retains Domain Truth.
- External: DEC-0011 وDEC-0013.
لا تضيف هذه المواصفة Dependency جديدة.

## 4. المفاهيم المستخدمة
Device، Session، Pending Operation، State Record، Offline Operation، Domain Truth، Finality، Conflict، Agent Action، Sale، Payment، Settlement، Clinical Record، Financial Transaction، Ledger Entry، Inventory Position.
لا Replica/Copy أو Local Operation أو Resolution State كـConcepts جديدة.

## 5. Offline
**Offline ≠ Finality.**
Offline هي حالة تشغيلية قد تسمح بقراءة أو إنشاء عملية محلية، لكنها لا تمنح العملية وحدها صفة نهائية في Owner Domain.

يمكن تصنيف السلوك دلالياً:
- Offline-safe: قراءة أو إعداد مسودة لا تتطلب حقيقة متغيرة لحظة الفعل.
- Offline-possible-with-constraints: إنشاء عملية تبقى Pending.
- Online/authority-dependent: حالات تحتاج تحققاً أو اعترافاً من Owner Domain قبل اعتبارها نهائية.
- Decision-dependent: حالات تتأثر بـDEC-0011/DEC-0013 أو قواعد المجال الDECIDED / RESOLVED.

هذه تصنيفات دلالية وليست بروتوكول اتصال.

## 6. Pending Operation
Pending Operation تمثل محاولة/عملية لم تصل بعد إلى حالة Domain Finality.
**Pending Operation ≠ Domain Truth.**
يمكن أن تمثل:
- إعداد/إرسال Payment.
- تحديث Sale أو Inventory.
- إنشاء/تعديل Clinical Record.
- Agent Action أو Approval request.
لكنها لا تصبح الحقيقة النهائية لمجرد قبولها محلياً أو وضعها على Device.

## 7. Finality
Finality تعني أن Owner Domain اعترف بالحالة ضمن قواعده المعتمدة.
لا تستنتج finality من:
- وجود العملية على Device.
- queued/submitted محلياً.
- وصول العملية أولاً من جهاز معين.
- نجاح تنفيذ محلي.
- Approval محلية غير معترف بها عند الحاجة.
لا تحدد المواصفة آلية الاعتراف التقنية.

## 8. Health boundary
**Offline ≠ Final Clinical Truth.**
Clinical Record وResult/Report وPrescription تبقى Health truth.
Pending health operation أو Device لا تملكها.
الصحة لا تحصل على finality لمجرد العمل offline.
التعارض السريري يعود إلى Owner Domain الصحي، والتفصيل في SPEC-0020.

## 9. Financial boundary
**Offline ≠ Financial Finality.**
Payment/Settlement أو أي عملية مالية معلقة لا تصبح Ledger Entry أو Financial Transaction نهائية لمجرد وجودها محلياً.
Finance يبقى مالك Financial Transaction/Ledger Entry/Balance.
لا تحسم هذه المواصفة سياسة offline المالية أو شروط التسوية النهائية؛ DEC-0011 DECIDED / RESOLVED.

## 10. Commerce / Inventory boundary
Sale أو تحديث Inventory يمكن أن يبدأ offline حيث يسمح السياق، لكنه يبقى Pending إلى أن يعترف Owner Domain بالحالة النهائية.
Inventory Position تبقى Inventory truth.
Sale تبقى Commerce truth.
لا يحدد هذا الملف آلية حسم المخزون أو التزام البيع أثناء الانقطاع.

## 11. Agent / Approval boundary
Offline Agent Action أو Approval محلية لا تمنح Agent أو الشخص Domain Truth.
Agent يبقى ضمن Authorization.
Approval لا تنقل Domain Ownership.
Pending Agent Action ≠ Execution النهائي في المجال.

## 12. Failure / Exception boundaries
- Offline ≠ Finality.
- Pending Operation ≠ Domain Truth.
- Device ≠ Source of Truth.
- Local success ≠ Domain success.
- queued/submitted ≠ completed/final.
- lost device ≠ deleted history.
- conflict ≠ third truth.
- retry ≠ new Domain Truth تلقائياً.
- offline financial behavior remains decision-dependent.

## 13. History / Provenance / Audit
العمليات المحلية المعلقة قابلة للتتبع دلالياً دون اعتبارها Domain Truth.
Provenance يصف المصدر/الاشتقاق.
Audit Record يسجل ما يلزم للتدقيق.
Domain History تبقى عند Owner Domain.
لا Event Sourcing ولا استخدام Audit/Provenance كمصدر حقيقة بديل.

## 14. Privacy / Authorization
Offline availability لا تتجاوز Authorization أو حدود الحساسية.
قد تكون البيانات المخزنة محلياً حساسة، لكن هذه المواصفة لا تنشئ سياسة تخزين أو تشفير تقنية.
Agent لا يوسّع الصلاحية بسبب offline.

## 15. Multi-device boundary
وجود العملية على Device A أو B لا يحدد الفائز.
إذا وجدت عمليات متزامنة أو متعارضة، فإن Conflict هو حالة تحتاج معالجة، بينما Owner Domain يحسم Domain Truth.
لا يحدد هذا الملف خوارزمية conflict resolution.

## 16. Source of Truth
| الحقيقة | المالك |
|---|---|
| Sale/Invoice | Commerce |
| Inventory Position | Inventory |
| Payment/Settlement | Payments |
| Financial Transaction/Ledger Entry/Balance | Finance |
| Clinical Record/Result/Report/Prescription | Health |
| Authorization | Authorization |
| Agent Action | Agent/Governance ضمن حدوده |
| Device/Pending Operation/Conflict | تشغيلي/تابع للمجال وليس Domain Truth |

## 17. Invariants
1. Offline ≠ Finality.
2. Pending Operation ≠ Domain Truth.
3. Device ≠ Source of Truth.
4. Conflict ≠ third truth.
5. Health/Finance/Commerce do not gain finality merely from offline execution.
6. Owner Domain remains source of truth.
7. no CRDT.
8. no OT.
9. no Event Sourcing.
10. no Replication/Sync Protocol.
11. no financial offline policy is decided here.
12. DEC-0011 DECIDED / RESOLVED.
13. DEC-0013 DECIDED / RESOLVED.
14. لا Concept أو Relationship جديد.

## 18. Acceptance Criteria
- AC-01 local state can be represented as non-final.
- AC-02 pending operation is distinct from Domain Truth.
- AC-03 Device is not Source of Truth.
- AC-04 health/finance/commerce finality is not inferred from offline work.
- AC-05 lost-device state does not erase Domain History.
- AC-06 no CRDT/OT/Event Sourcing/Replication/Sync Protocol is specified.
- AC-07 DEC-0011/0013 are DECIDED / RESOLVED.
- AC-08 REQ-0046/0056 covered with current ownership.

## 19. Traceability
REQ-FUNC-0046 → Primary SPEC-0019.
REQ-NFR-0056 → Primary SPEC-0019، Supporting SPEC-0024.
لا تعيد المواصفة توزيع الملكية.

## 20. Open Decisions
DEC-0011 DECIDED / RESOLVED.
DEC-0013 DECIDED / RESOLVED.
Q-0007/Q-0020/Q-0022 تبقى ضمن نطاقها الأصلي.
لا قرار جديد ولا إغلاق DEC.

## 21. الحدود وما لا تحدده
لا CRDT/OT/Event Sourcing.
لا Replication/Sync Protocol.
لا financial offline policy.
لا DB/SQL/API/UI/Framework/Provider.
لا تبدأ SPEC-0020 كتفصيل خوارزمي.

## 22. الخلاصة
Offline حالة تشغيلية لا تمنح finality. Pending Operation وDevice لا يملكان Domain Truth، وHealth/Finance/Commerce تحتفظ بملكية الحقيقة النهائية. التعارضات وفقدان الجهاز تبقى حدوداً دلالية، دون اختيار خوارزمية أو بروتوكول.
