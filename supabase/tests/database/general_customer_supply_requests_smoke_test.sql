\set ON_ERROR_STOP on
DO $$
DECLARE
 v_org uuid:='10000000-0000-0000-0000-000000000001';
 v_customer uuid:='20000000-0000-0000-0000-000000000001';
 v_merchant uuid:='20000000-0000-0000-0000-000000000002';
 v_customer_row uuid:='30000000-0000-0000-0000-000000000001';
 v_account uuid:='40000000-0000-0000-0000-000000000001';
 v_supplier uuid:='50000000-0000-0000-0000-000000000001';
 v_location uuid:='60000000-0000-0000-0000-000000000001';
 v_request uuid; v_po uuid; v_result jsonb; v_status text; v_count integer;
BEGIN
 INSERT INTO auth.users(id,email,email_confirmed_at) VALUES
  (v_customer,'customer@example.test',now()),(v_merchant,'merchant@example.test',now());
 INSERT INTO public.commercial_organizations(id,name) VALUES(v_org,'متجر الاختبار');
 INSERT INTO public.commercial_organization_members VALUES(v_org,v_merchant,'active','manager');
 INSERT INTO public.commercial_organization_locations(id,organization_id,name) VALUES(v_location,v_org,'المخزن');
 INSERT INTO public.commercial_customers(id,organization_id,auth_user_id,display_name)
 VALUES(v_customer_row,v_org,v_customer,'عميل تجريبي');
 INSERT INTO public.commercial_party_accounts(id,organization_id,account_type,allow_credit,currency_code)
 VALUES(v_account,v_org,'business',true,'YER');
 INSERT INTO public.commercial_customer_party_accounts VALUES(v_org,v_customer_row,v_account);
 INSERT INTO public.commercial_suppliers(id,organization_id,display_name) VALUES(v_supplier,v_org,'مورد تجريبي');

 PERFORM set_config('request.jwt.claim.sub',v_customer::text,true);
 v_result:=public.commercial_customer_portal_submit_supply_request(
  v_org,'مضخة مياه',2,'EA','قطعة',125000,'credit','قدرة 1 حصان','أريدها قبل نهاية الأسبوع','supply-request-key-0001');
 v_request:=(v_result->>'request_id')::uuid;
 IF v_result->>'status'<>'pending' OR v_request IS NULL THEN RAISE EXCEPTION 'submit_request_failed'; END IF;
 v_result:=public.commercial_customer_portal_submit_supply_request(
  v_org,'مضخة مياه',2,'EA','قطعة',125000,'credit','قدرة 1 حصان','أريدها قبل نهاية الأسبوع','supply-request-key-0001');
 IF NOT (v_result->>'replayed')::boolean THEN RAISE EXCEPTION 'idempotent_retry_failed'; END IF;
 IF jsonb_array_length(public.commercial_customer_portal_list_my_supply_requests(v_org))<>1 THEN
  RAISE EXCEPTION 'customer_request_list_failed';
 END IF;

 PERFORM set_config('request.jwt.claim.sub',v_merchant::text,true);
 v_result:=public.commercial_customer_portal_list_organization_supply_requests(v_org,100);
 IF jsonb_array_length(v_result)<>1 OR v_result->0->>'item_name'<>'مضخة مياه' THEN
  RAISE EXCEPTION 'merchant_queue_failed';
 END IF;
 -- Add two newer pending requests and prove p_limit is applied before JSON aggregation.
 INSERT INTO public.commercial_customer_supply_requests(
  organization_id,customer_id,buyer_account_id,submitted_by,item_name,requested_quantity,
  unit_code,unit_name,requested_price_minor,currency_code,payment_mode,idempotency_key,request_fingerprint,created_at
 ) VALUES
  (v_org,v_customer_row,v_account,v_customer,'صنف اختبار 2',1,'EA','قطعة',100,'YER','cash','limit-test-key-0002',md5('limit-test-2'),now()+interval '2 seconds'),
  (v_org,v_customer_row,v_account,v_customer,'صنف اختبار 3',1,'EA','قطعة',100,'YER','cash','limit-test-key-0003',md5('limit-test-3'),now()+interval '3 seconds');
 v_result:=public.commercial_customer_portal_list_organization_supply_requests(v_org,1);
 IF jsonb_array_length(v_result)<>1 OR v_result->0->>'item_name'<>'صنف اختبار 3' THEN
  RAISE EXCEPTION 'merchant_queue_limit_not_applied_before_aggregation';
 END IF;
 v_result:=public.commercial_customer_portal_list_organization_supply_requests(v_org,100);
 IF jsonb_array_length(v_result)<>3 THEN
  RAISE EXCEPTION 'merchant_queue_full_list_regression';
 END IF;
 PERFORM public.commercial_customer_portal_review_supply_request(v_org,v_request,'request_changes','حدد نوع التوصيل');
 PERFORM set_config('request.jwt.claim.sub',v_customer::text,true);
 v_result:=public.commercial_customer_portal_list_my_supply_requests(v_org);
 IF v_result->0->>'status'<>'change_requested' OR v_result->0->>'merchant_note'<>'حدد نوع التوصيل' THEN
  RAISE EXCEPTION 'change_request_not_visible_to_customer';
 END IF;
 PERFORM public.commercial_customer_portal_resubmit_supply_request(
  v_request,'مضخة مياه',2,'EA','قطعة',125000,'credit','قدرة 1 حصان','توصيل إلى المنزل');
 PERFORM set_config('request.jwt.claim.sub',v_merchant::text,true);
 PERFORM public.commercial_customer_portal_review_supply_request(v_org,v_request,'accept','تم القبول');
 v_result:=public.commercial_customer_portal_convert_supply_request_to_purchase(
  v_org,v_request,v_supplier,v_location,'CSR-SMOKE-01',125000,'اختبار التحويل');
 v_po:=(v_result->>'purchase_order_id')::uuid;
 IF v_po IS NULL OR v_result->>'status'<>'converted'
  OR (v_result->>'stock_received')::boolean OR (v_result->>'accounting_posted')::boolean THEN
  RAISE EXCEPTION 'purchase_conversion_contract_failed';
 END IF;
 SELECT status INTO v_status FROM public.commercial_purchase_orders WHERE id=v_po;
 IF v_status<>'draft' THEN RAISE EXCEPTION 'purchase_order_not_draft'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_purchase_order_items
  WHERE purchase_order_id=v_po AND offering_id IS NULL AND quantity_ordered=2 AND unit_cost_minor=125000) THEN
  RAISE EXCEPTION 'unmapped_purchase_line_missing';
 END IF;
 IF (SELECT status FROM public.commercial_customer_supply_requests WHERE id=v_request)<>'converted' THEN
  RAISE EXCEPTION 'request_not_marked_converted';
 END IF;
 IF (SELECT count(*) FROM public.commercial_customer_supply_request_events WHERE request_id=v_request)<5 THEN
  RAISE EXCEPTION 'request_event_history_incomplete';
 END IF;
 SELECT count(*) INTO v_count FROM public.commercial_user_notifications WHERE user_id=v_customer;
 IF v_count<3 THEN RAISE EXCEPTION 'customer_notifications_missing'; END IF;
 SELECT count(*) INTO v_count FROM public.commercial_user_notifications WHERE user_id=v_merchant;
 IF v_count<1 THEN RAISE EXCEPTION 'merchant_notification_missing'; END IF;
 IF has_table_privilege('anon','public.commercial_customer_supply_requests','SELECT')
  OR has_table_privilege('authenticated','public.commercial_customer_supply_requests','INSERT') THEN
  RAISE EXCEPTION 'request_table_direct_access_not_revoked';
 END IF;
END $$;
