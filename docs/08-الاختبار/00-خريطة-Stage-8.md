# خريطة Stage 8 — الاختبار والتحقق

**الحالة:** مفتوحة رسمياً — تنفيذ دفعات اختبار Stage 8 جارٍ؛ Batch 5 منفذة، والحكم النهائي مرتبط بـCI على commit الدفعة نفسه.

## 1. الهدف
إنشاء إطار تحقق مستقل بعد التنفيذ ينقل المشروع عبر:
**Implementation → Test → Evaluation → Release**

المرحلة لا تعيد تعريف Domain Truth أو المتطلبات أو المواصفات، ولا تغيّر تنفيذ Stage 7.

## 2. المخرجات
- خريطة الاختبار والتحقق.
- نطاق الاختبار وحدوده.
- منهج الاختبار والتحقق والتقييم.
- بوابة الدخول والخروج.
- سجل الدفعات المتوقع.
- سجل القرارات والمسائل الممنوع حسمها تقنياً أو وظيفياً قبل الاختبار.
- نتائج الاختبارات والمراجعات لكل دفعة.
- Achievement فقط عند اكتمال مخرج متحقق فعلياً.

## 3. سلسلة التتبع
**REQ → SPEC → EXEC → Implementation → TEST → Evaluation → Release**

يبقى TEST تابعاً لما تم تنفيذه فعلياً، ولا يُستخدم لتعديل معنى REQ/SPEC ضمنياً.

## 4. الحالة
- Stage 7: **CLOSED**.
- Stage 8: **OPEN**.
- Stage 9: **لم تُفتح**.
- DEC-0001..DEC-0013: **OPEN**.
- لا تغييرات على Stage 3–7 ضمن فتح Stage 8.

## 5. حدود الفتح
هذا الفتح توثيقي وحوْكمي فقط؛ لا ينشئ اختبارات جديدة، ولا يختار framework اختبار نهائياً، ولا يعلن أي نتيجة Test/Evaluation جديدة.

## 6. TEST-0005 — Reliability, Edge Cases & State Consistency
- Baseline: `a41a7e3480365bd729439073ba8deaf08bbfd03c`.
- الاختبارات الجديدة: **45**؛ إجمالي suite المتوقع: **211**.
- النطاق: القيم الفارغة/المفقودة/غير الصالحة، الحدود والقيم القصوى، lifecycle/state boundaries، duplicate/replay، correction/cancellation/reversal، التاريخ، Source of Truth، Pending/Conflict، والعزل العابر للمجالات.
- GAP-0005 وGAP-0006 اختُبرا بصرامة كسلوك حالي؛ لا Idempotency Engine ولا Identity Registry.
- لا تغييرات على Stage 3–7، ولا DEC changes، ولا Stage 9.
