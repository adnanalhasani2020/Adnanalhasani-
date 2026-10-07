# ACH-0016 — تفعيل التحقق الآلي لاختبارات Stage 7

## الحالة
منفذ — تم إنشاء CI والتحقق من تعريفه، دون ادعاء نجاح تشغيل GitHub Actions قبل ظهور نتيجة تشغيل فعلية.

## النطاق
إضافة GitHub Actions workflow للتحقق الآلي من اختبارات Stage 7، دون تعديل منطق التنفيذ لمعالجة فشل الاختبارات.

## المنجز
- Workflow باسم Stage 7 Automated Tests تحت .github/workflows/stage7-tests.yml.
- التشغيل عند push إلى main وعند pull_request إلى main.
- Ubuntu runner.
- Python 3.11.9 مثبت صراحةً.
- تثبيت المشروع من pyproject.toml.
- تثبيت pytest صراحةً لأنه غير موجود في dependencies الحالية.
- تشغيل مجموعة pytest الحالية كاملة.
- إضافة تحقق خفيف لاكتشاف واستيراد كل وحدات Python الحالية تحت src/agent_core/ مع استثناء __init__.py فقط.
- توثيق تفسير PASS/FAIL وحدود CI.

## الاختبارات
لم يتم تعديل test_stage7_batch1.py أو test_batch2.py أو test_batch3.py أو test_batch4.py.

لا يوجد ادعاء مسبق بنجاح الاختبارات. إذا ظهر فشل في CI، يبقى ظاهراً ولا يُعالج بتعديل الاختبار لجعله PASS.

## حدود الحوكمة
- لا تغيير في Stage 3–6.
- لا DEC حُسمت.
- Stage 8 لم تُفتح.
- GitHub Actions مصدر تحقق آلي للاختبارات، وليس Domain Truth.
- لا تمت إضافة Domain Concept أو Relationship جديد.

## التحقق
تم فحص تعريف الـworkflow ومطابقته لنطاق المهمة. لا تُسجّل نتيجة PASS فعلية لـGitHub Actions إلا من تشغيل CI نفسه.

## Stage status
Stage 7 ما زالت مفتوحة. Stage 8 لم تُفتح.
