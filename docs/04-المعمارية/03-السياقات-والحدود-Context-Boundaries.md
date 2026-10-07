# السياقات والحدود Context Boundaries

## القاعدة
الحدود هنا **حدود مسؤولية ومعنى** وليست حدود خدمات أو عمليات نشر. عبور الحد لا ينقل ملكية الحقيقة.

## السياقات
Identity، Access، Activities، Commerce، Inventory، Financial Relations، Payments/Settlement، Health، Education، Family/Delegation، Communication، Discovery، Sales/Interaction Channels، Agents، Offline/Operational، Privacy/Security/Audit.

## مصفوفة العلاقات العابرة للحدود

| Domain A | Domain B | نوع العلاقة | ما الذي يعبر الحدود | من يملك الحقيقة |
|---|---|---|---|---|
| Identity | Access | اعتماد سياقي | مرجع الهوية وحالة الأهلية للوصول | Identity للهوية، Access لحالة الوصول |
| Commerce | Finance | التزام/أثر مالي | Invoice/Obligation/Transaction reference | Commerce للفواتير، Finance للأثر المالي |
| Health | Finance | خدمة مقابل أثر مالي | Service/Invoice reference محدود | Health للسجل السريري، Finance للأثر المالي |
| Family | Authorization | سلطة مشتقة محتملة | سياق العلاقة وشروط التفويض | Family للعلاقة، Authorization للسلطة |
| Agent | Domain | تنفيذ مفوض | طلب/فعل/نتيجة تنفيذ | المجال المستهدف للحقيقة، Agent لأثر الفعل |
| Discovery | Commerce | اكتشاف عرض | Offering/availability reference | Commerce للعرض، Discovery لنتيجة الاكتشاف |
| Channel | Sale | بدء تفاعل | سياق بدء العملية | Channel لبداية التفاعل، Commerce للبيع |
| Offline | Finality | علاقة تشغيلية لا ملكية | حالة معلقة/طلب حسم | المجال الأصلي للنهائية والحقيقة |

## حدود إضافية
- Activities ↔ Identity: Activities يملك علاقة الشخص بالنشاط؛ Identity يملك الشخص.
- Inventory ↔ Discovery: Inventory يملك حالة المخزون؛ Discovery يملك نتيجة التوفر.
- Finance ↔ Payments: Finance يملك الآثار المالية؛ Payments يملك حالة الدفع/التسوية ضمن نطاقه.
- Health ↔ Communication: Communication يملك الرسالة؛ Health يملك الحقيقة السريرية.
- Education ↔ Family: Education يملك السجل؛ Family يوفر سياق العلاقة الذي قد يدخل في Authorization.
- Authorization ↔ Agents: Authorization يحدد السلطة؛ Agent يستهلكها ولا ينشئها.

## Invariants العابرة للحدود
1. المرجع بين مجالين لا ينشئ نسخة حقيقة ثانية.
2. القراءة من مجال لا تعني حق الكتابة فيه.
3. الحدث/الإشعار لا يثبت ملكية الحالة التي يشير إليها.
4. Derived View ليست Source of Truth.
5. حدود المجال لا تُساوى مبكراً مع حدود الخدمات أو الوحدات التقنية.