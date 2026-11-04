# مرجع ملفات الكود — تطبيق متجري التجاري

**الحالة:** مرجع وصفي للكود الموجود في الفرع `main`، وليس شهادة جاهزية إنتاجية.  
**آخر مطابقة:** 2026-11-04.  
**النطاق:** ملفات `android-commercial/` فقط. لا يغطي هذا الملف كل ملفات المستودع متعدد التطبيقات.  
**قاعدة التوثيق:** عند التعارض، الكود والترحيلات والاختبارات الحالية أدلة على التنفيذ؛ وثائق الخطة تشرح المقصود، ولا تثبت أن ترحيلات Supabase مطبقة على البيئة الحية.

## 1. خريطة التنفيذ

- **واجهة Android:** Kotlin وJetpack Compose، ضمن الحزمة `com.adnanalhasani.commercial`.
- **الوصول السحابي:** `CommercialCloudRepository.kt` يحتوي طلبات Supabase، قراءة البيانات التجارية، وعمليات الكتابة التي تستدعي RPCs.
- **الشاشات:** تعرض البيانات وتجمع مدخلات المستخدم؛ لا ينبغي اعتبار إخفاء زر أو تعطيله بديلًا عن صلاحيات قاعدة البيانات.
- **السلطة النهائية:** المصادقة، العضوية، صلاحيات الدور، القيود، الذرية، ومنع التكرار يجب أن تُفرض في Supabase/RPC/SQL. الواجهة وحدها لا تثبت هذه الضمانات.
- **الأثر المالي والمخزني:** لا يعني نجاح الشاشة أو RPC وحده أن دفتر الأستاذ، الذمم، المخزون، والاسترداد متطابقة من البداية إلى النهاية؛ راجع بوابات القبول في `commercial-documents-implementation-plan.md` وتقارير `docs/audits/`.

## 2. ملفات Kotlin ومسؤولياتها

| الملف | المسؤولية الموثقة | حدود يجب مراعاتها |
|---|---|---|
| `MainActivity.kt` | نقطة دخول Android فقط؛ يهيئ `ComponentActivity` ويستدعي `StoreApp`. | لا تضاف إليه حالة الشاشات أو التنقل أو تحميل بيانات المتجر؛ هذه مسؤولية `StoreApp.kt`. |
| `StoreApp.kt` | تركيب التطبيق وحالة التنقل والسياق السحابي وتحميل بيانات المتجر ومسارات الدخول والميزات. | يحافظ على حاجز منع خلط المحلي بالسحابي؛ الشاشات المساعدة أصبحت مكونات مستقلة وتُستدعى عبر نفس callbacks. |
| `CommercialCloudRepository.kt` | مساعدات سياق المؤسسة والجلسة والصفحات، خيارات العملاء، وقراءات السجلات التشغيلية المسموح بها. | لا يحمل الآن منطق الدليل أو الوحدات أو المخزون أو المشتريات أو المبيعات أو الذمم أو البوابة أو الأستاذ؛ راجع المستودعات المتخصصة. لا تنشئ عميل Supabase أو مخزن جلسة آخر. |
| `CommercialCloudSaleComposerScreen.kt` | محرر فاتورة البيع السحابي متعدد الأصناف، اختيار وحدة البيع، إدخال الكمية ومراجعة الإجماليات قبل الإرسال. | كمية السطر تقبل عددًا صحيحًا فقط في النسخة التي تمت مراجعتها؛ لا توصف واجهة البيع بأنها تدعم الكسور من طرف إلى طرف. تحقق الخادم هو الحاكم للسعر والتوفر. |
| `CommercialCloudSalesScreen.kt` | قائمة مستندات البيع السحابية وعرض التفاصيل، ومشاركة/طباعة ملخص الفاتورة المحفوظة. | الطباعة والمشاركة تعرضان نتيجة موجودة؛ لا تُعدّان دليلًا مستقلًا على سلامة القيود أو المخزون. |
| `CommercialCloudDebtScreen.kt` | عرض ذمم العملاء وتسجيل التحصيل الجزئي وعرض بيانات السند المرتبط. | الرصيد المعروض يجب أن يراعي التحصيلات والاستردادات والمرتجعات المعتمدة. فحص تعريف دالة التحصيل الحية في 2026-11-03 أكد وجود حارس الرصيد بعد المرتجعات؛ لا يزال اختبار القبول المالي من جلسة Android مصادَق عليها مطلوبًا قبل اعتماد الدورة كاملة. يجب عدم إعادة إدخال قراءة جدول الاستردادات الداخلي من العميل. |
| `CommercialCloudReturnsScreen.kt` | عرض طلبات إرجاع المبيعات، إدخال/مراجعة بنود الإرجاع، قبول أو رفض الطلب، وعرض حالة الفحص وإعادة التخزين والاسترداد. | طلب المرتجع، الموافقة، إعادة المخزون، والاسترداد المالي حالات مستقلة. قبول الطلب لا يثبت تنفيذ الاسترداد؛ إعادة التخزين تحتاج موقعًا صالحًا وربطًا مخزنيًا. |
| `CommercialCloudReturnRefund.kt` | تنفيذ طلب الإجراء الخاص بالاسترداد/إجراء المرتجع من خلال طلب خادمي محمي. | لا تتجاوز مسار RPC ولا تكتب إلى جداول الاسترداد الداخلية مباشرة من التطبيق. حالة الاسترداد يجب أن تأتي من نتيجة خادمية موثوقة. |
| `CommercialCloudPurchasesScreen.kt` | واجهة أوامر الشراء ومستنداتها وخطواتها المتاحة للمستخدم، مع إظهار حالة المستند والتنبيهات عند وجود بنود غير مربوطة بعروض صالحة. | وجود أمر شراء لا يثبت اكتمال الاستلام أو فاتورة المورد أو الالتزام أو الدفع أو القيد. راجع خطة المستندات واختبارات دورة الشراء. |
| `PurchaseOrderEntryDialog.kt` | جمع مدخلات إنشاء/تحرير أمر الشراء وبنوده من الواجهة. | التحقق النهائي من الكميات والسياق والتكرار وصلاحية البنود يجب أن يتم في الخادم/قاعدة البيانات. |
| `PurchaseReceiptEntryDialog.kt` | جمع بيانات استلام الشراء وإرسالها عبر مسار الاستلام. | الاستلام الجزئي والكميات الكسرية والربط بالمخزون تحتاج اختبارًا على حدود قاعدة البيانات، ولا يكفي نجاح إغلاق الحوار. |
| `PurchaseLineOfferingLinkDialog.kt` | ربط بند أمر الشراء بعرض/صنف تجاري مناسب. | يجب التحقق من صلاحية العرض والنشاط والوحدة قبل تسجيل الفاتورة أو الاستلام. |
| `SupplierInvoiceApplication.kt` | قواعد تطبيقية مستقلة لتحضير فاتورة المورد، التحقق من البنود والمبالغ، احتساب فروق الأسعار وبصمة الطلب، واستخدام عمليات حسابية دقيقة مع اكتشاف التجاوز. | هذه طبقة تحقق محلية وليست بديلًا عن قيود الخادم أو حفظ المستند ذريًا. تغييرات القواعد تحتاج اختبارات الوحدة الموجودة. |
| `SupplierInvoiceEntryDialog.kt` | واجهة إدخال فاتورة المورد وربطها ببيانات الشراء. | لا تفترض أن الفاتورة منشورة محاسبيًا لمجرد قبول المدخلات. |
| `SupplierPaymentEntryDialog.kt` | واجهة إدخال دفعة المورد/بيانات الإثبات. | يجب أن يمنع الخادم تكرار الإثبات، ويحدد الالتزام والعملة وطريقة الدفع ويكتب الأثر المحاسبي بصورة متسقة. |
| `ActivityProfileScreen.kt` | تسجيل الدخول/إنشاء الحساب وإعداد ملف النشاط التجاري وبيانات المالك والعنوان والروابط الخارجية للصور عبر Supabase. | تحفظ روابط الصور الخارجية ولا ترفع الملفات إلى Supabase Storage. صور الهوية بيانات حساسة؛ استخدم تخزينًا خاصًا أو روابط موقعة قصيرة الصلاحية. لا تضع مفتاح خدمة سريًا داخل التطبيق. |
| `CatalogScreen.kt` | اكتشاف دليل الأصناف العالمي وإدارته، تحميل التصنيفات والوحدات والمنتجات، وإرسال طلبات النقل بين حسابات الدليل ومراجعتها. | دليل الأصناف العالمي ليس هو نفسه رصيد المخزون المحلي. يجب إبقاء مصدر البيانات وصلاحيات الإدارة وحدود المؤسسة واضحة. |
| `CatalogProductEditDialog.kt` | تحرير تفاصيل صنف في الدليل، بما في ذلك حقول النص/القوائم التي يستهلكها مسار إدارة الدليل. | لا تخلط بين تحديث بيانات الدليل وبين تعديل أرصدة المخزون أو أسعار عمليات البيع السابقة. |
| `MasterDataScreen.kt` | إدارة البيانات المرجعية التجارية مثل المستودعات ومواقع التخزين والمجموعات والأقسام والتصنيفات ضمن المؤسسة. | توثيق وإدارة موقع التخزين لا يعني أن الأرصدة أو حركات النقل مربوطة به؛ هذه الفجوة موضحة في وثيقة معمارية المستودعات. |
| `CommercialUnitDirectoryScreen.kt` | إدارة دليل وحدات القياس الخاصة بالمؤسسة. | معاملات التحويل يجب أن تكون موجبة وصحيحة ومتسقة في المخزون والشراء والبيع؛ تغيير الوحدة لا يعيد كتابة تاريخ المستندات السابقة. |
| `CommercialCloudStockAlertsScreen.kt` | عرض تنبيهات المخزون اعتمادًا على بيانات الرصيد وحد التنبيه المتاحين. | دقة التنبيه تعتمد على دقة تحميل الرصيد؛ لا تستخدم هذا العرض كبديل عن حجز/خصم ذري في عملية البيع. |
| `CommercialCloudExpensesScreen.kt` | تسجيل وعرض سندات المصروفات السحابية وتحديث القائمة بعد العملية. | الترحيل `20261026121906_commercial_cloud_expense_vouchers.sql` يحدد مسار RPC؛ وجوده في Git لا يثبت نشره. يجب اختبار الإلغاء والعكس والتدقيق مع مستخدم مصادق عليه. |
| `CommercialCloudOperationalScreen.kt` | عرض التقارير التشغيلية اليومية المتاحة للمبيعات والتحصيلات والمصروفات والرصيد المستحق وبعض مؤشرات الربح. | هذه تقارير تشغيلية وليست بديلًا عن القوائم المالية الموحدة أو ميزان المراجعة؛ لا تعلن اكتمال المحاسبة دون مطابقة القيود والذمم والمخزون والتسويات. |

| `SupabaseClient.kt` | عميل HTTP واحد لطلبات Supabase، بما فيها الرؤوس والمهل الزمنية ومعالجة انقطاع الاتصال. | لا تنشئ عميلًا موازيًا أو تغير سياسة إعادة المحاولة/التحقق من النتيجة دون اختبارات؛ الجلسة تظل في طبقتها الحالية. |
| `CommercialCloudSession.kt` | إنشاء مخزن الجلسة المشفر المشترك الذي يستخدمه إعداد النشاط ومستودع السحابة. | يحافظ على اسم ملف التفضيلات ومخطط التشفير الحالي حتى لا تضيع الجلسات المحفوظة. |
| `CloudErrorMapper.kt` | تحويل رموز وأخطاء PostgREST والعمليات التجارية إلى رسائل قابلة للتصرف. | لا يقرر نجاح العملية؛ يوضح فقط سبب رفض الخادم أو عدم تأكيد النتيجة. |
| `CatalogRepository.kt` | تحميل المخزون السحابي الدقيق، إعداد وحدات البيع، ربط دليل الأصناف بعروض النشاط/الموقع، وإنشاء عرض المخزون من الصنف العالمي أو الإنشاء المباشر. | يحافظ على الفرق بين الصنف العالمي وعرض النشاط ورصيد المخزون المحلي، وعلى `BigDecimal` في الرصيد الدقيق. |
| `MasterDataRepository.kt` | دليل وحدات القياس، تصنيفات الأصناف، المستودعات، مواقع التخزين، الأقسام، وربط الأقسام بالمستودعات. | يستخدم مساعدات الجلسة والصفحات المشتركة؛ لا ينشئ مخططًا جديدًا أو يغيّر أرصدة المخزون. |
| `CustomerAccountsRepository.kt` | إسقاط الذمم المفتوحة حسب النشاط والعميل، التحصيل الجزئي، مفتاح إعادة محاولة التحصيل، وجلب سند القبض المرقّم من الخادم. | التحصيل لا يكتمل إلا عبر RPC المحاسبي الحالي؛ تُخصم المرتجعات المعتمدة والاستردادات المنفذة مرة واحدة، ولا تُقرأ جداول الاسترداد الداخلية من العميل. |
| `AccountingRepository.kt` | حسابات الأستاذ العام، خرائط الحسابات، حسابات مراقبة العملاء والموردين، وربط حسابات الأطراف وسطور القيود المرحلة. | قراءات محاسبية مقيدة بالمؤسسة والقيود المرحلة؛ لا تنشئ قيودًا ولا تعيد ترحيلها من التقارير. |
| `CustomerPortalRepository.kt` | طلبات العميل والحجوزات متعددة الخدمات وسجل البوابة، وإدارة التاجر للطلبات والحجوزات والتنفيذ والإخفاء وتعديل البنود. | يستخدم جلسة العميل الموثقة وسياق المؤسسة المشترك؛ لا يسجل تحصيل العميل مباشرة ولا يتجاوز موافقة التاجر أو RPC. |
| `InventoryRepository.kt` | حركات المخزون وتنبيهات إعادة الطلب وسياسات تقييم التكلفة وطبقات التكلفة وأرصدة المواقع وسياق ربط المستودعات. | قراءات مخزنية فقط؛ تحافظ على `BigDecimal` والكميات والوحدات ولا تنفذ تسويات أو تحركات من شاشة التقارير. |
| `ReturnsRepository.kt` | فواتير البيع القابلة للإرجاع، طلبات المرتجعات، الفحص، قرار رد المبلغ، وإعادة التخزين. | يحافظ على حدود الكمية العشرية وحالة المرتجع ومفاتيح العمليات؛ اعتماد الكميات والأرصدة يبقى في RPC وقاعدة البيانات. |
| `SupplierPaymentRepository.kt` | قراءة دفعات الموردين المرحلة وتسجيل دفعة مورد عبر RPC المحاسبي مع التحقق من المبلغ والطريقة والمستند المؤيد. | لا تسجل المصروف أو فاتورة المورد؛ الدفعة مرتبطة بالتزام المورد وتُقرأ من السجلات المرحلة فقط. |
| `PurchaseRepository.kt` | أوامر الشراء والاستلام، فواتير الموردين وتفاصيل بنودها، خيارات المورد والمستودع، ومفاتيح منع التكرار. | يحافظ على RPCs الحالية وحقول الفاتورة والاستلام والربط بالدليل؛ لا يغير أسماء الجداول أو الأعمدة. |
| `ExpenseRepository.kt` | سندات المصروفات السحابية، عملة المؤسسة والمنطقة الزمنية للتقارير، الإلغاء، وتسجيل المصروف بمفتاح منع تكرار. | لا يغيّر مصدر العملة أو المنطقة الزمنية إلى إعداد الجهاز؛ يستعمل إعداد المؤسسة من الخادم. |
| `SalesRepository.kt` | بيع الفواتير الفردية والمتعددة، مفاتيح منع التكرار، سجل فواتير البيع السحابي وملخصات البيع. | يحافظ على RPCs الحالية وحقول الفاتورة والدفعات والمرتجعات كما هي؛ يستخدم مساعدات الاتصال والصفحات المشتركة. |
| `CustomerPaymentRequestsRepository.kt` | طلبات الدفع من بوابة العميل ومراجعتها من التاجر، مع مفاتيح إعادة المحاولة والنماذج الخاصة بها. | لا يسجل تحصيلًا مباشرة؛ اعتماد التاجر هو الذي يستدعي مسار التحصيل المحاسبي الحالي. |
| `CommercialModels.kt` | نماذج `Product` و`SaleRecord` المشتركة بين الواجهة ومستودع السحابة. | `Product.stock` حقل توافق صحيح العدد؛ استخدم `stockPrecise` عند التعامل مع الرصيد الدقيق. |
| `LocalStoreModels.kt` | نماذج التسويات والمصروفات المحلية، ونموذج وحدة البيع المحلية ومحللات وحدات المنتج. | لا تمثل هذه النماذج دفتر الأستاذ السحابي ولا تزامنًا تلقائيًا؛ لا تغير صيغة JSON المخزنة أو قواعد التحويل دون اختبارات ترحيل. |
| `LocalStoreRepository.kt` | تحميل وحفظ المنتجات والمبيعات والتسويات والمصروفات المحلية في SharedPreferences مع عزل المفاتيح حسب المؤسسة عند توفرها. | يحافظ على أسماء المفاتيح وحقول JSON القديمة؛ لا يعني التخزين المحلي مزامنة سحابية أو دفترًا ماليًا معتمدًا. |

اختبار عقد التخزين: `tests/test_local_store_repository_contract.py`.

| `DashboardScreen.kt` | لوحة المتجر المحلية ومؤشرات المبيعات والمخزون. | تعرض البيانات المتاحة في وضع المتجر المحلي فقط؛ لا تستبدل لوحة المؤشرات السحابية. |
| `LocalSalesScreen.kt` | شاشة المبيعات المحلية والفواتير المحفوظة على الجهاز. | لا تستخدم بياناتها لتأكيد عمليات البيع السحابية أو قيود الخادم. |
| `LocalInventoryScreen.kt` | شاشة المخزون المحلي والبحث عن الأصناف. | دليل الأصناف العالمي وعروض المؤسسة ومخزونها السحابي تبقى في مساراتها المنفصلة. |
| `StoreUiComponents.kt` | بطاقات المؤشرات والعناوين وصفوف المنتجات والمبيعات المشتركة بين الشاشات المحلية. | مكونات عرض فقط؛ قواعد المخزون والبيع لا توضع هنا. |

اختبارات حدود الشاشات المحلية: `tests/test_local_commercial_screens_contract.py`.
| `StoreUiTheme.kt` | ألوان واجهات المتجر المحلية المشتركة. | تعديل الألوان هنا ينعكس على الشاشات المحلية التي تستخدمها. |
| `StoreUiFormatting.kt` | تنسيق المبالغ المحلية القديمة بوحدة الريال اليمني. | لا تستخدمه لتنسيق مبالغ Supabase الصغرى متعددة العملات؛ تلك لها قواعدها المستقلة. |

## 3. ملفات الاختبار والبناء

- `app/src/test/java/com/adnanalhasani/commercial/SupplierInvoiceApplicationTest.kt`: اختبارات قواعد `SupplierInvoiceApplication`. نجاحها لا يثبت اختبار شبكة Supabase أو دورة شراء كاملة.
- `app/src/main/java/com/adnanalhasani/commercial/CustomerPaymentRequestAmount.kt`: تحويل مبالغ طلبات الدفع بين الوحدات الكبرى والصغرى حسب العملة، مع رفض الدقة غير القابلة للتمثيل. اختبار السلوك: `app/src/test/java/com/adnanalhasani/commercial/CustomerPaymentRequestAmountTest.kt`.
- `app/src/main/java/com/adnanalhasani/commercial/SupplierInvoicePriceVarianceCalculations.kt`: نماذج وحساب فرق سعر فاتورة المورد مقابل تكلفة أمر الشراء، مع تجميع دقيق وتقريب مرة واحدة. اختبار السلوك: `app/src/test/java/com/adnanalhasani/commercial/SupplierInvoicePriceVarianceReportTest.kt`.
- `app/src/main/java/com/adnanalhasani/commercial/CostProfitCalculations.kt`: حساب صافي الإيراد وتكلفة البضاعة المباعة وتكلفة المرتجعات المعاد تخزينها والربح الإجمالي بدقة، مع منع التعويض الصفري عند غياب التكلفة. اختبار السلوك: `app/src/test/java/com/adnanalhasani/commercial/CostProfitReportTotalsTest.kt`.
- `app/src/main/AndroidManifest.xml`: تعريف التطبيق وصلاحياته ونقطة الدخول. يجب مراجعة أي تغيير صلاحيات مع متطلبات التشغيل الفعلية.
- `app/build.gradle.kts` و`build.gradle.kts` و`settings.gradle.kts`: إعداد بناء Android والاعتماديات والمشروعات.
- `gradle.properties`: خصائص البناء. لا تضع أسرار الإنتاج فيه ولا تضمن أن متغيرات البناء وحدها تحمي المفاتيح.
- `docs/architecture/README.md`, `module-boundaries.md`, and `refactoring-roadmap.md`: current modularization target, ownership rules, and execution status.
- `docs/cloud-backed-screen-delivery.md`: سجل شرائح تسليم الشاشات السحابية وحدود التحقق.
- `docs/commercial-documents-implementation-plan.md`: عقد سير المستندات التجاري والبوابات المتبقية.
- `docs/warehouse-and-classification-architecture.md`: نموذج المستودعات ومواقع التخزين والتصنيفات وحالة الربط المخزني.

## 4. مسارات البيانات الحرجة

### البيع
واجهة تركيب الفاتورة → `CommercialCloudRepository` → RPC البيع السحابي → تحقق الهوية والعضوية والسعر والوحدة والمخزون ومفتاح التكرار في الخادم → إنشاء المستند والبنود وحركة المخزون والدفعة/القيد التي يغطيها الترحيل. يجب اختبار الفشل وإعادة الإرسال والتزامن، لا المسار السعيد فقط.

### تحصيل العميل
واجهة المديونية → RPC التحصيل → تحقق الرصيد بعد الائتمانات والاستردادات → حفظ السند والقيود في معاملة الخادم → إعادة تحميل الرصيد. لا ينبغي للتطبيق منح نفسه صلاحية قراءة جداول مالية داخلية محمية.

### المرتجع والاسترداد
طلب الإرجاع → قرار المراجعة → الفحص/إعادة التخزين عند توفر ربط المخزون → الاسترداد المالي المنفصل عند انطباقه. يجب أن تكون كل خطوة قابلة للتتبع ولا تمحو المستند الأصلي.

### المشتريات
أمر شراء → ربط بنود بعروض صالحة → استلام كلي/جزئي → فاتورة المورد → التزام/دفعة → قيد ومخزون. لا تعتبر وجود واجهات الإدخال دليلًا على اكتمال كل هذه المراحل.

### المصروفات
واجهة سند المصروف → RPC محمي → تحقق الدور والعضوية والعملة والمبلغ وطريقة الدفع ومفتاح التكرار → سند وقيد متوازن ضمن معاملة واحدة → سجل تدقيق. الإلغاء يجب أن ينشئ أثرًا عكسيًا قابلًا للتتبع.

## 5. فجوات موثقة لا يجوز اعتبارها منجزة

1. **الكميات الكسرية في البيع:** نموذج الكمية ومرشح الإدخال في محرر الفاتورة ورصيد `Product` ما زالت صحيحة الأعداد فقط؛ كما أن مسار تحميل المخزون يحول كمية متاحة إلى `Int`. يجب إصلاح النموذج والواجهة والحساب والتحقق الخادمي والاختبارات معًا.
2. **مواقع التخزين التفصيلية:** إدارة الأدلة موجودة، لكن الربط بأرصدة المخزون وحركات النقل غير مكتمل وفق وثيقة المعمارية.
3. **المحاسبة الموحدة:** لا تزال التقارير المالية الكاملة، التسويات، إقفال الفترات وأعمار الديون مشروطة ببوابات قبول مستقلة.
4. **نشر الترحيلات:** لا يمكن استنتاج أن قاعدة Supabase الحية مطابقة لملفات Git من وجود migration في الفرع فقط.
5. **اختبار قبول سحابي:** الاختبارات الساكنة أو اختبارات المصدر لا تثبت أن RLS وعلاقات PostgREST وRPC تعمل في بيئة حية بمستخدم مصادق عليه.
6. **الجاهزية للإنتاج:** لا تستنتج من اكتمال الشاشة أو بناء APK فقط؛ يلزم نجاح بوابات الأمان والتكامل والاستعادة والمراجعة التشغيلية.

## 6. إجراء تحديث هذا المرجع

عند تغيير ملف:
1. حدّث صف الملف في هذا المرجع إذا تغيرت مسؤوليته أو حدوده.
2. حدّث وثيقة سير العمل المعنية إذا تغيرت حالات المستند أو قواعد القبول.
3. أضف/حدّث اختبارًا يثبت السلوك، ولا تصف السلوك بأنه متحقق إذا كان التغيير موثقًا فقط.
4. سجّل commit ونتيجة CI وبيئة التحقق، وافصل بين `CODE` و`MIGRATION_IN_GIT` و`MIGRATION_APPLIED` و`INTEGRATION_TESTED`.
5. راجع `docs/audits/README.md` إذا أُغلق عيب أو ظهر عيب جديد، وأزل العيوب التي لم تعد موجودة بدل تكرارها.

## 7. حدود هذه الوثيقة

هذه خريطة مسؤوليات الملفات ومسارات العمل التجارية مبنية على أسماء الملفات والرموز والوثائق الحالية؛ ليست شرحًا لكل سطر من ملفات Kotlin، ولا تدقيقًا لكل ملفات المستودع البالغ عددها مئات الملفات. لم يتم تغيير سلوك التطبيق في هذه الوثيقة، ولم تُطبّق migrations على Supabase الحية، ولم تُنفّذ عملية بيع/شراء/استرداد حية كجزء من إنشاء هذا المرجع. يجب أن تظل هذه الحدود صريحة في تقارير الحالة.


### Reports catalog extraction (2026-11-04)
- `ReportsCatalog.kt` owns `ReportCatalogItem` and `buildReportsCatalog(cloudMode)`.
- `ReportsCenterScreen.kt` consumes the catalog and retains filter state, calculation orchestration, presentation, and export integration. `ReportsCloudData.kt` owns the cloud report projections and refresh lifecycle.
- Keep catalog labels, source descriptions, supported flags, and readiness notes together in `ReportsCatalog.kt`; do not duplicate the catalog in screen code.


### Inventory report mapping extraction (2026-11-04)
- `ReportInventoryLines.kt` owns the shared `ReportLine` presentation model and the pure mapping/state builders for location inventory and stock-movement-by-user reports.
- `ReportsCenterScreen.kt` retains report selection, data loading, filtering, screen state, rendering, and export orchestration; it consumes the extracted internal report mapping functions.
- `ReportCalculations.kt` remains the owner of reusable exact arithmetic and aggregation rules. The extraction did not change formulas, query scopes, quantity precision, or report labels.
- Source-contract tests now read the new owner file for moved helpers; this is a source-location update, not a relaxation of the report assertions.


### Financial report row assembly extraction (2026-11-04)
- `ReportFinancialLines.kt` owns the pure row assembly for the ledger cash-flow report and the tax/discount summary, including the fail-closed source-error state.
- `ReportsCenterScreen.kt` retains the report controls, data loading, report selection, and rendering; shared `reportMoney` and `reportDateMillis` helpers are exposed internally for the extracted mapper.
- The extraction preserves posted-ledger source filtering, currency grouping, opening/closing balance logic, document-date windows, and the existing exact arithmetic/error handling.


### Catalog activity hierarchy extraction (2026-11-04)
- `CatalogActivityHierarchy.kt` owns the activity-directory node model and pure parent-chain/sector/business-level selector helpers.
- `CatalogScreen.kt` retains activity-directory loading/parsing, selected activity state, menus, RPC calls, catalog discovery, editing, transfers, and invoice-purchase linking.
- The extraction does not change activity codes, RPC parameters, default business-level selection, or the relationship between global catalog items and organization offerings.


### Catalog domain model extraction (2026-11-04)
- `CatalogModels.kt` owns the shared catalog data models: products, units, rulings, categories, branch/warehouse options, nearby-market rows, invoice-purchase mapping rows, directory units, and pending transfers.
- `CatalogScreen.kt` continues to own Compose state, screen rendering, data loading, RPC calls, and user workflows; it consumes the extracted models without changing their fields or defaults.
- The models are package-internal so existing callers and dialogs retain access. No database schema, RPC contract, or persisted data changed.
- `tests/test_catalog_models_ownership.py` guards the single model owner and the screen's existing model contract.


### Catalog transport consolidation (2026-11-04)
- `CatalogCloudTransport.kt` retains the catalog call-site helpers but delegates both GET and POST requests to the shared `SupabaseClient`.
- Catalog session reads/writes now use `commercialCloudSessionPreferences`; the duplicate encrypted-preference factory and direct `HttpURLConnection` implementations were removed from `CatalogScreen.kt`.
- Catalog HTTP requests retain the 15-second connect timeout and 25-second read timeout through the shared client, with the shared actionable backend/network error handling. RPC names, paths, payloads, tokens, and query scopes are unchanged.

- `app/src/test/java/com/adnanalhasani/commercial/ReportInventoryLinesTest.kt`: verifies location report quantity mapping, fractional stock movement formatting, date-window filtering, and fail-closed error states for the extracted inventory report mappers.


### Report domain mapper extraction (2026-11-04)
- `ReportDomainMappers.kt` owns pure conversions from cloud sale, return, supplier invoice, and supplier aging models into calculation/report inputs and presentation rows.
- `ReportsCenterScreen.kt` continues to own Compose state, loading, report selection, date/filter orchestration, and rendering; it calls the extracted internal mappers.
- The extraction preserves model fields, filtering predicates, row labels, money formatting calls, and calculation entry points. It does not change report formulas or data sources.
- `tests/test_report_domain_mappers_ownership.py` guards the mapper owner and screen call sites; the broader report source contract now reads the mapper file.


### Report formatting extraction (2026-11-04)
- `ReportFormatting.kt` owns currency fraction-digit resolution, minor-unit/local money formatting, and ISO instant/date-only timestamp parsing.
- `ReportsCenterScreen.kt` retains report filters and UI but calls these shared package-level helpers.
- `ReportFormattingTest.kt` covers currency exponent handling (USD/JPY), local whole-unit formatting, instant/date-only parsing, and invalid currency fallback.


### Report ledger classification extraction (2026-11-04)
- `ReportLedgerClassification.kt` owns normalization of ledger account types, income-statement/balance-sheet classification, and debit/credit normal-balance aggregation.
- `ReportsCenterScreen.kt` consumes the shared helpers while retaining the same account classification rules and statement row construction.
- `ReportLedgerClassificationTest.kt` covers account-type aliases, contra-revenue and COGS detection, unknown account types, and debit/credit balance direction.


### Catalog login panel extraction (2026-11-04)
- `CatalogLoginPanel.kt` owns the login form presentation, validation enablement, and security guidance text.
- `CatalogScreen.kt` retains authentication request orchestration, encrypted refresh-token persistence, session state, and feedback handling; the login panel receives callbacks and does not access credentials storage or network APIs.
- `tests/test_catalog_models_ownership.py` guards the panel call site and the dedicated composable owner.


### Local sale and product dialog extraction (2026-11-04)
- `LocalProductDialogs.kt` owns `SaleEntryDialog`, `EditProductDialog`, and `AddProductDialog`.
- The dialogs retain their existing parameters, local validation, unit selection, and callbacks. `MainActivity.kt` continues to invoke the same composables; no local storage contract or cloud operation was changed.
- `tests/test_local_commercial_screens_contract.py` guards the single owner for these dialogs.


### MainActivity and local operational screen extraction (2026-11-04)
- `MainActivity.kt` now contains only the Android activity lifecycle, Compose theme setup, and shared cloud executor/handler declarations.
- `LocalOperationalScreens.kt` owns the local More, expenses, legacy reports, cloud legacy reports, debts, expense-entry, and settlement UI.
- `LocalProductDialogs.kt` owns local sale, add-product, and edit-product dialogs.
- `tests/test_local_commercial_screens_contract.py` verifies the thin activity boundary and the single owner of each extracted composable.


### Shared store palette extraction (2026-11-04)
- `StorePalette.kt` owns the shared Compose colors used by the activity, local operational screens, product dialogs, and app shell.
- Extracted the existing color values unchanged from the former activity file. This prevents extracted UI files from depending on private declarations in `MainActivity.kt`.
- `tests/test_local_commercial_screens_contract.py` verifies the single palette owner.


### Catalog market response mapping

- `CatalogMarketData.kt` owns parsing of branch/warehouse market context, aggregated nearby-catalog rows, and aggregated price-guidance rows.
- `CatalogScreen.kt` keeps orchestration, location permission handling, pagination, and UI state; it delegates response mapping to the pure parser functions.


### Catalog discovery mapper
- `CatalogProductMapper.kt` owns mapping of catalog discovery page responses into the existing `CatalogProduct` model.
- `CatalogScreen.kt` retains request orchestration, page offset/has-more decisions, item de-duplication, and UI feedback.
- `CatalogProductMapperTest.kt` covers nested units/rulings, localized display fallback, raw-value retention, and empty pages.
- `tests/test_catalog_models_ownership.py` enforces the mapper boundary.


### Catalog directory mapping extraction (2026-11-04)
- `CatalogDirectoryMapper.kt` owns the combined directory response parser for categories, translations, units, local unit codes, and activity hierarchy nodes.
- `CatalogScreen.kt` continues to own request orchestration, locale selection, initial activity inference, screen state, and feedback.
- `CatalogDirectoryMapperTest.kt` covers translated and fallback fields, activity-node filtering, local-unit normalization, empty collections, and malformed required arrays.


### Catalog invoice and transfer mapping extraction (2026-11-04)
- `CatalogWorkflowMappers.kt` owns pure mapping for invoice-purchase request lists and pending catalog-transfer lists.
- `CatalogScreen.kt` retains request URLs, organization scoping, sorting/limits, actions, and feedback.
- `CatalogWorkflowMappersTest.kt` covers request/transfer field mapping, optional purchase-order IDs, and empty responses.


### Catalog invoice mapping response extraction (2026-11-04)
- `CatalogInvoiceMappingMapper.kt` maps the prepared invoice-mapping payload into location, warehouse, unmatched-line, and offering models.
- `CatalogScreen.kt` retains purchase-order eligibility and lock checks, REST orchestration, active catalog filtering, and mapping state.
- `CatalogInvoiceMappingMapperTest.kt` covers the response fields and empty collections.


### Reports ledger validation extraction (2026-11-04)
- Moved the existing financial-statement source validation block into `ReportLedgerValidation.kt` as a pure function.
- Preserved fail-closed error precedence, required GL mapping keys, supplier control-account roles, end-date filtering, invalid-date rejection, entry/currency balancing, account-type allowlist, and ISO currency validation.
- `ReportsCenterScreen.kt` still owns all cloud loads, Compose state, and rendering; it now delegates only this validation decision.
- Added `ReportLedgerValidationTest.kt` for cloud-mode gating, source-error precedence, missing mappings/roles, malformed dates, empty windows, unbalanced/unknown/invalid-currency entries, and a valid balanced ledger.
- No database, RPC, report formula, or export contract was changed.


### Reports cloud data extraction (2026-11-04)
- `ReportsCloudData.kt` owns the immutable snapshot of cloud report projections, per-source load errors, and the refresh lifecycle used by the commercial reports center.
- `ReportsCenterScreen.kt` consumes the snapshot and retains report selection, date/filter state, report-row orchestration, Compose presentation, and export integration.
- Each projection remains independently guarded with `runCatching`; failed sources are cleared and expose their own error so report availability continues to fail closed. The loader preserves the existing source functions and sequential retrieval order.
- This is a source-ownership change only. No query scope, RPC, report formula, database schema, or production data changed.


### Reports center UI state extraction (2026-11-04)
- `ReportsCenterUiState.kt` owns the Compose mutable state for report selection, date range, search/filter controls, export payload/title, feedback, supplier filter, and cloud refresh key.
- `ReportsCenterScreen.kt` keeps stable delegated local properties so existing calculations, event handlers, and rendering retain their names and behavior while state creation has one owner.
- The state owner contains no report calculations, repository calls, or export I/O.


### Reports period controls extraction (2026-11-04)
- `ReportsPeriodControls.kt` owns the period-preset chips and editable start/end date fields as a presentation component.
- `ReportsCenterScreen.kt` retains date parsing, invalid-range handling, report-window filtering, and the selected period state; the component communicates changes only through callbacks.
- Existing presets and their date boundaries are preserved. No report query or calculation was moved into the UI component.


### Reports Android export lifecycle extraction (2026-11-04)
- `ReportsExportLaunchers.kt` owns Android document-picker launchers, CSV/XLSX/PDF stream writing, PDF sharing, and export-source/date guards.
- `ReportExport.kt` continues to own the pure/native PDF/XLSX/print rendering implementation; the launcher owner delegates to it.
- `ReportsCenterScreen.kt` builds the selected report's export rows and invokes the launchers through stable aliases. Export metadata and error feedback remain in the screen state.


### Reports catalog presentation extraction (2026-11-04)
- `ReportsCatalogSection.kt` owns the report category chips, catalog cards, source-readiness labels, and title-to-selection mapping as a `LazyListScope` section.
- `ReportsCenterScreen.kt` supplies the current catalog/filter/source state and receives the selected report through a callback; it retains report calculations and result rendering.
- The component does not load sources or decide report support. It respects the catalog's supported flag, date validity, loading state, and per-report source error.


### Inventory report row assembly extraction (2026-11-04)
- `ReportInventoryRows.kt` owns selected-row assembly for current inventory valuation, inventory movement, stock-by-user, location balance, warehouse-mapping readiness, current stock, and low-stock alert reports.
- `ReportsCenterScreen.kt` dispatches to this builder first and continues with other report families when it returns `null`; source readiness and date-window inputs are passed from the existing screen state.
- Existing cost/currency checks, quantity precision, current-snapshot wording, and inventory source scopes are preserved.


### Posted-ledger report row assembly extraction (2026-11-04)
- `ReportLedgerRows.kt` owns row assembly for the income statement/balance sheet, trial balance, general-ledger movement/account statement, posted journal detail, and journal register reports.
- `ReportsCenterScreen.kt` delegates these report selections to the ledger row builder after the inventory builder declines ownership; ledger validation, source projections, and account/date filters remain explicit inputs.
- The extraction preserves posted-ledger source semantics, account classification, debit/credit directions, currency grouping, balance equation checks, and account-query requirements.


### Detailed tax report source validation extraction (2026-11-05)
- `ReportTaxValidation.kt` owns the source-readiness and record-integrity gates for the detailed recorded-tax/discount report.
- `ReportsCenterData.kt` supplies the already-loaded sales, purchase, return projections, source errors, and selected date window.
- The original fail-closed precedence, canceled-invoice exclusions, date checks, amount/currency validation, and return-to-sale currency matching remain unchanged. Tax row presentation remains in `ReportTaxRows.kt`; no tax rate or liability formula was introduced.


### Customer/supplier statement validation extraction (2026-11-05)
- `ReportPartyStatementValidation.kt` owns the source-readiness gates for full customer and supplier control-account statements.
- `ReportsCenterData.kt` passes the existing loaded ledger, party-link, GL mapping, supplier-control-account projections and source errors to the pure validators.
- Validation precedence, Arabic errors, account-role/mapping checks, and party/currency link checks are preserved; row construction remains in `ReportPartyStatementRows.kt`.
- `tests/test_report_party_statement_rows_ownership.py` and `tests/test_reports_center_data_ownership.py` guard the new owner and coordinator call sites.


### Customer/supplier statement row extraction (2026-11-04)
- `ReportPartyStatementRows.kt` owns row assembly for the full customer control-account statement, supplier control-account statement, and operational customer statement.
- It consumes loaded party-account links, control-account mappings, posted ledger lines, sales/purchase projections, and date/name filters. It does not fetch data or mutate receivables/payables.
- The extraction preserves account-role/currency scoping, posted-ledger date ordering, opening/period/closing balances, exact amount summation, and refund-aware customer movement construction.


### Sales report row assembly extraction (2026-11-04)
- `ReportSalesRows.kt` owns row assembly for sales summaries, invoice details/lines, sales by item/customer/payment state, discounts/tax, average invoice value, and period comparison.
- `ReportsCenterScreen.kt` delegates those selections to the sales row builder using the existing local/cloud projections and validated period boundaries.
- Completed-sale filters, cloud/local source separation, invoice line snapshots, currency grouping, and equal-duration comparison windows are preserved.


### Supplier invoice and obligation validation extraction (2026-11-05)
- `ReportSupplierValidation.kt` owns the source-readiness and integrity checks for supplier invoice variance, invoice details, supplier purchase summaries, and supplier obligations.
- `ReportsCenterData.kt` retains the active-invoice/obligation projections and obligation input preparation, then delegates validation decisions to the focused owner.
- Existing source-error precedence, canceled-invoice exclusions, date-window rules, ISO currency checks, invoice line/subtotal/tax/freight reconciliation, exact arithmetic, and Arabic errors are preserved. Calculation formulas remain in `SupplierInvoicePriceVarianceCalculations.kt` and `ReportCalculations.kt`; row assembly remains in `ReportSupplierRows.kt`.
- `tests/test_report_supplier_rows_ownership.py` and `tests/test_reports_center_data_ownership.py` guard the new owner and coordinator call sites.


### Supplier report row assembly extraction (2026-11-04)
- `ReportSupplierRows.kt` owns row assembly for supplier invoice price variance, recorded invoices/obligations, supplier obligation totals, and supplier movement statements.
- It consumes existing purchase/invoice projections, posted supplier-payment movements, the current date window, and the supplier-name filter.
- The extraction preserves canceled-invoice exclusions, invoice/order currency and price-variance validation, obligation arithmetic, posted-payment signs, and opening/period/closing movement balances.


### Operational payment/return/expense report rows extraction (2026-11-04)
- `ReportOperationalRows.kt` owns row assembly for payment-method totals, successful cloud payments, local settlements, sales-return settlement status, receipt/payment records, and operating-expense summaries/details.
- It consumes the existing local/cloud projections and report period; canceled expense vouchers and unsuccessful payments remain excluded under the original rules.
- The extraction does not create payments, process refunds, cancel vouchers, or write ledger entries.


### Derived performance/aging/collection row mapping extraction (2026-11-04)
- `ReportDerivedRows.kt` owns presentation-row mapping for user sales performance, daily sales trend, customer receivable aging, customer collections, supplier purchase summaries, and supplier aging reports.
- The helpers receive existing calculation results and source-error states; a failed calculation remains fail-closed and does not get rendered as a partial report.
- The calculation formulas and source-validation rules remain in their existing domain/report owners.


### Detailed tax/discount report row extraction (2026-11-04)
- `ReportTaxRows.kt` owns the detailed tax/discount row projection from completed sales, non-canceled supplier invoices, and completed returns.
- The existing `taxDetailError` gate is applied before building rows; approved return value remains a return amount and is not mislabeled as a separately calculated tax refund.
- No tax formula, tax rate, or tax liability is inferred by the mapper.


### Historical cost/profit validation extraction (2026-11-05)
- `ReportProfitabilityValidation.kt` owns source readiness and integrity checks for historical cost/profit reporting.
- `ReportsCenterData.kt` passes loaded sales/return projections, source errors, and the selected period to the validator.
- The original validation precedence, missing historical-cost checks, restocked-return cost requirements, original-sale/currency matching, tax-return limitation, and exact arithmetic gate remain unchanged. Calculations stay in `CostProfitCalculations.kt`; input mapping stays in `ReportDomainMappers.kt`; row presentation stays in `ReportProfitabilityRows.kt`.
- `tests/test_report_profitability_rows_ownership.py` and `tests/test_reports_center_data_ownership.py` guard the new owner.


### Profitability report row mapping extraction (2026-11-04)
- `ReportProfitabilityRows.kt` owns display-row mapping for item profitability and historical cost-of-goods/gross-profit reports.
- It consumes the existing item-profitability calculation result and cost-profit source projections; the exact formulas and validation remain in `ReportCalculations.kt` and `CostProfitCalculations.kt`.
- Historical line costs, approved refunds, restocked-cost reversal, and the existing fail-closed error gates are preserved.


### Reports result presentation/export actions extraction (2026-11-04)
- `ReportsResultSection.kt` owns selected-report result cards, empty-state messaging, CSV/XLSX/PDF/print actions, and the common export-row metadata builder.
- `ReportsCenterScreen.kt` supplies validated rows and source/date readiness, then receives export metadata/error updates through callbacks.
- Android document-picker and share lifecycle remains in `ReportsExportLaunchers.kt`; native PDF/XLSX/print rendering remains in `ReportExport.kt`.


### Reports filter presentation extraction (2026-11-04)
- `ReportsFilterSection.kt` owns supplier/customer party filters, generic row/currency/status filters, and the explicit ledger-account query field.
- `ReportsCenterScreen.kt` supplies selected report/state and receives filter changes through callbacks; the existing row-filtering and account-query requirements remain in the screen/domain pipeline.


### Reports center data orchestration extraction (2026-11-04)
- `ReportsCenterData.kt` owns the report-data preparation pipeline: date validation/window boundaries, report-specific source validation, calculation orchestration, catalog filtering, selected-row dispatch/filtering, and summary totals.
- `ReportsCenterScreen.kt` now owns only Compose screen composition and event wiring; it consumes the prepared `ReportsCenterData` snapshot and delegates period/filter/catalog/result UI to focused components.
- Cloud retrieval remains in `ReportsCloudData.kt`; interactive state remains in `ReportsCenterUiState.kt`; per-family row builders and export lifecycle remain in their dedicated files.
- The report data coordinator is intentionally read-only and consumes existing local/cloud projections. No query scope, RPC, schema, accounting formula, or production data was changed by this extraction.


### Reports validation ownership (2026-11-05)
- `ReportOperationalValidation.kt` owns fail-closed checks for operating expenses, payment-method analysis, operational customer statements, customer collections, user sales performance, and daily sales trends.
- `ReportProfitabilityValidation.kt` owns both historical cost/profit checks and item-profitability invoice/return integrity checks.
- `ReportsCenterData.kt` retains loaded projections, date-window inputs, calculation orchestration, and report/error routing; calculations and row builders remain in their existing owners.
- Source ownership guards were updated. No Android build or test suite is claimed as executed for this change.


### Financial report validation (2026-11-05)
- `ReportFinancialValidation.kt` owns the current inventory valuation source gate and the cash-flow source/integrity gate, including overflow preflight for every exact arithmetic step used by cash-flow row assembly.
- `ReportsCenterData.kt` delegates those checks while retaining report dispatch. `ReportFinancialLines.kt` and `ReportInventoryRows.kt` remain the row builders.


### Report calculation input mapping (2026-11-05)
- `ReportDomainMappers.kt` owns cloud-to-calculation input projections for item profitability, customer collections, user sales performance, daily sales trends, and supplier purchase summaries.
- `ReportsCenterData.kt` retains calculation lifecycle/orchestration and calls those pure mappers. Calculation formulas and report row builders remain in their dedicated files.


### Supplier obligation input mapping (2026-11-05)
- `ReportDomainMappers.kt` owns active supplier invoice/obligation candidate selection and mapping into obligation calculation inputs.
- `ReportsCenterData.kt` retains report validation/calculate orchestration and reuses the shared active-invoice candidates for invoice-detail validation.


### Reports center currency totals
- Sales summary totals are aggregated by currency using exact `Long` addition in `calculateReportCurrencyTotals`.
- Overflow produces an explicit Arabic error in the summary card and suppresses totals; the UI no longer risks displaying wrapped values.


### Sales report aggregate arithmetic
- Sales summary, customer/item/payment-state breakdowns, invoice averages, and period comparisons use exact aggregation and exact differences.
- `ReportsCenterData.kt` preflights and reuses the selected sales-row result; overflow hides the affected report and exposes an Arabic error instead of wrapping totals.


### Party statement arithmetic validation
- Customer and supplier control-account statements now preflight debit/credit deltas, opening balances, and period running balances with exact arithmetic before rows render.
- Overflow produces a report-specific Arabic error and prevents partial/wrapped statement balances.


### Supplier movement statement validation
- Supplier movement inputs are projected once and shared by the validation and row owners.
- The report blocks `Long.MIN_VALUE` payment negation and overflow in opening/running balances instead of showing wrapped supplier balances.
