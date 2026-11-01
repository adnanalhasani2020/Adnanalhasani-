# توسعة فحص انجراف RPC بين تطبيق متجري وSupabase — 2026-11-02

## نطاق الفحص

أُعيد فحص ملفات Android التي تحتوي على استدعاءات RPC المباشرة أو استدعاءات واجهة الحسابات الشخصية:
- `CatalogScreen.kt`
- `CommercialNotificationsScreen.kt`
- `CommercialCloudRepository.kt`
- `CommercialCloudReturnRefund.kt`
- `ActivityProfileScreen.kt`
- `PersonalAccountsScreen.kt`

تمت مقارنة أسماء الإجراءات مع `pg_proc` في مشروع الإنتاج `dukeigbrzvpxzkbmdbeh` باستعلامات قراءة فقط.

## النتيجة

توجد **34 دالة RPC يستدعيها التطبيق ولا توجد في قاعدة الإنتاج** ضمن النطاق الذي فُحص. هذا العدد يشمل إجراءات الكتالوج والسوق والفواتير والإشعارات، وإجراءات حسابات التفويض والطلبات والحجوزات.

### الكتالوج والسوق وتحويل الفواتير والإشعارات (12)

- `commercial_catalog_discover_products_page`
- `commercial_catalog_configure_items`
- `commercial_catalog_create_inventory_item_from_global`
- `commercial_catalog_link_unpriced_items`
- `commercial_catalog_nearby_popularity`
- `commercial_catalog_price_suggestions`
- `commercial_invoice_purchase_map_known_items`
- `commercial_request_invoice_as_purchase`
- `commercial_review_invoice_purchase_request`
- `commercial_update_location_coordinates`
- `commercial_user_notifications_list_my`
- `commercial_user_notifications_mark_read`

### التفويض والطلبات والحجوزات متعددة الخدمات (16 إضافية)

- `commercial_account_list_delegated_booking_resources`
- `commercial_account_list_delegated_offerings`
- `commercial_account_list_my_active_delegations`
- `commercial_customer_portal_create_delegated_booking`
- `commercial_customer_portal_create_delegated_multi_service_booking`
- `commercial_customer_portal_create_delegated_order`
- `commercial_customer_portal_create_multi_service_booking`
- `commercial_customer_portal_decide_booking`
- `commercial_customer_portal_decide_order`
- `commercial_customer_portal_edit_order_lines`
- `commercial_customer_portal_fulfill_order`
- `commercial_customer_portal_list_editable_offerings`
- `commercial_customer_portal_list_organization_bookings`
- `commercial_customer_portal_list_organization_orders_v2`
- `commercial_customer_portal_set_booking_hidden`
- `commercial_customer_portal_set_order_hidden`

### إدارة التفويض المباشر (6 إضافية)

- `commercial_account_create_delegation`
- `commercial_account_delegated_spend_report`
- `commercial_account_list_delegations`
- `commercial_account_review_delegation`
- `commercial_account_revoke_delegation`
- `commercial_account_update_delegation`

## دليل بنيوي من الإنتاج

استعلام `information_schema.tables` عن أسماء الجداول التي تحتوي `delegat` أو `customer_portal` أو `invoice_purchase` أو `notification` أعاد جدولًا واحدًا فقط: `commercial_user_notifications`.

بالتالي، ليست الفجوة مجرد أسماء دوال RPC؛ مخطط الإنتاج يفتقد أيضًا جداول طلبات التحويل وبعض بنى التفويض وبوابة العملاء التي تعتمد عليها هذه الإجراءات. لا تُنشأ هذه الجداول يدويًا منفصلة عن ترحيلاتها الأصلية.

آخر ترحيل مسجل في الإنتاج وقت الفحص هو `20261027195426` (`guard_return_adjustment_currency`). ملفات المراحل اللاحقة في Git لا تُعد منشورة لمجرد وجودها في المستودع.

## الإجراء الآمن

1. عدم تطبيق ملفات ما بعد 27 أكتوبر مباشرة على الإنتاج.
2. تجهيز بيئة staging لها baseline مطابق لمخطط الإنتاج؛ المشروع التجريبي المستعاد حاليًا فارغ ولا يصلح لتطبيق ترحيلات المراحل اللاحقة وحدها.
3. مطابقة كل ترحيل لاحق مع المخطط الفعلي وسجل الإصدارات، ثم تطبيقها بالترتيب على staging.
4. إجراء اختبارات تكامل مصادقة لأدوار مالك المؤسسة ومديرها وموظفيها والمستخدم المفوض وغير العضو.
5. نشر دفعات الترحيلات المتحققة فقط، ثم إعادة فحص أسماء الدوال والجداول.

## حدود الفحص

العدد 34 يخص RPCs الظاهرة في ملفات Android المذكورة أعلاه، وليس كل API في المستودع أو كل مكوناته. لم تُنفذ أي كتابة أو ترحيل على قاعدة الإنتاج في هذا الفحص.
