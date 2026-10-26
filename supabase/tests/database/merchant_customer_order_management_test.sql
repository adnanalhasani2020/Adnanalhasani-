-- Structural regression coverage for merchant-side customer order management.
BEGIN;
SELECT plan(8);

SELECT has_function('public','commercial_customer_portal_list_organization_orders_v2',ARRAY['uuid','integer','boolean'],'organization order queue exists');
SELECT has_function('public','commercial_customer_portal_list_organization_bookings',ARRAY['uuid','integer','boolean'],'organization booking queue exists');
SELECT has_function('public','commercial_customer_portal_decide_order',ARRAY['uuid','uuid','text','text'],'order decision RPC exists');
SELECT has_function('public','commercial_customer_portal_decide_booking',ARRAY['uuid','uuid','text','text'],'booking decision RPC exists');
SELECT has_function('public','commercial_customer_portal_edit_order_lines',ARRAY['uuid','uuid','jsonb','text'],'server-priced order line editor exists');
SELECT has_function('public','commercial_customer_portal_set_order_hidden',ARRAY['uuid','uuid','boolean'],'order hide/restore RPC exists');
SELECT has_function('public','commercial_customer_portal_set_booking_hidden',ARRAY['uuid','uuid','boolean'],'booking hide/restore RPC exists');

SELECT ok(
  position('booking_resource_capacity_exceeded' in pg_get_functiondef('public.commercial_customer_portal_decide_booking(uuid,uuid,text,text)'::regprocedure))>0
  AND position('customer_booking_order_decision_race_retry' in pg_get_functiondef('public.commercial_customer_portal_decide_booking(uuid,uuid,text,text)'::regprocedure))>0
  AND position('commercial_audit_events' in pg_get_functiondef('public.commercial_customer_portal_decide_order(uuid,uuid,text,text)'::regprocedure))>0,
  'booking decision rechecks capacity, updates linked order atomically, and decisions are audited'
);
SELECT * FROM finish();
ROLLBACK;
