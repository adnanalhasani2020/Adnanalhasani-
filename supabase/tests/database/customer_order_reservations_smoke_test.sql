DO $$
DECLARE
 v_user uuid:=gen_random_uuid(); v_org uuid:=gen_random_uuid(); v_location uuid:=gen_random_uuid();
 v_activity uuid:=gen_random_uuid(); v_offering uuid:=gen_random_uuid();
 v_order uuid:=gen_random_uuid(); v_item uuid:=gen_random_uuid();
 v_order2 uuid:=gen_random_uuid(); v_item2 uuid:=gen_random_uuid();
 v_order3 uuid:=gen_random_uuid(); v_item3 uuid:=gen_random_uuid();
 v_result jsonb; v_reserved numeric; v_on_hand numeric; v_count integer;
BEGIN
 INSERT INTO auth.users(id) VALUES(v_user);
 INSERT INTO public.commercial_organizations(id) VALUES(v_org);
 INSERT INTO public.commercial_organization_locations(id,organization_id,name) VALUES(v_location,v_org,'Store');
 INSERT INTO public.commercial_organization_members(organization_id,user_id,state,role) VALUES(v_org,v_user,'active','manager');
 INSERT INTO public.commercial_activities(id,organization_id) VALUES(v_activity,v_org);
 INSERT INTO public.commercial_offerings(id,activity_id) VALUES(v_offering,v_activity);
 INSERT INTO public.commercial_inventory_balances(organization_id,location_id,offering_id,quantity_on_hand,quantity_reserved)
 VALUES(v_org,v_location,v_offering,10,2);
 INSERT INTO public.commercial_customer_orders(id,organization_id,order_type,status,payment_status)
 VALUES(v_order,v_org,'purchase','pending','unpaid');
 INSERT INTO public.commercial_customer_order_items(id,order_id,offering_id,item_type,quantity)
 VALUES(v_item,v_order,v_offering,'product',3);
 PERFORM set_config('request.jwt.claim.sub',v_user::text,true);

 v_result:=public.commercial_customer_portal_reserve_order(v_org,v_order,v_location,now()+interval '1 day','release_stock','reserve-order-001');
 SELECT quantity_on_hand,quantity_reserved INTO v_on_hand,v_reserved FROM public.commercial_inventory_balances
 WHERE organization_id=v_org AND location_id=v_location AND offering_id=v_offering;
 IF v_on_hand<>10 OR v_reserved<>5 THEN RAISE EXCEPTION 'reservation_must_only_reduce_available_stock'; END IF;
 IF (SELECT status FROM public.commercial_customer_orders WHERE id=v_order)<>'confirmed' THEN
   RAISE EXCEPTION 'reservation_must_confirm_order';
 END IF;

 -- A competing request cannot reserve more than the remaining sellable quantity.
 INSERT INTO public.commercial_customer_orders(id,organization_id,order_type,status,payment_status)
 VALUES(v_order2,v_org,'purchase','pending','unpaid');
 INSERT INTO public.commercial_customer_order_items(id,order_id,offering_id,item_type,quantity)
 VALUES(v_item2,v_order2,v_offering,'product',6);
 BEGIN
  PERFORM public.commercial_customer_portal_reserve_order(v_org,v_order2,v_location,now()+interval '1 day','release_stock','reserve-order-002');
  RAISE EXCEPTION 'expected_insufficient_stock_rejection';
 EXCEPTION WHEN SQLSTATE '22023' THEN NULL;
 END;

 -- Expiry returns the reserved amount to available stock without changing on-hand.
 UPDATE public.commercial_customer_order_reservations SET expires_at=now()-interval '1 minute'
 WHERE order_id=v_order;
 PERFORM public.commercial_customer_portal_process_expired_order_reservations(v_org,100);
 SELECT quantity_on_hand,quantity_reserved INTO v_on_hand,v_reserved FROM public.commercial_inventory_balances
 WHERE organization_id=v_org AND location_id=v_location AND offering_id=v_offering;
 IF v_on_hand<>10 OR v_reserved<>2 THEN RAISE EXCEPTION 'expiry_must_release_reserved_quantity_only'; END IF;
 IF (SELECT status FROM public.commercial_customer_order_reservations WHERE order_id=v_order)<>'expired' THEN
   RAISE EXCEPTION 'release_policy_must_expire_reservation';
 END IF;
 IF (SELECT status FROM public.commercial_customer_orders WHERE id=v_order)<>'cancelled' THEN
   RAISE EXCEPTION 'expiry_must_use_supported_cancelled_order_state';
 END IF;

 -- Keep-reserved policy retains stock and emits one daily reminder per due processing window.
 INSERT INTO public.commercial_customer_orders(id,organization_id,order_type,status,payment_status)
 VALUES(v_order3,v_org,'purchase','pending','unpaid');
 INSERT INTO public.commercial_customer_order_items(id,order_id,offering_id,item_type,quantity)
 VALUES(v_item3,v_order3,v_offering,'product',2);
 PERFORM public.commercial_customer_portal_reserve_order(v_org,v_order3,v_location,now()+interval '1 day','keep_reserved_notify','reserve-order-003');
 UPDATE public.commercial_customer_order_reservations SET expires_at=now()-interval '1 minute',next_reminder_at=now()-interval '1 minute'
 WHERE order_id=v_order3;
 v_result:=public.commercial_customer_portal_process_expired_order_reservations(v_org,100);
 SELECT quantity_reserved INTO v_reserved FROM public.commercial_inventory_balances
 WHERE organization_id=v_org AND location_id=v_location AND offering_id=v_offering;
 IF v_reserved<>4 THEN RAISE EXCEPTION 'keep_policy_must_keep_stock_reserved'; END IF;
 SELECT count(*) INTO v_count FROM public.commercial_user_notifications WHERE entity_id=v_order3;
 IF v_count<>1 THEN RAISE EXCEPTION 'keep_policy_must_notify_manager'; END IF;
 PERFORM public.commercial_customer_portal_process_expired_order_reservations(v_org,100);
 SELECT count(*) INTO v_count FROM public.commercial_user_notifications WHERE entity_id=v_order3;
 IF v_count<>1 THEN RAISE EXCEPTION 'daily_reminder_must_not_spam_on_repeated_refresh'; END IF;

 -- Reserved fulfillment releases the hold and delegates the real stock movement to checkout.
 v_result:=public.commercial_customer_portal_fulfill_reserved_order(v_org,v_order3,v_location,'fulfill-order-003');
 SELECT quantity_on_hand,quantity_reserved INTO v_on_hand,v_reserved FROM public.commercial_inventory_balances
 WHERE organization_id=v_org AND location_id=v_location AND offering_id=v_offering;
 IF v_on_hand<>8 OR v_reserved<>2 THEN RAISE EXCEPTION 'reserved_fulfillment_must_consume_physical_stock_once'; END IF;
 v_result:=public.commercial_customer_portal_fulfill_reserved_order(v_org,v_order3,v_location,'fulfill-order-003');
 IF coalesce((v_result->>'idempotent_replay')::boolean,false) IS NOT TRUE THEN
   RAISE EXCEPTION 'reserved_fulfillment_retry_must_be_idempotent';
 END IF;
END;
$$;

DO $$
BEGIN
 IF has_table_privilege('anon','public.commercial_customer_order_reservations','SELECT') THEN
   RAISE EXCEPTION 'anonymous_must_not_read_reservation_ledger';
 END IF;
 IF NOT has_function_privilege('authenticated','public.commercial_customer_portal_reserve_order(uuid,uuid,uuid,timestamptz,text,text)','EXECUTE') THEN
   RAISE EXCEPTION 'authenticated_reservation_rpc_grant_missing';
 END IF;
 IF has_function_privilege('anon','public.commercial_customer_portal_reserve_order(uuid,uuid,uuid,timestamptz,text,text)','EXECUTE') THEN
   RAISE EXCEPTION 'anonymous_reservation_rpc_grant_must_be_revoked';
 END IF;
END;
$$;
