-- Expand the initial activity directory without creating parallel category/product masters.
-- These are ROUH-maintained labels inspired by international classifications; no external code is asserted until explicitly mapped.
UPDATE public.commercial_global_business_activity_nodes
SET source_system='ROUH_ACTIVITY', source_version='1'
WHERE source_system='ISIC' AND source_code IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_business_activity_alias_locale_normalized
  ON public.commercial_global_business_activity_aliases(locale_code, normalized_alias)
  WHERE is_active;

-- Additional commonly needed activities. Keep parents and codes stable; inserts are idempotent.
INSERT INTO public.commercial_global_business_activity_nodes
(code,parent_code,name_ar,name_en,description_ar,description_en,node_kind,is_selectable,default_business_level_code,sort_order,source_system,source_version)
VALUES
('CROP_FARMING','AGRICULTURE','زراعة المحاصيل','Crop farming','زراعة المحاصيل والحبوب والخضروات والفاكهة.','Growing crops, grains, vegetables and fruit.','activity',true,'other',20,'ROUH_ACTIVITY','1'),
('LIVESTOCK_FARMING','AGRICULTURE','تربية المواشي والدواجن','Livestock and poultry farming','تربية الحيوانات والدواجن وإنتاجها.','Raising livestock and poultry.','activity',true,'other',30,'ROUH_ACTIVITY','1'),
('FISHERIES','AGRICULTURE','الصيد والاستزراع السمكي','Fishing and aquaculture','الصيد والاستزراع السمكي والأنشطة البحرية.','Fishing and aquaculture.','activity',true,'other',40,'ROUH_ACTIVITY','1'),
('FOOD_MANUFACTURING','MANUFACTURING','تصنيع الأغذية والمشروبات','Food and beverage manufacturing','تصنيع وتجهيز الأغذية والمشروبات.','Food and beverage processing.','activity',true,'factory',20,'ROUH_ACTIVITY','1'),
('TEXTILE_MANUFACTURING','MANUFACTURING','صناعة المنسوجات والملابس','Textile and apparel manufacturing','تصنيع الأقمشة والملابس والمنسوجات.','Textile and apparel manufacturing.','activity',true,'factory',30,'ROUH_ACTIVITY','1'),
('CHEMICAL_MANUFACTURING','MANUFACTURING','الصناعات الكيميائية','Chemical manufacturing','تصنيع المواد والمنتجات الكيميائية.','Manufacture of chemical products.','activity',true,'factory',40,'ROUH_ACTIVITY','1'),
('ELECTRONICS_MANUFACTURING','MANUFACTURING','تصنيع الإلكترونيات والأجهزة','Electronics and equipment manufacturing','تصنيع الأجهزة والمكونات الإلكترونية.','Manufacture of electronics and components.','activity',true,'factory',50,'ROUH_ACTIVITY','1'),
('FURNITURE_MANUFACTURING','MANUFACTURING','صناعة الأثاث','Furniture manufacturing','تصنيع الأثاث والمنتجات الخشبية.','Furniture and wood product manufacturing.','activity',true,'factory',60,'ROUH_ACTIVITY','1'),
('METALWORK_MANUFACTURING','MANUFACTURING','تشكيل المعادن والحدادة الصناعية','Metal fabrication','تشكيل المعادن وصناعة المنتجات المعدنية.','Metal fabrication and metal products.','activity',true,'factory',70,'ROUH_ACTIVITY','1'),
('ELECTRICAL_INSTALLATION','CONSTRUCTION','التمديدات الكهربائية','Electrical installation','تركيب وتمديد الأنظمة الكهربائية في المباني.','Electrical installation in buildings.','activity',true,'shop',20,'ROUH_ACTIVITY','1'),
('PLUMBING_INSTALLATION','CONSTRUCTION','السباكة وتمديدات المياه','Plumbing and water installation','تركيب وصيانة أنظمة المياه والصرف.','Plumbing and water and drainage systems.','activity',true,'shop',30,'ROUH_ACTIVITY','1'),
('PAINTING_FINISHING','CONSTRUCTION','الدهانات والتشطيبات','Painting and finishing','أعمال الدهان والتشطيبات الداخلية والخارجية.','Painting and finishing works.','activity',true,'shop',40,'ROUH_ACTIVITY','1'),
('WHOLESALE_FOOD','WHOLESALE','تجارة الجملة للمواد الغذائية','Food wholesale','توريد الأغذية والمشروبات بكميات تجارية.','Wholesale supply of food and beverages.','specialization',true,'other',10,'ROUH_ACTIVITY','1'),
('WHOLESALE_ELECTRONICS','WHOLESALE','تجارة الجملة للإلكترونيات','Electronics wholesale','توريد الأجهزة والإلكترونيات بكميات تجارية.','Wholesale supply of electronics.','specialization',true,'other',20,'ROUH_ACTIVITY','1'),
('WHOLESALE_BUILDING','WHOLESALE','تجارة الجملة لمواد البناء','Building materials wholesale','توريد مواد البناء للمقاولين والمتاجر.','Wholesale supply of building materials.','specialization',true,'other',30,'ROUH_ACTIVITY','1'),
('WHOLESALE_TEXTILES','WHOLESALE','تجارة الجملة للملابس والمنسوجات','Textile and clothing wholesale','توريد الملابس والمنسوجات بكميات تجارية.','Wholesale supply of clothing and textiles.','specialization',true,'other',40,'ROUH_ACTIVITY','1'),
('WHOLESALE_AGRICULTURE','WHOLESALE','تجارة الجملة للمنتجات الزراعية','Agricultural products wholesale','توريد المنتجات والمحاصيل الزراعية.','Wholesale supply of agricultural products.','specialization',true,'other',50,'ROUH_ACTIVITY','1'),
('WHOLESALE_MEDICAL','WHOLESALE','تجارة الجملة للمستلزمات الطبية','Medical supplies wholesale','توريد المستلزمات الطبية وفق التراخيص المعمول بها.','Wholesale supply of medical supplies subject to applicable licensing.','specialization',true,'other',60,'ROUH_ACTIVITY','1'),
('RETAIL_FOOD_SPECIALTY','FOOD_RETAIL','متاجر الأغذية المتخصصة','Specialty food retail','متاجر متخصصة في فئة غذائية معينة.','Retail stores specializing in a particular food category.','specialization',true,'shop',10,'ROUH_ACTIVITY','1'),
('RETAIL_BAKERY','FOOD_RETAIL','المخابز ومحلات المعجنات','Bakeries and pastry shops','إنتاج أو بيع المخبوزات والمعجنات.','Production or retail of bakery goods and pastries.','specialization',true,'shop',20,'ROUH_ACTIVITY','1'),
('RETAIL_BUTCHER','FOOD_RETAIL','محلات اللحوم والدواجن','Butcher and poultry shops','بيع اللحوم والدواجن وفق متطلبات السلامة المحلية.','Retail of meat and poultry subject to local food-safety requirements.','specialization',true,'shop',30,'ROUH_ACTIVITY','1'),
('RETAIL_FISH','FOOD_RETAIL','محلات الأسماك والمأكولات البحرية','Fish and seafood retail','بيع الأسماك والمأكولات البحرية.','Retail of fish and seafood.','specialization',true,'shop',40,'ROUH_ACTIVITY','1'),
('RETAIL_FRUIT_VEGETABLE','FOOD_RETAIL','محلات الخضروات والفواكه','Fruit and vegetable shops','بيع الخضروات والفواكه الطازجة.','Retail of fresh fruit and vegetables.','specialization',true,'shop',50,'ROUH_ACTIVITY','1'),
('RETAIL_DAIRY','FOOD_RETAIL','متاجر الألبان ومشتقاتها','Dairy retail','بيع الحليب والأجبان ومشتقات الألبان.','Retail of milk, cheese and dairy products.','specialization',true,'shop',60,'ROUH_ACTIVITY','1'),
('RETAIL_COFFEE_TEA','FOOD_RETAIL','متاجر القهوة والشاي','Coffee and tea retail','بيع القهوة والشاي ومستلزماتهما.','Retail of coffee, tea and related goods.','specialization',true,'shop',70,'ROUH_ACTIVITY','1'),
('RETAIL_GENERAL_MERCHANDISE','RETAIL_NON_SPECIALIZED','متجر متنوع السلع','General merchandise store','متجر يبيع مجموعات متنوعة من السلع غير المتخصصة.','Store selling a broad mix of non-specialized merchandise.','specialization',true,'shop',50,'ROUH_ACTIVITY','1'),
('RETAIL_HOUSEHOLD','RETAIL_SPECIALIZED','تجارة الأدوات المنزلية','Household goods retail','بيع الأدوات واللوازم المنزلية.','Retail of household goods.','specialization',true,'shop',90,'ROUH_ACTIVITY','1'),
('RETAIL_BOOKS_STATIONERY','RETAIL_SPECIALIZED','المكتبات والقرطاسية','Books and stationery retail','بيع الكتب والقرطاسية والمستلزمات المكتبية.','Retail of books, stationery and office supplies.','specialization',true,'shop',100,'ROUH_ACTIVITY','1'),
('RETAIL_SPORTS','RETAIL_SPECIALIZED','المستلزمات الرياضية','Sports goods retail','بيع المعدات والملابس الرياضية.','Retail of sporting goods and apparel.','specialization',true,'shop',110,'ROUH_ACTIVITY','1'),
('RETAIL_TOYS','RETAIL_SPECIALIZED','الألعاب ومستلزمات الأطفال','Toys and children goods retail','بيع الألعاب ومستلزمات الأطفال.','Retail of toys and children goods.','specialization',true,'shop',120,'ROUH_ACTIVITY','1'),
('RETAIL_JEWELRY','RETAIL_SPECIALIZED','الذهب والمجوهرات والساعات','Jewelry and watches retail','بيع الذهب والمجوهرات والساعات وفق المتطلبات المحلية.','Retail of jewelry and watches subject to local requirements.','specialization',true,'shop',130,'ROUH_ACTIVITY','1'),
('RETAIL_OPTICAL','RETAIL_SPECIALIZED','النظارات والمنتجات البصرية','Optical goods retail','بيع النظارات والمنتجات البصرية وفق اللوائح المحلية.','Retail of optical goods subject to local regulation.','specialization',true,'shop',140,'ROUH_ACTIVITY','1'),
('RETAIL_TELECOM','RETAIL_SPECIALIZED','متاجر الاتصالات وشرائح الهاتف','Telecom retail','بيع أجهزة وملحقات وخدمات الاتصالات.','Retail of telecom devices, accessories and services.','specialization',true,'shop',150,'ROUH_ACTIVITY','1'),
('RETAIL_PET_SUPPLIES','RETAIL_SPECIALIZED','مستلزمات الحيوانات الأليفة','Pet supplies retail','بيع مستلزمات الحيوانات الأليفة.','Retail of pet supplies.','specialization',true,'shop',160,'ROUH_ACTIVITY','1'),
('RETAIL_FUEL','RETAIL_SPECIALIZED','محطات الوقود','Fuel stations','بيع الوقود وفق التراخيص والاشتراطات المحلية.','Fuel retail subject to local licensing and safety rules.','specialization',true,'other',170,'ROUH_ACTIVITY','1'),
('RETAIL_GARDEN','RETAIL_SPECIALIZED','مستلزمات الحدائق والزراعة','Garden and agricultural retail','بيع مستلزمات الحدائق والزراعة.','Retail of garden and agricultural supplies.','specialization',true,'shop',180,'ROUH_ACTIVITY','1'),
('RETAIL_PHOTOGRAPHY','RETAIL_SPECIALIZED','الكاميرات ومعدات التصوير','Camera and photography retail','بيع الكاميرات ومعدات التصوير.','Retail of cameras and photography equipment.','specialization',true,'shop',190,'ROUH_ACTIVITY','1'),
('RETAIL_SECONDHAND','RETAIL_SPECIALIZED','السلع المستعملة','Second-hand goods retail','بيع السلع المستعملة مع بيان حالتها بوضوح.','Retail of second-hand goods with clear condition disclosure.','specialization',true,'shop',200,'ROUH_ACTIVITY','1'),
('RETAIL_ONLINE','RETAIL','التجارة الإلكترونية للسلع','Online retail','بيع السلع للمستهلك عبر القنوات الإلكترونية.','Retail sale of goods to consumers through online channels.','specialization',true,'shop',30,'ROUH_ACTIVITY','1'),
('REPAIR_ELECTRONICS','PERSONAL_REPAIR','صيانة الإلكترونيات والهواتف','Electronics and phone repair','صيانة وإصلاح الأجهزة الإلكترونية والهواتف.','Maintenance and repair of electronics and phones.','specialization',true,'shop',20,'ROUH_ACTIVITY','1'),
('REPAIR_AUTOMOTIVE','VEHICLE_TRADE','صيانة وإصلاح المركبات','Vehicle maintenance and repair','صيانة وإصلاح المركبات.','Vehicle maintenance and repair.','specialization',true,'shop',20,'ROUH_ACTIVITY','1'),
('REPAIR_HOME_APPLIANCES','PERSONAL_REPAIR','صيانة الأجهزة المنزلية','Home appliance repair','صيانة وإصلاح الأجهزة المنزلية.','Maintenance and repair of household appliances.','specialization',true,'shop',30,'ROUH_ACTIVITY','1'),
('LOGISTICS_WAREHOUSING','TRANSPORT','التخزين والمستودعات','Warehousing and storage','تخزين البضائع وإدارة المستودعات.','Warehousing and storage.','activity',true,'other',20,'ROUH_ACTIVITY','1'),
('LOGISTICS_DELIVERY','TRANSPORT','التوصيل وخدمات الميل الأخير','Delivery and last-mile services','توصيل البضائع والطلبات للعملاء.','Delivery of goods and orders to customers.','activity',true,'other',30,'ROUH_ACTIVITY','1'),
('LOGISTICS_FREIGHT','TRANSPORT','الشحن ونقل البضائع','Freight transport','نقل البضائع محليًا أو دوليًا.','Domestic or international freight transport.','activity',true,'other',40,'ROUH_ACTIVITY','1'),
('PROFESSIONAL_ACCOUNTING','PROFESSIONAL','المحاسبة ومسك الدفاتر','Accounting and bookkeeping','خدمات المحاسبة ومسك الدفاتر.','Accounting and bookkeeping services.','activity',true,'office',20,'ROUH_ACTIVITY','1'),
('PROFESSIONAL_LEGAL','PROFESSIONAL','الخدمات القانونية','Legal services','خدمات قانونية وفق متطلبات الترخيص المحلي.','Legal services subject to local licensing.','activity',true,'office',30,'ROUH_ACTIVITY','1'),
('PROFESSIONAL_ENGINEERING','PROFESSIONAL','الخدمات الهندسية','Engineering services','خدمات التصميم والاستشارات الهندسية.','Engineering design and consulting.','activity',true,'office',40,'ROUH_ACTIVITY','1'),
('PROFESSIONAL_MARKETING','PROFESSIONAL','التسويق والإعلان','Marketing and advertising','خدمات التسويق والإعلان وإدارة الحملات.','Marketing, advertising and campaign management.','activity',true,'office',50,'ROUH_ACTIVITY','1'),
('PROFESSIONAL_CONSULTING','PROFESSIONAL','الاستشارات الإدارية','Management consulting','خدمات الاستشارات الإدارية والتشغيلية.','Management and operations consulting.','activity',true,'office',60,'ROUH_ACTIVITY','1'),
('PROFESSIONAL_DESIGN','PROFESSIONAL','التصميم والإبداع','Design and creative services','خدمات التصميم الجرافيكي والهوية والإبداع.','Graphic, brand and creative design services.','activity',true,'office',70,'ROUH_ACTIVITY','1'),
('PROFESSIONAL_CLEANING','PROFESSIONAL','خدمات النظافة التجارية','Commercial cleaning services','خدمات النظافة للمنشآت والمكاتب.','Cleaning services for businesses and offices.','activity',true,'other',80,'ROUH_ACTIVITY','1'),
('EDUCATION_TUTORING','EDUCATION','الدروس الخصوصية والتدريب المهني','Tutoring and vocational training','الدروس والتدريب والمهارات المهنية.','Tutoring, vocational training and skills development.','activity',true,'other',20,'ROUH_ACTIVITY','1'),
('HEALTH_DENTAL','HEALTH','عيادات الأسنان','Dental clinics','خدمات طب الأسنان وفق الترخيص المحلي.','Dental services subject to local licensing.','activity',true,'clinic',20,'ROUH_ACTIVITY','1'),
('HEALTH_LAB','HEALTH','المختبرات الطبية','Medical laboratories','خدمات الفحوصات المخبرية وفق اللوائح المحلية.','Laboratory testing subject to local regulation.','activity',true,'clinic',30,'ROUH_ACTIVITY','1'),
('HEALTH_PHYSIOTHERAPY','HEALTH','العلاج الطبيعي والتأهيل','Physiotherapy and rehabilitation','خدمات العلاج الطبيعي والتأهيل.','Physiotherapy and rehabilitation services.','activity',true,'clinic',40,'ROUH_ACTIVITY','1'),
('HEALTH_VETERINARY','HEALTH','الخدمات البيطرية','Veterinary services','الرعاية البيطرية للحيوانات.','Veterinary care services.','activity',true,'clinic',50,'ROUH_ACTIVITY','1'),
('REAL_ESTATE_BROKERAGE','REAL_ESTATE','الوساطة العقارية','Real estate brokerage','وساطة بيع وتأجير العقارات.','Real estate sales and rental brokerage.','activity',true,'office',10,'ROUH_ACTIVITY','1'),
('REAL_ESTATE_MANAGEMENT','REAL_ESTATE','إدارة العقارات','Property management','إدارة وتشغيل العقارات والمرافق.','Property and facility management.','activity',true,'office',20,'ROUH_ACTIVITY','1'),
('FINANCIAL_INSURANCE','FINANCE','التأمين والخدمات التأمينية','Insurance services','خدمات التأمين وفق اللوائح المحلية.','Insurance services subject to local regulation.','activity',true,'office',10,'ROUH_ACTIVITY','1'),
('FINANCIAL_EXCHANGE','FINANCE','الصرافة وتحويل الأموال','Currency exchange and remittance','خدمات الصرافة والتحويل ضمن الترخيص المحلي.','Currency exchange and remittance subject to local licensing.','activity',true,'office',20,'ROUH_ACTIVITY','1'),
('PERSONAL_BEAUTY','PERSONAL_SERVICES','صالونات التجميل والعناية الشخصية','Beauty salons and personal care','خدمات التجميل والعناية الشخصية.','Beauty and personal care services.','activity',true,'shop',20,'ROUH_ACTIVITY','1'),
('PERSONAL_BARBER','PERSONAL_SERVICES','الحلاقة','Barber shops','خدمات الحلاقة.','Barber services.','activity',true,'shop',30,'ROUH_ACTIVITY','1'),
('PERSONAL_LAUNDRY','PERSONAL_SERVICES','المغاسل وخدمات الغسيل','Laundry and dry cleaning','خدمات الغسيل والتنظيف الجاف.','Laundry and dry cleaning services.','activity',true,'shop',40,'ROUH_ACTIVITY','1'),
('PERSONAL_EVENT','PERSONAL_SERVICES','تنظيم الفعاليات والمناسبات','Event and occasion services','تنظيم الفعاليات والمناسبات.','Event and occasion organization.','activity',true,'other',50,'ROUH_ACTIVITY','1'),
('INFORMATION_IT_SUPPORT','INFORMATION','دعم تقنية المعلومات والشبكات','IT support and networking','خدمات دعم الحاسوب والشبكات.','Computer and network support services.','activity',true,'office',20,'ROUH_ACTIVITY','1'),
('INFORMATION_WEB','INFORMATION','تصميم وتطوير المواقع','Web design and development','تصميم وتطوير المواقع الإلكترونية.','Website design and development.','activity',true,'office',30,'ROUH_ACTIVITY','1'),
('INFORMATION_TELECOM','INFORMATION','خدمات الاتصالات','Telecommunications services','خدمات الاتصالات والشبكات.','Telecommunications and network services.','activity',true,'office',40,'ROUH_ACTIVITY','1'),
('HOSPITALITY_CATERING','HOSPITALITY','التموين وإعداد الولائم','Catering services','إعداد وتقديم الطعام للفعاليات والمنشآت.','Food preparation and service for events and organizations.','activity',true,'shop',30,'ROUH_ACTIVITY','1'),
('HOSPITALITY_CAFE','HOSPITALITY','المقاهي ومحلات القهوة','Cafes and coffee shops','تقديم المشروبات والوجبات الخفيفة.','Beverage and light meal service.','activity',true,'shop',40,'ROUH_ACTIVITY','1'),
('HOSPITALITY_FAST_FOOD','HOSPITALITY','الوجبات السريعة','Fast-food restaurants','إعداد وبيع الوجبات السريعة.','Preparation and sale of fast food.','activity',true,'shop',50,'ROUH_ACTIVITY','1'),
('HOSPITALITY_BAKERY','HOSPITALITY','المخابز الإنتاجية','Production bakeries','إنتاج المخبوزات للبيع بالجملة أو التجزئة.','Production bakeries for wholesale or retail.','activity',true,'factory',60,'ROUH_ACTIVITY','1')
ON CONFLICT (code) DO NOTHING;

-- Add reviewed searchable names to the shared translation store without duplicating the translation master.
INSERT INTO public.commercial_global_entity_translations
(entity_type,entity_key,locale_code,field_key,translated_value,translation_status,source_language)
SELECT 'business_activity',code,'ar','name',name_ar,'official','ar'
FROM public.commercial_global_business_activity_nodes
ON CONFLICT (entity_type,entity_key,locale_code,field_key) DO NOTHING;
INSERT INTO public.commercial_global_entity_translations
(entity_type,entity_key,locale_code,field_key,translated_value,translation_status,source_language)
SELECT 'business_activity',code,'en','name',name_en,'official','en'
FROM public.commercial_global_business_activity_nodes
ON CONFLICT (entity_type,entity_key,locale_code,field_key) DO NOTHING;

INSERT INTO public.commercial_global_business_activity_aliases(activity_node_id,locale_code,alias,source)
SELECT n.id,v.locale_code,v.alias,'curated'
FROM (VALUES
 ('GROCERY','ar','دكان'),('GROCERY','ar','بقالة الحارة'),('GROCERY','en','mini market'),
 ('RETAIL_BAKERY','ar','مخبز'),('RETAIL_BAKERY','ar','مخبوزات'),('RETAIL_BAKERY','en','bakery'),
 ('RETAIL_BUTCHER','ar','جزارة'),('RETAIL_BUTCHER','ar','ملحمة'),('RETAIL_BUTCHER','en','butcher'),
 ('RETAIL_FRUIT_VEGETABLE','ar','خضار وفواكه'),('RETAIL_FRUIT_VEGETABLE','en','greengrocer'),
 ('RETAIL_BOOKS_STATIONERY','ar','قرطاسية'),('RETAIL_BOOKS_STATIONERY','en','stationery shop'),
 ('RETAIL_HOUSEHOLD','ar','أدوات منزلية'),('RETAIL_HOUSEHOLD','en','household store'),
 ('REPAIR_ELECTRONICS','ar','صيانة جوالات'),('REPAIR_ELECTRONICS','en','phone repair'),
 ('PERSONAL_BEAUTY','ar','كوافير'),('PERSONAL_BEAUTY','en','beauty salon'),
 ('PERSONAL_LAUNDRY','ar','مغسلة'),('PERSONAL_LAUNDRY','en','laundry'),
 ('HOSPITALITY_CAFE','ar','كافيه'),('HOSPITALITY_CAFE','en','coffee shop'),
 ('HOSPITALITY_FAST_FOOD','ar','مطعم وجبات سريعة'),('HOSPITALITY_FAST_FOOD','en','fast food')
) AS v(code,locale_code,alias)
JOIN public.commercial_global_business_activity_nodes n ON n.code=v.code
ON CONFLICT (activity_node_id,locale_code,normalized_alias) DO NOTHING;
