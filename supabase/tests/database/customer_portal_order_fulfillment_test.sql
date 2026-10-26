-- Run after all migrations in a disposable Supabase database.
BEGIN;
SELECT plan(8);

SELECT has_table(
  'public', 'commercial_customer_order_fulfillments',
  'fulfillment ledger links customer orders to completed sales'
);

SELECT has_index(
  'public', 'commercial_customer_order_fulfillments',
  'commercial_customer_order_fulfillments_order_uq',
  'a customer order can be fulfilled only once'
);

SELECT ok(
  has_function_privilege(
    'authenticated',
    'public.commercial_customer_portal_fulfill_order(uuid,uuid,uuid,text)',
    'EXECUTE'
  ),
  'authenticated callers can invoke the guarded fulfillment RPC'
);

SELECT ok(
  NOT has_function_privilege(
    'anon',
    'public.commercial_customer_portal_fulfill_order(uuid,uuid,uuid,text)',
    'EXECUTE'
  ),
  'anonymous callers cannot invoke customer order fulfillment'
);

SELECT ok(
  position('commercial_organization_members' in pg_get_functiondef(
    'public.commercial_customer_portal_fulfill_order(uuid,uuid,uuid,text)'::regprocedure
  )) > 0
  AND position('customer_order_fulfillment_permission_required' in pg_get_functiondef(
    'public.commercial_customer_portal_fulfill_order(uuid,uuid,uuid,text)'::regprocedure
  )) > 0,
  'fulfillment requires an active organization manager'
);

SELECT ok(
  position('commercial_checkout_sale_multi' in pg_get_functiondef(
    'public.commercial_customer_portal_fulfill_order(uuid,uuid,uuid,text)'::regprocedure
  )) > 0
  AND position('customer_order.fulfilled' in pg_get_functiondef(
    'public.commercial_customer_portal_fulfill_order(uuid,uuid,uuid,text)'::regprocedure
  )) > 0,
  'fulfillment uses the atomic checkout path and writes an audit event'
);

SELECT ok(
  position('customer_order_price_or_offering_changed_requote_required' in pg_get_functiondef(
    'public.commercial_customer_portal_fulfill_order(uuid,uuid,uuid,text)'::regprocedure
  )) > 0
  AND position('customer_order_spans_multiple_activities' in pg_get_functiondef(
    'public.commercial_customer_portal_fulfill_order(uuid,uuid,uuid,text)'::regprocedure
  )) > 0,
  'fulfillment rejects price changes and unsupported cross-activity orders'
);

SELECT ok(
  position('FOR UPDATE' in pg_get_functiondef(
    'public.commercial_customer_portal_fulfill_order(uuid,uuid,uuid,text)'::regprocedure
  )) > 0
  AND position('idempotent_replay' in pg_get_functiondef(
    'public.commercial_customer_portal_fulfill_order(uuid,uuid,uuid,text)'::regprocedure
  )) > 0,
  'fulfillment serializes order transitions and supports safe retries'
);

SELECT * FROM finish();
ROLLBACK;
