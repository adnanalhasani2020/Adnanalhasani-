DO $$
DECLARE
 v_customer uuid:='10000000-0000-0000-0000-000000000002';
 v_manager uuid:='10000000-0000-0000-0000-000000000001';
 v_staff uuid:='10000000-0000-0000-0000-000000000003';
 v_viewer uuid:='10000000-0000-0000-0000-000000000004';
 v_outsider uuid:='10000000-0000-0000-0000-000000000005';
 v_org uuid:='20000000-0000-0000-0000-000000000001';
 v_sale uuid:='50000000-0000-0000-0000-000000000001';
 v_request uuid; v_result jsonb; v_due jsonb; v_payment_count integer;
BEGIN
 -- Customer can see only their own open invoice balance.
 PERFORM set_config('request.jwt.claim.sub',v_customer::text,true);
 v_due:=public.commercial_customer_portal_list_outstanding_sales(v_org);
 IF jsonb_array_length(v_due)<>1 OR (v_due->0->>'due_minor')::bigint<>8000 THEN
  RAISE EXCEPTION 'customer_outstanding_due_projection_invalid: %',v_due;
 END IF;

 -- Claiming a payment creates a request, not a payment or journal effect.
 v_result:=public.commercial_customer_portal_submit_payment_request(v_sale,5000,'سددت جزءًا من الدين','customer-submit-key-001');
 v_request:=(v_result->>'request_id')::uuid;
 IF (v_result->>'status')<>'pending' OR (v_result->>'replayed')::boolean THEN
  RAISE EXCEPTION 'payment_claim_creation_invalid';
 END IF;
 SELECT count(*) INTO v_payment_count FROM public.commercial_payments;
 IF v_payment_count<>1 THEN RAISE EXCEPTION 'customer_claim_created_payment_early'; END IF;

 -- Retry with the same key returns the same request; another claim cannot reserve the same due twice.
 v_result:=public.commercial_customer_portal_submit_payment_request(v_sale,5000,'سددت جزءًا من الدين','customer-submit-key-001');
 IF (v_result->>'request_id')::uuid<>v_request OR NOT (v_result->>'replayed')::boolean THEN
  RAISE EXCEPTION 'customer_submit_idempotency_failed';
 END IF;
 BEGIN
  PERFORM public.commercial_customer_portal_submit_payment_request(v_sale,4000,'دفعة أخرى','customer-submit-key-002');
  RAISE EXCEPTION 'over_reserved_payment_claim_was_accepted';
 EXCEPTION WHEN SQLSTATE '22023' THEN NULL;
 END;

 -- A merchant can list and request correction; a staff member and viewer cannot review.
 PERFORM set_config('request.jwt.claim.sub',v_manager::text,true);
 v_result:=public.commercial_merchant_list_customer_payment_requests(v_org,'pending',50);
 IF jsonb_array_length(v_result)<>1 THEN RAISE EXCEPTION 'merchant_request_list_invalid'; END IF;
 v_result:=public.commercial_merchant_review_customer_payment_request(
  v_request,'request_changes',NULL,'يرجى تأكيد المبلغ المستلم','cash','merchant-review-change-001'
 );
 IF (v_result->>'status')<>'change_requested' THEN RAISE EXCEPTION 'merchant_change_request_failed'; END IF;

 PERFORM set_config('request.jwt.claim.sub',v_staff::text,true);
 BEGIN
  PERFORM public.commercial_merchant_review_customer_payment_request(
   v_request,'reject',NULL,'رفض غير مصرح','cash','staff-review-denied-001'
  );
  RAISE EXCEPTION 'staff_review_was_allowed';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
 PERFORM set_config('request.jwt.claim.sub',v_viewer::text,true);
 BEGIN
  PERFORM public.commercial_merchant_review_customer_payment_request(
   v_request,'reject',NULL,'رفض غير مصرح','cash','viewer-review-denied-001'
  );
  RAISE EXCEPTION 'viewer_review_was_allowed';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;

 -- Customer resubmits after correction; only merchant approval invokes the payment RPC.
 PERFORM set_config('request.jwt.claim.sub',v_customer::text,true);
 v_result:=public.commercial_customer_portal_update_payment_request(v_request,4500,'استلمتم 4500','customer-update-key-001');
 IF (v_result->>'status')<>'pending' THEN RAISE EXCEPTION 'customer_resubmission_failed'; END IF;

 PERFORM set_config('request.jwt.claim.sub',v_manager::text,true);
 BEGIN
  PERFORM public.commercial_merchant_review_customer_payment_request(
   v_request,'approve',4400,'تأكيد غير مطابق','cash','merchant-approve-mismatch-001'
  );
  RAISE EXCEPTION 'mismatched_merchant_amount_was_accepted';
 EXCEPTION WHEN SQLSTATE '22023' THEN NULL;
 END;
 SELECT count(*) INTO v_payment_count FROM public.commercial_payments;
 IF v_payment_count<>1 THEN RAISE EXCEPTION 'mismatched_approval_created_payment'; END IF;

 v_result:=public.commercial_merchant_review_customer_payment_request(
  v_request,'approve',4500,'تم استلام الدفعة','cash','merchant-approve-key-001'
 );
 IF (v_result->>'status')<>'approved' OR (v_result->>'payment_id') IS NULL THEN
  RAISE EXCEPTION 'merchant_approval_did_not_create_payment';
 END IF;
 SELECT count(*) INTO v_payment_count FROM public.commercial_payments;
 IF v_payment_count<>2 THEN RAISE EXCEPTION 'approved_payment_not_created_exactly_once'; END IF;

 -- Replaying approval does not create a second payment.
 v_result:=public.commercial_merchant_review_customer_payment_request(
  v_request,'approve',4500,'تم استلام الدفعة','cash','merchant-approve-key-001'
 );
 IF NOT (v_result->>'replayed')::boolean THEN RAISE EXCEPTION 'merchant_review_replay_failed'; END IF;
 SELECT count(*) INTO v_payment_count FROM public.commercial_payments;
 IF v_payment_count<>2 THEN RAISE EXCEPTION 'approval_replay_duplicated_payment'; END IF;

 -- Customer and merchant histories contain immutable transition events and notifications.
 IF (SELECT count(*) FROM public.commercial_customer_payment_request_events WHERE request_id=v_request)<>4 THEN
  RAISE EXCEPTION 'payment_request_event_history_incomplete';
 END IF;
 IF (SELECT count(*) FROM public.commercial_user_notifications WHERE entity_id=v_request)<3 THEN
  RAISE EXCEPTION 'payment_request_notifications_missing';
 END IF;

 -- Cross-organization customer cannot submit against another customer's invoice.
 PERFORM set_config('request.jwt.claim.sub',v_outsider::text,true);
 BEGIN
  PERFORM public.commercial_customer_portal_submit_payment_request(v_sale,1000,'foreign sale','outsider-submit-key-001');
  RAISE EXCEPTION 'cross_organization_payment_claim_was_allowed';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;

 IF NOT (SELECT relrowsecurity FROM pg_class WHERE oid='public.commercial_customer_payment_requests'::regclass)
  OR NOT (SELECT relrowsecurity FROM pg_class WHERE oid='public.commercial_customer_payment_request_events'::regclass)
  OR has_table_privilege('anon','public.commercial_customer_payment_requests','SELECT')
  OR has_table_privilege('authenticated','public.commercial_customer_payment_requests','SELECT')
  OR has_table_privilege('authenticated','public.commercial_customer_payment_request_events','INSERT') THEN
  RAISE EXCEPTION 'payment_request_table_privileges_invalid';
 END IF;
 IF has_function_privilege('anon','public.commercial_customer_portal_submit_payment_request(uuid,bigint,text,text)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_customer_portal_submit_payment_request(uuid,bigint,text,text)','EXECUTE')
  OR has_function_privilege('anon','public.commercial_merchant_review_customer_payment_request(uuid,text,bigint,text,text,text)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_merchant_review_customer_payment_request(uuid,text,bigint,text,text,text)','EXECUTE') THEN
  RAISE EXCEPTION 'payment_request_rpc_grants_invalid';
 END IF;
END $$;
