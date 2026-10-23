-- Starter multi-industry taxonomy and generic product templates.
-- These are unbranded templates: do not infer manufacturer, barcode, medical status or religious ruling.
INSERT INTO public.commercial_global_catalog_categories
  (code,parent_code,name_ar,name_en,description_ar,description_en,sort_order,is_active,version)
VALUES
 ('HEALTHCARE-GROUP',NULL,'الصحة والرعاية الطبية','Healthcare & Medical','تصنيف عام للصحة والرعاية الطبية','General healthcare and medical catalog',100,true,1),
 ('MEDICAL-CONSUMABLES','HEALTHCARE-GROUP','المستهلكات الطبية','Medical Consumables','مستهلكات عامة تتطلب مراجعة المواصفات التنظيمية','Generic consumables; verify regulatory specifications',101,true,1),
 ('MEDICAL-PPE','MEDICAL-CONSUMABLES','معدات الوقاية الشخصية','Medical PPE','معدات وقاية عامة','Generic protective equipment',102,true,1),
 ('WOUND-CARE','MEDICAL-CONSUMABLES','العناية بالجروح والضمادات','Wound Care & Dressings','مستلزمات عامة للعناية بالجروح','Generic wound-care supplies',103,true,1),
 ('DIAGNOSTIC-SUPPLIES','MEDICAL-CONSUMABLES','مستلزمات الفحص والتشخيص','Diagnostic Supplies','مستلزمات تشخيص عامة','Generic diagnostic supplies',104,true,1),
 ('LAB-SUPPLIES','HEALTHCARE-GROUP','مستلزمات المختبرات','Laboratory Supplies','مستلزمات مختبر عامة','Generic laboratory supplies',105,true,1),
 ('PHARMACY-SUPPLIES','HEALTHCARE-GROUP','مستلزمات الصيدليات','Pharmacy Supplies','عبوات وتنظيم ومستلزمات غير دوائية','Packaging and non-medicine pharmacy supplies',106,true,1),
 ('HOSPITAL-LINEN','HEALTHCARE-GROUP','مفروشات المستشفيات','Hospital Linen','مفروشات عامة مع ضرورة مراجعة مواصفات المنشأة','Generic linen; verify facility requirements',107,true,1),
 ('MOBILE-GROUP',NULL,'الجوالات والاتصالات','Mobile & Telecommunications','تصنيف عام لأجهزة الجوال وملحقاتها','Mobile devices and accessories',200,true,1),
 ('MOBILE-DEVICES','MOBILE-GROUP','الأجهزة المحمولة','Mobile Devices','أجهزة عامة دون تحديد علامة تجارية','Generic devices without brand assumptions',201,true,1),
 ('MOBILE-ACCESSORIES','MOBILE-GROUP','ملحقات الجوال','Mobile Accessories','ملحقات عامة','Generic mobile accessories',202,true,1),
 ('NETWORK-EQUIPMENT','MOBILE-GROUP','معدات الشبكات','Network Equipment','معدات شبكات عامة','Generic networking equipment',203,true,1),
 ('AUTOMOTIVE-GROUP',NULL,'السيارات وقطع الغيار','Automotive & Parts','تصنيف عام للسيارات وقطعها','Automotive products and parts',300,true,1),
 ('AUTO-FILTERS','AUTOMOTIVE-GROUP','فلاتر السيارات','Automotive Filters','فلاتر عامة؛ يلزم مطابقة رقم القطعة','Generic filters; verify exact part fitment',301,true,1),
 ('AUTO-FLUIDS','AUTOMOTIVE-GROUP','سوائل وزيوت السيارات','Automotive Fluids','سوائل عامة؛ تحقق من المواصفة المعتمدة','Generic fluids; verify approved specification',302,true,1),
 ('AUTO-REPAIR-PARTS','AUTOMOTIVE-GROUP','قطع الصيانة والإصلاح','Repair & Service Parts','قطع عامة؛ تحقق من التوافق مع الطراز','Generic parts; verify model compatibility',303,true,1),
 ('HEAVY-EQUIPMENT-GROUP',NULL,'المعدات الثقيلة','Heavy Equipment','تصنيف عام للمعدات الثقيلة وقطعها','Heavy equipment and parts',400,true,1),
 ('HEAVY-SPARES','HEAVY-EQUIPMENT-GROUP','قطع غيار المعدات الثقيلة','Heavy Equipment Spares','قطع عامة؛ يلزم التحقق من رقم القطعة','Generic spares; verify exact part number',401,true,1),
 ('HEAVY-HYDRAULICS','HEAVY-EQUIPMENT-GROUP','الهيدروليك والخراطيم','Hydraulics & Hoses','مكونات عامة؛ تحقق من الضغط والمقاس','Generic components; verify pressure and size',402,true,1),
 ('HEAVY-SAFETY','HEAVY-EQUIPMENT-GROUP','السلامة المهنية','Heavy Equipment Safety','مستلزمات سلامة عامة','Generic safety supplies',403,true,1),
 ('HOSPITALITY-GROUP',NULL,'الفنادق والمطاعم والضيافة','Hospitality','مستلزمات تشغيل الضيافة','Hospitality operating supplies',500,true,1),
 ('HOTEL-AMENITIES','HOSPITALITY-GROUP','مستلزمات غرف الفنادق','Hotel Guest Amenities','مستلزمات عامة للنزلاء','Generic guest amenities',501,true,1),
 ('HOTEL-LINEN','HOSPITALITY-GROUP','مفروشات ومناشف الفنادق','Hotel Linen & Towels','مفروشات عامة للفنادق','Generic hotel linen',502,true,1),
 ('RESTAURANT-PACKAGING','HOSPITALITY-GROUP','تغليف وعبوات المطاعم','Restaurant Packaging','عبوات عامة؛ تحقق من ملاءمتها للغذاء','Generic packaging; verify food-contact suitability',503,true,1),
 ('RESTAURANT-KITCHEN','HOSPITALITY-GROUP','معدات وأدوات المطابخ','Restaurant Kitchenware','معدات وأدوات عامة للمطابخ','Generic kitchenware',504,true,1),
 ('AGRICULTURE-GROUP',NULL,'الزراعة والري','Agriculture & Irrigation','مستلزمات عامة للزراعة والري','Agriculture and irrigation supplies',600,true,1),
 ('AGRI-IRRIGATION','AGRICULTURE-GROUP','مستلزمات الري','Irrigation Supplies','تحقق من المقاس والتوافق','Verify dimensions and compatibility',601,true,1),
 ('AGRI-TOOLS','AGRICULTURE-GROUP','الأدوات الزراعية','Agricultural Tools','أدوات عامة','Generic agricultural tools',602,true,1),
 ('MANUFACTURING-GROUP',NULL,'التصنيع والمواد الخام','Manufacturing & Raw Materials','مواد ومستلزمات تشغيل عامة','General manufacturing supplies',700,true,1),
 ('MANUFACTURING-FASTENERS','MANUFACTURING-GROUP','المثبتات والبراغي','Fasteners','تحقق من القياس والمادة والمعيار','Verify size, material and standard',701,true,1),
 ('MANUFACTURING-PACKAGING','MANUFACTURING-GROUP','مواد التغليف الصناعي','Industrial Packaging','مواد تغليف عامة','Generic industrial packaging',702,true,1),
 ('MANUFACTURING-SAFETY','MANUFACTURING-GROUP','سلامة العاملين','Manufacturing Safety','معدات وقاية عامة','Generic worker safety equipment',703,true,1),
 ('OFFICE-PRINTING','OFFICE','الطباعة وأحبارها','Printing & Toner','تحقق من توافق الطابعة والطراز','Verify printer model compatibility',31,true,1),
 ('ELECTRONICS-ACCESSORIES','ELECTRICAL','ملحقات الإلكترونيات','Electronics Accessories','ملحقات عامة دون تحديد علامة تجارية','Generic accessories without brand assumptions',52,true,1)
ON CONFLICT (code) DO NOTHING;

INSERT INTO public.commercial_global_entity_translations
  (entity_type,entity_key,locale_code,field_key,translated_value,source_language)
SELECT 'catalog_category',c.code,'ar','name',c.name_ar,'ar'
FROM public.commercial_global_catalog_categories c
WHERE c.code IN (
 'HEALTHCARE-GROUP','MEDICAL-CONSUMABLES','MEDICAL-PPE','WOUND-CARE','DIAGNOSTIC-SUPPLIES','LAB-SUPPLIES','PHARMACY-SUPPLIES','HOSPITAL-LINEN',
 'MOBILE-GROUP','MOBILE-DEVICES','MOBILE-ACCESSORIES','NETWORK-EQUIPMENT',
 'AUTOMOTIVE-GROUP','AUTO-FILTERS','AUTO-FLUIDS','AUTO-REPAIR-PARTS',
 'HEAVY-EQUIPMENT-GROUP','HEAVY-SPARES','HEAVY-HYDRAULICS','HEAVY-SAFETY',
 'HOSPITALITY-GROUP','HOTEL-AMENITIES','HOTEL-LINEN','RESTAURANT-PACKAGING','RESTAURANT-KITCHEN',
 'AGRICULTURE-GROUP','AGRI-IRRIGATION','AGRI-TOOLS','MANUFACTURING-GROUP','MANUFACTURING-FASTENERS','MANUFACTURING-PACKAGING','MANUFACTURING-SAFETY',
 'OFFICE-PRINTING','ELECTRONICS-ACCESSORIES'
)
ON CONFLICT DO NOTHING;
INSERT INTO public.commercial_global_entity_translations
  (entity_type,entity_key,locale_code,field_key,translated_value,source_language)
SELECT 'catalog_category',c.code,'en','name',c.name_en,'en'
FROM public.commercial_global_catalog_categories c
WHERE c.code IN (
 'HEALTHCARE-GROUP','MEDICAL-CONSUMABLES','MEDICAL-PPE','WOUND-CARE','DIAGNOSTIC-SUPPLIES','LAB-SUPPLIES','PHARMACY-SUPPLIES','HOSPITAL-LINEN',
 'MOBILE-GROUP','MOBILE-DEVICES','MOBILE-ACCESSORIES','NETWORK-EQUIPMENT',
 'AUTOMOTIVE-GROUP','AUTO-FILTERS','AUTO-FLUIDS','AUTO-REPAIR-PARTS',
 'HEAVY-EQUIPMENT-GROUP','HEAVY-SPARES','HEAVY-HYDRAULICS','HEAVY-SAFETY',
 'HOSPITALITY-GROUP','HOTEL-AMENITIES','HOTEL-LINEN','RESTAURANT-PACKAGING','RESTAURANT-KITCHEN',
 'AGRICULTURE-GROUP','AGRI-IRRIGATION','AGRI-TOOLS','MANUFACTURING-GROUP','MANUFACTURING-FASTENERS','MANUFACTURING-PACKAGING','MANUFACTURING-SAFETY',
 'OFFICE-PRINTING','ELECTRONICS-ACCESSORIES'
)
ON CONFLICT DO NOTHING;

INSERT INTO public.commercial_global_catalog_items
  (catalog_code,name_ar,name_en,description_ar,description_en,category_code,item_type,unit_code,attributes_schema)
VALUES
 ('MED-GLOVES-NITRILE','قفازات نيتريل','Nitrile gloves','قالب عام؛ أكمل المقاس والشركة المصنعة والباركود والمواصفات قبل الاستخدام.','Generic template; add size, manufacturer, barcode and specifications before use.','MEDICAL-PPE','product','BOX','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('MED-SURGICAL-MASK','كمامات طبية','Surgical masks','قالب عام؛ تحقق من التصنيف والمعيار المعتمد قبل الشراء.','Generic template; verify approved classification and standard before purchase.','MEDICAL-PPE','product','BOX','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('MED-GAUZE-DRESSING','شاش طبي','Medical gauze','قالب عام؛ أكمل المقاس والتعبئة والمواصفات.','Generic template; add size, packaging and specifications.','WOUND-CARE','product','BOX','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('MED-ELASTIC-BANDAGE','رباط ضاغط مرن','Elastic bandage','قالب عام؛ أكمل المقاس والمادة.','Generic template; add size and material.','WOUND-CARE','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('MED-COTTON-WOOL','قطن طبي','Medical cotton wool','قالب عام؛ تحقق من الوزن والتعبئة.','Generic template; verify weight and packaging.','WOUND-CARE','product','BOX','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('MED-DIGITAL-THERMOMETER','ميزان حرارة رقمي','Digital thermometer','قالب عام؛ أكمل الشركة المصنعة والمواصفات التنظيمية.','Generic template; add manufacturer and regulatory specifications.','DIAGNOSTIC-SUPPLIES','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('MED-DISPOSABLE-SYRINGE','حقنة للاستعمال الواحد','Disposable syringe','قالب عام؛ أكمل السعة والمقاس والتصنيف المعتمد.','Generic template; add capacity, size and approved classification.','MEDICAL-CONSUMABLES','product','BOX','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('MED-IV-SET','طقم إعطاء محاليل','IV administration set','قالب عام؛ تحقق من المواصفات التنظيمية والتوافق.','Generic template; verify regulatory specifications and compatibility.','MEDICAL-CONSUMABLES','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('MED-ALCOHOL-SWAB','مسحات تنظيف طبية','Medical cleansing swabs','قالب عام؛ تحقق من التركيب والتصنيف.','Generic template; verify composition and classification.','MEDICAL-CONSUMABLES','product','BOX','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('MED-HAND-SANITIZER','معقم يدين','Hand sanitizer','قالب عام؛ أكمل التركيب والحجم والتحذيرات.','Generic template; add ingredients, volume and warnings.','MEDICAL-CONSUMABLES','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('MED-HOSPITAL-GOWN','رداء طبي','Medical gown','قالب عام؛ أكمل المقاس والخامة والتصنيف.','Generic template; add size, material and classification.','MEDICAL-PPE','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('MED-SHOE-COVER','غطاء أحذية للاستعمال الواحد','Disposable shoe covers','قالب عام؛ أكمل المقاس والخامة والتعبئة.','Generic template; add size, material and packaging.','MEDICAL-PPE','product','BOX','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('MED-SPECIMEN-CUP','وعاء عينات مخبرية','Laboratory specimen container','قالب عام؛ أكمل الحجم ومواصفات العينة والتعبئة.','Generic template; add volume, specimen compatibility and packaging.','LAB-SUPPLIES','product','BOX','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('MED-TEST-TUBE','أنبوب اختبار مخبري','Laboratory test tube','قالب عام؛ أكمل المادة والحجم والتوافق.','Generic template; add material, volume and compatibility.','LAB-SUPPLIES','product','BOX','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('MED-MICROSCOPE-SLIDE','شريحة مجهرية','Microscope slide','قالب عام؛ أكمل الأبعاد والخامة والتعبئة.','Generic template; add dimensions, material and packaging.','LAB-SUPPLIES','product','BOX','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('PHARMACY-MEDICINE-BOTTLE','عبوة دواء فارغة','Empty medicine bottle','قالب عام غير دوائي؛ أكمل المادة والحجم وملاءمة التعبئة.','Non-medicine generic packaging template; add material, volume and packaging suitability.','PHARMACY-SUPPLIES','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('PHARMACY-PILL-ORGANIZER','منظم حبوب','Pill organizer','قالب عام؛ أكمل المادة والأبعاد.','Generic template; add material and dimensions.','PHARMACY-SUPPLIES','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('PHONE-SMARTPHONE','هاتف ذكي','Smartphone','قالب عام؛ أضف العلامة التجارية والطراز والسعة والباركود.','Generic template; add brand, model, storage capacity and barcode.','MOBILE-DEVICES','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('PHONE-CHARGER','شاحن هاتف','Phone charger','قالب عام؛ تحقق من القدرة والموصل والتوافق.','Generic template; verify wattage, connector and compatibility.','MOBILE-ACCESSORIES','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('PHONE-USB-CABLE','كابل شحن وبيانات','USB charging/data cable','قالب عام؛ أكمل الطول والموصل ومعدل النقل.','Generic template; add length, connector and data rate.','MOBILE-ACCESSORIES','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('PHONE-CASE','غطاء هاتف','Phone case','قالب عام؛ حدد الطراز والمادة واللون.','Generic template; specify model, material and color.','MOBILE-ACCESSORIES','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('PHONE-SCREEN-PROTECTOR','واقي شاشة هاتف','Phone screen protector','قالب عام؛ حدد الطراز والمقاس والمادة.','Generic template; specify model, size and material.','MOBILE-ACCESSORIES','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('PHONE-POWER-BANK','بطارية شحن متنقلة','Power bank','قالب عام؛ تحقق من السعة والقدرة وشهادات السلامة.','Generic template; verify capacity, output and safety certifications.','MOBILE-ACCESSORIES','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('PHONE-EARBUDS','سماعات أذن','Earbuds','قالب عام؛ أضف العلامة والطراز والموصل.','Generic template; add brand, model and connector.','MOBILE-ACCESSORIES','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('NETWORK-ROUTER','موجه شبكة','Network router','قالب عام؛ أكمل المواصفات والمنافذ والمعايير.','Generic template; add specifications, ports and standards.','NETWORK-EQUIPMENT','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('NETWORK-SIM-CARD','شريحة اتصال','SIM card','قالب عام؛ حدد نوع الشريحة والمشغل والمنطقة.','Generic template; specify SIM type, carrier and region.','NETWORK-EQUIPMENT','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('AUTO-OIL-FILTER','فلتر زيت سيارة','Automotive oil filter','قالب عام؛ تحقق من رقم القطعة وتوافق المركبة.','Generic template; verify part number and vehicle fitment.','AUTO-FILTERS','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('AUTO-AIR-FILTER','فلتر هواء سيارة','Automotive air filter','قالب عام؛ تحقق من رقم القطعة وتوافق المركبة.','Generic template; verify part number and vehicle fitment.','AUTO-FILTERS','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('AUTO-ENGINE-OIL','زيت محرك','Engine oil','قالب عام؛ لا تستخدمه قبل تحديد اللزوجة والمواصفة المعتمدة.','Generic template; specify viscosity and approved standard before use.','AUTO-FLUIDS','product','L','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('AUTO-COOLANT','سائل تبريد مركبات','Automotive coolant','قالب عام؛ تحقق من المواصفة والتوافق قبل الاستخدام.','Generic template; verify specification and compatibility before use.','AUTO-FLUIDS','product','L','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('AUTO-TIRE','إطار مركبة','Vehicle tire','قالب عام؛ أكمل المقاس ومؤشرات الحمولة والسرعة.','Generic template; add size, load and speed ratings.','AUTO-REPAIR-PARTS','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('AUTO-BATTERY','بطارية مركبة','Vehicle battery','قالب عام؛ أكمل الجهد والسعة والأبعاد والتوافق.','Generic template; add voltage, capacity, dimensions and compatibility.','AUTO-REPAIR-PARTS','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('AUTO-BRAKE-PADS','بطانات فرامل','Brake pads','قالب عام؛ تحقق من المركبة ورقم القطعة.','Generic template; verify vehicle and part number.','AUTO-REPAIR-PARTS','product','SET','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('AUTO-SPARK-PLUG','شمعة إشعال','Spark plug','قالب عام؛ تحقق من رقم القطعة ومواصفات المحرك.','Generic template; verify part number and engine specifications.','AUTO-REPAIR-PARTS','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('HEAVY-HYDRAULIC-HOSE','خرطوم هيدروليكي','Hydraulic hose','قالب عام؛ حدد الطول والقطر وتصنيف الضغط.','Generic template; specify length, diameter and pressure rating.','HEAVY-HYDRAULICS','product','M','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('HEAVY-HYDRAULIC-FILTER','فلتر هيدروليك','Hydraulic filter','قالب عام؛ تحقق من رقم القطعة والتوافق.','Generic template; verify part number and compatibility.','HEAVY-SPARES','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('HEAVY-TRACK-PAD','وسادة جنزير معدات ثقيلة','Heavy equipment track pad','قالب عام؛ حدد الطراز والأبعاد.','Generic template; specify model and dimensions.','HEAVY-SPARES','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('HEAVY-BUCKET-TOOTH','سن دلو حفارة','Excavator bucket tooth','قالب عام؛ تحقق من رقم القطعة والطراز.','Generic template; verify part number and model.','HEAVY-SPARES','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('HEAVY-WORK-LIGHT','مصباح عمل للمعدات','Equipment work light','قالب عام؛ أكمل الجهد والقدرة وتصنيف الحماية.','Generic template; add voltage, wattage and ingress rating.','HEAVY-SAFETY','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('HEAVY-SAFETY-VEST','سترة سلامة عاكسة','Reflective safety vest','قالب عام؛ أكمل المقاس وتصنيف الرؤية.','Generic template; add size and visibility class.','HEAVY-SAFETY','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('HOTEL-TOWEL','منشفة فندقية','Hotel towel','قالب عام؛ حدد المقاس والخامة والوزن.','Generic template; specify size, material and weight.','HOTEL-LINEN','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('HOTEL-BED-LINEN','مفروشات سرير فندقية','Hotel bed linen','قالب عام؛ حدد المقاس والخامة.','Generic template; specify size and material.','HOTEL-LINEN','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('HOTEL-GUEST-SOAP','صابون ضيافة','Guest soap','قالب عام؛ أكمل الوزن والمكونات والتحذيرات.','Generic template; add weight, ingredients and warnings.','HOTEL-AMENITIES','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('HOTEL-SHAMPOO','شامبو ضيافة','Guest shampoo','قالب عام؛ أكمل الحجم والمكونات والتحذيرات.','Generic template; add volume, ingredients and warnings.','HOTEL-AMENITIES','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('RESTAURANT-TAKEOUT-BOX','علبة طعام سفري','Takeout food container','قالب عام؛ تحقق من المادة وملاءمة ملامسة الغذاء.','Generic template; verify material and food-contact suitability.','RESTAURANT-PACKAGING','product','BOX','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('RESTAURANT-PAPER-CUP','كوب ورقي','Paper cup','قالب عام؛ حدد السعة وملاءمة المشروبات الساخنة.','Generic template; specify capacity and hot-drink suitability.','RESTAURANT-PACKAGING','product','BOX','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('RESTAURANT-NAPKIN','مناديل مطاعم','Restaurant napkins','قالب عام؛ حدد المقاس وعدد القطع.','Generic template; specify size and count.','RESTAURANT-PACKAGING','product','BOX','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('RESTAURANT-CUTLERY','أدوات مائدة للاستعمال الواحد','Disposable cutlery','قالب عام؛ تحقق من المادة وملاءمة الغذاء.','Generic template; verify material and food-contact suitability.','RESTAURANT-PACKAGING','product','BOX','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('RESTAURANT-APRON','مريلة مطبخ','Kitchen apron','قالب عام؛ حدد الخامة والمقاس.','Generic template; specify material and size.','RESTAURANT-KITCHEN','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('AGRI-IRRIGATION-PIPE','أنبوب ري','Irrigation pipe','قالب عام؛ حدد القطر والخامة وتصنيف الضغط.','Generic template; specify diameter, material and pressure rating.','AGRI-IRRIGATION','product','M','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('AGRI-IRRIGATION-VALVE','صمام ري','Irrigation valve','قالب عام؛ حدد المقاس والخامة والتوصيل.','Generic template; specify size, material and connection.','AGRI-IRRIGATION','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('AGRI-PLANT-POT','وعاء زراعة','Plant pot','قالب عام؛ حدد القطر والخامة.','Generic template; specify diameter and material.','AGRI-TOOLS','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('AGRI-HAND-TOOL','أداة زراعية يدوية','Agricultural hand tool','قالب عام؛ حدد النوع والخامة والمقاس.','Generic template; specify type, material and size.','AGRI-TOOLS','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('AGRI-SEED-PACK','عبوة بذور زراعية','Agricultural seed pack','قالب عام؛ حدد النوع والصنف والوزن وتاريخ الصلاحية.','Generic template; specify species, variety, weight and expiry.','AGRI-TOOLS','product','BOX','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('MANUF-BOLT','برغي صناعي','Industrial bolt','قالب عام؛ حدد المقاس والمادة والمعيار.','Generic template; specify size, material and standard.','MANUFACTURING-FASTENERS','material','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('MANUF-NUT','صامولة صناعية','Industrial nut','قالب عام؛ حدد المقاس والمادة والمعيار.','Generic template; specify size, material and standard.','MANUFACTURING-FASTENERS','material','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('MANUF-STRETCH-FILM','غشاء تغليف مطاطي','Stretch wrapping film','قالب عام؛ حدد العرض والسماكة والطول.','Generic template; specify width, thickness and length.','MANUFACTURING-PACKAGING','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('MANUF-CARTON','كرتون تغليف','Packaging carton','قالب عام؛ حدد الأبعاد ودرجة التحمل.','Generic template; specify dimensions and strength grade.','MANUFACTURING-PACKAGING','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('MANUF-SAFETY-GOGGLES','نظارات حماية صناعية','Industrial safety goggles','قالب عام؛ تحقق من معيار الحماية.','Generic template; verify the applicable protection standard.','MANUFACTURING-SAFETY','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('MANUF-INDUSTRIAL-GLOVE','قفازات عمل صناعية','Industrial work gloves','قالب عام؛ حدد المادة والمقاس وتصنيف الحماية.','Generic template; specify material, size and protection class.','MANUFACTURING-SAFETY','product','PAIR','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('OFFICE-TONER-CARTRIDGE','خرطوشة حبر طابعة','Printer toner cartridge','قالب عام؛ تحقق من طراز الطابعة ورقم القطعة.','Generic template; verify printer model and part number.','OFFICE-PRINTING','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('OFFICE-FILE-FOLDER','ملف أوراق','File folder','قالب عام؛ حدد المقاس والخامة.','Generic template; specify size and material.','OFFICE-SUPPLIES','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb),
 ('OFFICE-STAPLER','دباسة مكتبية','Office stapler','قالب عام؛ حدد نوع الدبابيس والسعة.','Generic template; specify staple type and capacity.','OFFICE-SUPPLIES','product','EA','{"catalog_template":true,"requires_review":true}'::jsonb)
ON CONFLICT (catalog_code) DO NOTHING;

-- Give every generic starter template a canonical unit row; unknown pack conversions and barcodes are intentionally not fabricated.
INSERT INTO public.commercial_catalog_product_units(
  product_id,unit_code,unit_role,quantity_in_base,is_default,is_active
)
SELECT i.id,i.unit_code,'base',1,true,true
FROM public.commercial_global_catalog_items i
WHERE i.owner_organization_id IS NULL
  AND i.attributes_schema->>'catalog_template'='true'
ON CONFLICT (product_id,unit_code,unit_role,package_name) DO NOTHING;

INSERT INTO public.commercial_global_entity_translations
  (entity_type,entity_key,locale_code,field_key,translated_value,source_language)
SELECT 'catalog_item',i.catalog_code,'ar','name',i.name_ar,'ar'
FROM public.commercial_global_catalog_items i
WHERE i.catalog_code IN (
 'MED-GLOVES-NITRILE','MED-SURGICAL-MASK','MED-GAUZE-DRESSING','MED-ELASTIC-BANDAGE','MED-COTTON-WOOL','MED-DIGITAL-THERMOMETER','MED-DISPOSABLE-SYRINGE','MED-IV-SET','MED-ALCOHOL-SWAB','MED-HAND-SANITIZER','MED-HOSPITAL-GOWN','MED-SHOE-COVER','MED-SPECIMEN-CUP','MED-TEST-TUBE','MED-MICROSCOPE-SLIDE','PHARMACY-MEDICINE-BOTTLE','PHARMACY-PILL-ORGANIZER',
 'PHONE-SMARTPHONE','PHONE-CHARGER','PHONE-USB-CABLE','PHONE-CASE','PHONE-SCREEN-PROTECTOR','PHONE-POWER-BANK','PHONE-EARBUDS','NETWORK-ROUTER','NETWORK-SIM-CARD',
 'AUTO-OIL-FILTER','AUTO-AIR-FILTER','AUTO-ENGINE-OIL','AUTO-COOLANT','AUTO-TIRE','AUTO-BATTERY','AUTO-BRAKE-PADS','AUTO-SPARK-PLUG',
 'HEAVY-HYDRAULIC-HOSE','HEAVY-HYDRAULIC-FILTER','HEAVY-TRACK-PAD','HEAVY-BUCKET-TOOTH','HEAVY-WORK-LIGHT','HEAVY-SAFETY-VEST',
 'HOTEL-TOWEL','HOTEL-BED-LINEN','HOTEL-GUEST-SOAP','HOTEL-SHAMPOO','RESTAURANT-TAKEOUT-BOX','RESTAURANT-PAPER-CUP','RESTAURANT-NAPKIN','RESTAURANT-CUTLERY','RESTAURANT-APRON',
 'AGRI-IRRIGATION-PIPE','AGRI-IRRIGATION-VALVE','AGRI-PLANT-POT','AGRI-HAND-TOOL','AGRI-SEED-PACK',
 'MANUF-BOLT','MANUF-NUT','MANUF-STRETCH-FILM','MANUF-CARTON','MANUF-SAFETY-GOGGLES','MANUF-INDUSTRIAL-GLOVE',
 'OFFICE-TONER-CARTRIDGE','OFFICE-FILE-FOLDER','OFFICE-STAPLER'
)
ON CONFLICT DO NOTHING;
INSERT INTO public.commercial_global_entity_translations
  (entity_type,entity_key,locale_code,field_key,translated_value,source_language)
SELECT 'catalog_item',i.catalog_code,'en','name',i.name_en,'en'
FROM public.commercial_global_catalog_items i
WHERE i.catalog_code IN (
 'MED-GLOVES-NITRILE','MED-SURGICAL-MASK','MED-GAUZE-DRESSING','MED-ELASTIC-BANDAGE','MED-COTTON-WOOL','MED-DIGITAL-THERMOMETER','MED-DISPOSABLE-SYRINGE','MED-IV-SET','MED-ALCOHOL-SWAB','MED-HAND-SANITIZER','MED-HOSPITAL-GOWN','MED-SHOE-COVER','MED-SPECIMEN-CUP','MED-TEST-TUBE','MED-MICROSCOPE-SLIDE','PHARMACY-MEDICINE-BOTTLE','PHARMACY-PILL-ORGANIZER',
 'PHONE-SMARTPHONE','PHONE-CHARGER','PHONE-USB-CABLE','PHONE-CASE','PHONE-SCREEN-PROTECTOR','PHONE-POWER-BANK','PHONE-EARBUDS','NETWORK-ROUTER','NETWORK-SIM-CARD',
 'AUTO-OIL-FILTER','AUTO-AIR-FILTER','AUTO-ENGINE-OIL','AUTO-COOLANT','AUTO-TIRE','AUTO-BATTERY','AUTO-BRAKE-PADS','AUTO-SPARK-PLUG',
 'HEAVY-HYDRAULIC-HOSE','HEAVY-HYDRAULIC-FILTER','HEAVY-TRACK-PAD','HEAVY-BUCKET-TOOTH','HEAVY-WORK-LIGHT','HEAVY-SAFETY-VEST',
 'HOTEL-TOWEL','HOTEL-BED-LINEN','HOTEL-GUEST-SOAP','HOTEL-SHAMPOO','RESTAURANT-TAKEOUT-BOX','RESTAURANT-PAPER-CUP','RESTAURANT-NAPKIN','RESTAURANT-CUTLERY','RESTAURANT-APRON',
 'AGRI-IRRIGATION-PIPE','AGRI-IRRIGATION-VALVE','AGRI-PLANT-POT','AGRI-HAND-TOOL','AGRI-SEED-PACK',
 'MANUF-BOLT','MANUF-NUT','MANUF-STRETCH-FILM','MANUF-CARTON','MANUF-SAFETY-GOGGLES','MANUF-INDUSTRIAL-GLOVE',
 'OFFICE-TONER-CARTRIDGE','OFFICE-FILE-FOLDER','OFFICE-STAPLER'
)
ON CONFLICT DO NOTHING;

-- Seed only broad industry targeting. Every template remains flagged for review in attributes_schema.
INSERT INTO public.commercial_catalog_product_sectors(product_id,sector_code)
SELECT i.id,s.sector_code FROM public.commercial_global_catalog_items i
JOIN (VALUES
 ('MED-GLOVES-NITRILE','healthcare'),('MED-GLOVES-NITRILE','pharmacy'),('MED-GLOVES-NITRILE','hospital'),('MED-GLOVES-NITRILE','clinic'),('MED-GLOVES-NITRILE','laboratory'),('MED-GLOVES-NITRILE','wholesale'),
 ('MED-SURGICAL-MASK','healthcare'),('MED-SURGICAL-MASK','pharmacy'),('MED-SURGICAL-MASK','hospital'),('MED-SURGICAL-MASK','clinic'),('MED-SURGICAL-MASK','wholesale'),
 ('MED-GAUZE-DRESSING','healthcare'),('MED-GAUZE-DRESSING','pharmacy'),('MED-GAUZE-DRESSING','hospital'),('MED-GAUZE-DRESSING','clinic'),('MED-GAUZE-DRESSING','wholesale'),
 ('MED-ELASTIC-BANDAGE','healthcare'),('MED-ELASTIC-BANDAGE','pharmacy'),('MED-ELASTIC-BANDAGE','hospital'),('MED-ELASTIC-BANDAGE','clinic'),
 ('MED-COTTON-WOOL','healthcare'),('MED-COTTON-WOOL','pharmacy'),('MED-COTTON-WOOL','hospital'),('MED-COTTON-WOOL','clinic'),
 ('MED-DIGITAL-THERMOMETER','healthcare'),('MED-DIGITAL-THERMOMETER','pharmacy'),('MED-DIGITAL-THERMOMETER','hospital'),('MED-DIGITAL-THERMOMETER','clinic'),
 ('MED-DISPOSABLE-SYRINGE','healthcare'),('MED-DISPOSABLE-SYRINGE','pharmacy'),('MED-DISPOSABLE-SYRINGE','hospital'),('MED-DISPOSABLE-SYRINGE','clinic'),
 ('MED-IV-SET','healthcare'),('MED-IV-SET','hospital'),('MED-IV-SET','clinic'),
 ('MED-ALCOHOL-SWAB','healthcare'),('MED-ALCOHOL-SWAB','pharmacy'),('MED-ALCOHOL-SWAB','hospital'),('MED-ALCOHOL-SWAB','clinic'),
 ('MED-HAND-SANITIZER','healthcare'),('MED-HAND-SANITIZER','pharmacy'),('MED-HAND-SANITIZER','hospital'),('MED-HAND-SANITIZER','hotel'),('MED-HAND-SANITIZER','restaurant'),
 ('MED-HOSPITAL-GOWN','healthcare'),('MED-HOSPITAL-GOWN','hospital'),('MED-HOSPITAL-GOWN','clinic'),
 ('MED-SHOE-COVER','healthcare'),('MED-SHOE-COVER','hospital'),('MED-SHOE-COVER','laboratory'),
 ('MED-SPECIMEN-CUP','healthcare'),('MED-SPECIMEN-CUP','laboratory'),('MED-SPECIMEN-CUP','hospital'),('MED-TEST-TUBE','healthcare'),('MED-TEST-TUBE','laboratory'),('MED-MICROSCOPE-SLIDE','healthcare'),('MED-MICROSCOPE-SLIDE','laboratory'),
 ('PHARMACY-MEDICINE-BOTTLE','pharmacy'),('PHARMACY-MEDICINE-BOTTLE','wholesale'),('PHARMACY-PILL-ORGANIZER','pharmacy'),('PHARMACY-PILL-ORGANIZER','retail'),
 ('PHONE-SMARTPHONE','mobile_company'),('PHONE-SMARTPHONE','electronics'),('PHONE-SMARTPHONE','retail'),('PHONE-SMARTPHONE','wholesale'),
 ('PHONE-CHARGER','mobile_company'),('PHONE-CHARGER','electronics'),('PHONE-CHARGER','retail'),('PHONE-USB-CABLE','mobile_company'),('PHONE-USB-CABLE','electronics'),('PHONE-USB-CABLE','retail'),
 ('PHONE-CASE','mobile_company'),('PHONE-CASE','electronics'),('PHONE-CASE','retail'),('PHONE-SCREEN-PROTECTOR','mobile_company'),('PHONE-SCREEN-PROTECTOR','electronics'),('PHONE-SCREEN-PROTECTOR','retail'),
 ('PHONE-POWER-BANK','mobile_company'),('PHONE-POWER-BANK','electronics'),('PHONE-POWER-BANK','retail'),('PHONE-EARBUDS','mobile_company'),('PHONE-EARBUDS','electronics'),('PHONE-EARBUDS','retail'),
 ('NETWORK-ROUTER','mobile_company'),('NETWORK-ROUTER','electronics'),('NETWORK-ROUTER','general_company'),('NETWORK-SIM-CARD','mobile_company'),('NETWORK-SIM-CARD','retail'),
 ('AUTO-OIL-FILTER','automotive'),('AUTO-OIL-FILTER','wholesale'),('AUTO-OIL-FILTER','retail'),('AUTO-AIR-FILTER','automotive'),('AUTO-AIR-FILTER','wholesale'),('AUTO-AIR-FILTER','retail'),
 ('AUTO-ENGINE-OIL','automotive'),('AUTO-ENGINE-OIL','wholesale'),('AUTO-ENGINE-OIL','retail'),('AUTO-COOLANT','automotive'),('AUTO-COOLANT','wholesale'),('AUTO-COOLANT','retail'),
 ('AUTO-TIRE','automotive'),('AUTO-TIRE','wholesale'),('AUTO-TIRE','retail'),('AUTO-BATTERY','automotive'),('AUTO-BATTERY','wholesale'),('AUTO-BATTERY','retail'),
 ('AUTO-BRAKE-PADS','automotive'),('AUTO-BRAKE-PADS','wholesale'),('AUTO-SPARK-PLUG','automotive'),('AUTO-SPARK-PLUG','wholesale'),
 ('HEAVY-HYDRAULIC-HOSE','heavy_equipment'),('HEAVY-HYDRAULIC-HOSE','construction'),('HEAVY-HYDRAULIC-HOSE','manufacturing'),('HEAVY-HYDRAULIC-FILTER','heavy_equipment'),('HEAVY-HYDRAULIC-FILTER','construction'),('HEAVY-TRACK-PAD','heavy_equipment'),('HEAVY-BUCKET-TOOTH','heavy_equipment'),('HEAVY-WORK-LIGHT','heavy_equipment'),('HEAVY-SAFETY-VEST','heavy_equipment'),('HEAVY-SAFETY-VEST','construction'),
 ('HOTEL-TOWEL','hotel'),('HOTEL-TOWEL','retail'),('HOTEL-BED-LINEN','hotel'),('HOTEL-BED-LINEN','healthcare'),('HOTEL-GUEST-SOAP','hotel'),('HOTEL-GUEST-SOAP','retail'),('HOTEL-SHAMPOO','hotel'),('HOTEL-SHAMPOO','retail'),
 ('RESTAURANT-TAKEOUT-BOX','restaurant'),('RESTAURANT-TAKEOUT-BOX','retail'),('RESTAURANT-TAKEOUT-BOX','wholesale'),('RESTAURANT-PAPER-CUP','restaurant'),('RESTAURANT-PAPER-CUP','retail'),('RESTAURANT-NAPKIN','restaurant'),('RESTAURANT-NAPKIN','hotel'),('RESTAURANT-CUTLERY','restaurant'),('RESTAURANT-APRON','restaurant'),('RESTAURANT-APRON','hotel'),
 ('AGRI-IRRIGATION-PIPE','agriculture'),('AGRI-IRRIGATION-PIPE','wholesale'),('AGRI-IRRIGATION-VALVE','agriculture'),('AGRI-IRRIGATION-VALVE','wholesale'),('AGRI-PLANT-POT','agriculture'),('AGRI-PLANT-POT','retail'),('AGRI-HAND-TOOL','agriculture'),('AGRI-HAND-TOOL','retail'),('AGRI-SEED-PACK','agriculture'),('AGRI-SEED-PACK','retail'),
 ('MANUF-BOLT','manufacturing'),('MANUF-BOLT','construction'),('MANUF-BOLT','wholesale'),('MANUF-NUT','manufacturing'),('MANUF-NUT','construction'),('MANUF-NUT','wholesale'),('MANUF-STRETCH-FILM','manufacturing'),('MANUF-STRETCH-FILM','wholesale'),('MANUF-CARTON','manufacturing'),('MANUF-CARTON','wholesale'),('MANUF-SAFETY-GOGGLES','manufacturing'),('MANUF-SAFETY-GOGGLES','construction'),('MANUF-INDUSTRIAL-GLOVE','manufacturing'),('MANUF-INDUSTRIAL-GLOVE','construction'),
 ('OFFICE-TONER-CARTRIDGE','office'),('OFFICE-TONER-CARTRIDGE','education'),('OFFICE-TONER-CARTRIDGE','retail'),('OFFICE-FILE-FOLDER','office'),('OFFICE-FILE-FOLDER','education'),('OFFICE-FILE-FOLDER','retail'),('OFFICE-STAPLER','office'),('OFFICE-STAPLER','education'),('OFFICE-STAPLER','retail')
) AS s(catalog_code,sector_code) ON s.catalog_code=i.catalog_code
ON CONFLICT (product_id,sector_code) DO NOTHING;


-- Business-level recommendation weights complement sector targeting without hard-coding the schema to a fixed business list.
INSERT INTO public.commercial_catalog_product_business_levels(product_id,business_level_code,recommendation_weight)
SELECT i.id,v.level_code,v.weight
FROM public.commercial_global_catalog_items i
JOIN (VALUES
 ('MEDICAL-CONSUMABLES','clinic',25),('MEDICAL-CONSUMABLES','institution',20),('MEDICAL-CONSUMABLES','company',10),
 ('MEDICAL-PPE','clinic',25),('MEDICAL-PPE','institution',20),('MEDICAL-PPE','company',10),
 ('WOUND-CARE','clinic',25),('WOUND-CARE','institution',20),('WOUND-CARE','shop',10),
 ('DIAGNOSTIC-SUPPLIES','clinic',25),('DIAGNOSTIC-SUPPLIES','institution',20),
 ('LAB-SUPPLIES','clinic',20),('LAB-SUPPLIES','institution',25),('LAB-SUPPLIES','company',15),
 ('PHARMACY-SUPPLIES','shop',20),('PHARMACY-SUPPLIES','company',15),('PHARMACY-SUPPLIES','institution',10),
 ('HOSPITAL-LINEN','institution',25),('HOSPITAL-LINEN','company',15),
 ('MOBILE-DEVICES','shop',25),('MOBILE-DEVICES','company',15),('MOBILE-DEVICES','supermarket',10),
 ('MOBILE-ACCESSORIES','shop',25),('MOBILE-ACCESSORIES','supermarket',15),('MOBILE-ACCESSORIES','company',10),
 ('NETWORK-EQUIPMENT','company',25),('NETWORK-EQUIPMENT','office',20),('NETWORK-EQUIPMENT','institution',15),
 ('AUTO-FILTERS','company',25),('AUTO-FILTERS','shop',20),('AUTO-FILTERS','factory',10),
 ('AUTO-FLUIDS','company',20),('AUTO-FLUIDS','shop',20),('AUTO-FLUIDS','grocery',5),
 ('AUTO-REPAIR-PARTS','company',25),('AUTO-REPAIR-PARTS','shop',20),('AUTO-REPAIR-PARTS','factory',10),
 ('HEAVY-SPARES','company',25),('HEAVY-SPARES','factory',20),('HEAVY-SPARES','institution',10),
 ('HEAVY-HYDRAULICS','company',25),('HEAVY-HYDRAULICS','factory',20),
 ('HEAVY-SAFETY','company',20),('HEAVY-SAFETY','factory',20),('HEAVY-SAFETY','institution',10),
 ('HOTEL-AMENITIES','company',25),('HOTEL-AMENITIES','institution',10),('HOTEL-AMENITIES','shop',10),
 ('HOTEL-LINEN','company',25),('HOTEL-LINEN','institution',20),
 ('RESTAURANT-PACKAGING','company',25),('RESTAURANT-PACKAGING','shop',10),('RESTAURANT-PACKAGING','grocery',10),
 ('RESTAURANT-KITCHEN','company',25),('RESTAURANT-KITCHEN','institution',15),
 ('AGRI-IRRIGATION','company',20),('AGRI-IRRIGATION','shop',15),('AGRI-IRRIGATION','factory',10),
 ('AGRI-TOOLS','company',20),('AGRI-TOOLS','shop',20),('AGRI-TOOLS','grocery',5),
 ('MANUFACTURING-FASTENERS','factory',25),('MANUFACTURING-FASTENERS','company',20),
 ('MANUFACTURING-PACKAGING','factory',25),('MANUFACTURING-PACKAGING','company',20),('MANUFACTURING-PACKAGING','shop',10),
 ('MANUFACTURING-SAFETY','factory',25),('MANUFACTURING-SAFETY','company',20),('MANUFACTURING-SAFETY','institution',10),
 ('OFFICE-PRINTING','office',25),('OFFICE-PRINTING','company',15),('OFFICE-PRINTING','institution',15),
 ('OFFICE-SUPPLIES','office',25),('OFFICE-SUPPLIES','company',15),('OFFICE-SUPPLIES','institution',15),('OFFICE-SUPPLIES','shop',10)
) AS v(category_code,level_code,weight) ON v.category_code=i.category_code
WHERE i.owner_organization_id IS NULL AND i.attributes_schema->>'catalog_template'='true'
ON CONFLICT (product_id,business_level_code) DO NOTHING;


-- Extend existing grocery/household/office catalog templates to grocery, supermarket, hospitality and wholesale activity codes.
INSERT INTO public.commercial_catalog_product_sectors(product_id,sector_code)
SELECT i.id,v.sector_code
FROM public.commercial_global_catalog_items i
JOIN (VALUES
 ('FOOD-GRAINS','grocery'),('FOOD-GRAINS','supermarket'),('FOOD-GRAINS','restaurant'),('FOOD-GRAINS','hotel'),('FOOD-GRAINS','wholesale'),
 ('FOOD-SWEETENERS','grocery'),('FOOD-SWEETENERS','supermarket'),('FOOD-SWEETENERS','restaurant'),('FOOD-SWEETENERS','hotel'),('FOOD-SWEETENERS','wholesale'),
 ('FOOD-OILS','grocery'),('FOOD-OILS','supermarket'),('FOOD-OILS','restaurant'),('FOOD-OILS','hotel'),('FOOD-OILS','wholesale'),
 ('FOOD-DRINKS','grocery'),('FOOD-DRINKS','supermarket'),('FOOD-DRINKS','restaurant'),('FOOD-DRINKS','hotel'),('FOOD-DRINKS','wholesale'),
 ('FOOD-DAIRY','grocery'),('FOOD-DAIRY','supermarket'),('FOOD-DAIRY','restaurant'),('FOOD-DAIRY','hotel'),('FOOD-DAIRY','wholesale'),
 ('FOOD-BAKERY','grocery'),('FOOD-BAKERY','supermarket'),('FOOD-BAKERY','restaurant'),('FOOD-BAKERY','hotel'),('FOOD-BAKERY','wholesale'),
 ('HOUSEHOLD-CLEANING','grocery'),('HOUSEHOLD-CLEANING','supermarket'),('HOUSEHOLD-CLEANING','hotel'),('HOUSEHOLD-CLEANING','restaurant'),('HOUSEHOLD-CLEANING','wholesale'),
 ('OFFICE-SUPPLIES','office'),('OFFICE-SUPPLIES','supermarket'),('OFFICE-SUPPLIES','wholesale')
) AS v(category_code,sector_code) ON v.category_code=i.category_code
WHERE i.owner_organization_id IS NULL AND i.is_active
ON CONFLICT (product_id,sector_code) DO NOTHING;

INSERT INTO public.commercial_catalog_product_business_levels(product_id,business_level_code,recommendation_weight)
SELECT i.id,v.level_code,v.weight
FROM public.commercial_global_catalog_items i
JOIN (VALUES
 ('FOOD-GRAINS','shop',20),('FOOD-GRAINS','grocery',25),('FOOD-GRAINS','supermarket',25),('FOOD-GRAINS','company',15),
 ('FOOD-SWEETENERS','shop',20),('FOOD-SWEETENERS','grocery',25),('FOOD-SWEETENERS','supermarket',25),('FOOD-SWEETENERS','company',15),
 ('FOOD-OILS','shop',20),('FOOD-OILS','grocery',25),('FOOD-OILS','supermarket',25),('FOOD-OILS','company',15),
 ('FOOD-DRINKS','shop',20),('FOOD-DRINKS','grocery',25),('FOOD-DRINKS','supermarket',25),('FOOD-DRINKS','company',15),
 ('FOOD-DAIRY','shop',20),('FOOD-DAIRY','grocery',25),('FOOD-DAIRY','supermarket',25),('FOOD-DAIRY','company',15),
 ('FOOD-BAKERY','shop',20),('FOOD-BAKERY','grocery',25),('FOOD-BAKERY','supermarket',25),('FOOD-BAKERY','company',15),
 ('HOUSEHOLD-CLEANING','shop',20),('HOUSEHOLD-CLEANING','grocery',25),('HOUSEHOLD-CLEANING','supermarket',25),('HOUSEHOLD-CLEANING','company',15),
 ('OFFICE-SUPPLIES','office',25),('OFFICE-SUPPLIES','company',15),('OFFICE-SUPPLIES','shop',10)
) AS v(category_code,level_code,weight) ON v.category_code=i.category_code
WHERE i.owner_organization_id IS NULL AND i.is_active
ON CONFLICT (product_id,business_level_code) DO NOTHING;


-- Pharmacy owners often run the business at shop-level; ensure common medical and pharmacy supplies remain discoverable there.
INSERT INTO public.commercial_catalog_product_business_levels(product_id,business_level_code,recommendation_weight)
SELECT i.id,'shop',20
FROM public.commercial_global_catalog_items i
WHERE i.owner_organization_id IS NULL AND i.is_active
  AND i.category_code IN ('MEDICAL-CONSUMABLES','MEDICAL-PPE','WOUND-CARE','DIAGNOSTIC-SUPPLIES','LAB-SUPPLIES','PHARMACY-SUPPLIES')
ON CONFLICT (product_id,business_level_code) DO NOTHING;
