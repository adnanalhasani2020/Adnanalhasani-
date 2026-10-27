-- Structural regression coverage for merchant-side customer order management.
BEGIN;
SELECT plan(27);

SELECT has_function('public','commercial_customer_portal_list_organization_orders_v2',ARRAY['uuid','integer','boolean'],'organization order queue exists');
SELECT has_function('public','commercial_customer_portal_list_organization_bookings',ARRAY['uuid','integer','boolean'],'organization booking queue exists');
SELECT has_function('public','commercial_customer_portal_decide_order',ARRAY['uuid','uuid','text','text'],'order decision RPC exists');
SELECT has_function('public','commercial_customer_portal_decide_booking',ARRAY['uuid','uuid','text','text'],'booking decision RPC exists');
SELECT has_function('public','commercial_customer_portal_edit_order_lines',ARRAY['uuid','uuid','jsonb','text'],'server-priced order line editor exists');
SELECT has_function('public','commercial_customer_portal_set_order_hidden',ARRAY['uuid','uuid','boolean'],'order hide/restore RPC exists');
SELECT has_function('public','commercial_customer_portal_set_booking_hidden',ARRAY['uuid','uuid','boolean'],'booking hide/restore RPC exists');
SELECT has_function('public','commercial_customer_portal_list_editable_offerings',ARRAY['uuid'],'editable offering discovery RPC exists');

SELECT ok(
  position('booking_resource_capacity_exceeded' in pg_get_functiondef('public.commercial_customer_portal_decide_booking(uuid,uuid,text,text)'::regprocedure))>0
  AND position('customer_booking_order_decision_race_retry' in pg_get_functiondef('public.commercial_customer_portal_decide_booking(uuid,uuid,text,text)'::regprocedure))>0
  AND position('commercial_audit_events' in pg_get_functiondef('public.commercial_customer_portal_decide_order(uuid,uuid,text,text)'::regprocedure))>0,
  'booking decision rechecks capacity, updates linked order atomically, and decisions are audited'
);

-- Explicitly verify that each SECURITY DEFINER entry point is available to authenticated
-- clients but not to anonymous clients. The latter also catches accidental PUBLIC grants.
SELECT ok(has_function_privilege('authenticated','public.commercial_customer_portal_list_organization_orders_v2(uuid,integer,boolean)','EXECUTE'),'authenticated can execute order queue RPC');
SELECT ok(NOT has_function_privilege('anon','public.commercial_customer_portal_list_organization_orders_v2(uuid,integer,boolean)','EXECUTE'),'anon cannot execute order queue RPC');
SELECT ok(has_function_privilege('authenticated','public.commercial_customer_portal_list_organization_bookings(uuid,integer,boolean)','EXECUTE'),'authenticated can execute booking queue RPC');
SELECT ok(NOT has_function_privilege('anon','public.commercial_customer_portal_list_organization_bookings(uuid,integer,boolean)','EXECUTE'),'anon cannot execute booking queue RPC');
SELECT ok(has_function_privilege('authenticated','public.commercial_customer_portal_decide_order(uuid,uuid,text,text)','EXECUTE'),'authenticated can execute order decision RPC');
SELECT ok(NOT has_function_privilege('anon','public.commercial_customer_portal_decide_order(uuid,uuid,text,text)','EXECUTE'),'anon cannot execute order decision RPC');
SELECT ok(has_function_privilege('authenticated','public.commercial_customer_portal_decide_booking(uuid,uuid,text,text)','EXECUTE'),'authenticated can execute booking decision RPC');
SELECT ok(NOT has_function_privilege('anon','public.commercial_customer_portal_decide_booking(uuid,uuid,text,text)','EXECUTE'),'anon cannot execute booking decision RPC');
SELECT ok(has_function_privilege('authenticated','public.commercial_customer_portal_edit_order_lines(uuid,uuid,jsonb,text)','EXECUTE'),'authenticated can execute order line editor RPC');
SELECT ok(NOT has_function_privilege('anon','public.commercial_customer_portal_edit_order_lines(uuid,uuid,jsonb,text)','EXECUTE'),'anon cannot execute order line editor RPC');
SELECT ok(has_function_privilege('authenticated','public.commercial_customer_portal_set_order_hidden(uuid,uuid,boolean)','EXECUTE'),'authenticated can execute order visibility RPC');
SELECT ok(NOT has_function_privilege('anon','public.commercial_customer_portal_set_order_hidden(uuid,uuid,boolean)','EXECUTE'),'anon cannot execute order visibility RPC');
SELECT ok(has_function_privilege('authenticated','public.commercial_customer_portal_set_booking_hidden(uuid,uuid,boolean)','EXECUTE'),'authenticated can execute booking visibility RPC');
SELECT ok(NOT has_function_privilege('anon','public.commercial_customer_portal_set_booking_hidden(uuid,uuid,boolean)','EXECUTE'),'anon cannot execute booking visibility RPC');
SELECT ok(has_function_privilege('authenticated','public.commercial_customer_portal_list_editable_offerings(uuid)','EXECUTE'),'authenticated can execute editable offering RPC');
SELECT ok(NOT has_function_privilege('anon','public.commercial_customer_portal_list_editable_offerings(uuid)','EXECUTE'),'anon cannot execute editable offering RPC');

SELECT * FROM finish();
ROLLBACK;
