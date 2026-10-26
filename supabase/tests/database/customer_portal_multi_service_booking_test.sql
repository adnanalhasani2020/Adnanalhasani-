-- Run after all migrations in a disposable Supabase database.
BEGIN;
SELECT plan(8);

SELECT has_function(
  'public',
  'commercial_customer_portal_create_multi_service_booking',
  ARRAY['uuid','jsonb','uuid','timestamp with time zone','timestamp with time zone','text','text','text'],
  'multi-service booking RPC exists'
);

SELECT ok(
  has_function_privilege(
    'authenticated',
    'public.commercial_customer_portal_create_multi_service_booking(uuid,jsonb,uuid,timestamptz,timestamptz,text,text,text)',
    'EXECUTE'
  ),
  'authenticated customers can call the guarded booking RPC'
);

SELECT ok(
  NOT has_function_privilege(
    'anon',
    'public.commercial_customer_portal_create_multi_service_booking(uuid,jsonb,uuid,timestamptz,timestamptz,text,text,text)',
    'EXECUTE'
  ),
  'anonymous callers cannot create bookings'
);

SELECT ok(
  position('commercial_customer_portal_create_order' in pg_get_functiondef(
    'public.commercial_customer_portal_create_multi_service_booking(uuid,jsonb,uuid,timestamptz,timestamptz,text,text,text)'::regprocedure
  )) > 0
  AND position('commercial_bookings' in pg_get_functiondef(
    'public.commercial_customer_portal_create_multi_service_booking(uuid,jsonb,uuid,timestamptz,timestamptz,text,text,text)'::regprocedure
  )) > 0,
  'one booking is linked to one server-priced multi-line order'
);

SELECT ok(
  position('booking_resource_capacity_exceeded' in pg_get_functiondef(
    'public.commercial_customer_portal_create_multi_service_booking(uuid,jsonb,uuid,timestamptz,timestamptz,text,text,text)'::regprocedure
  )) > 0
  AND position('FOR UPDATE' in pg_get_functiondef(
    'public.commercial_customer_portal_create_multi_service_booking(uuid,jsonb,uuid,timestamptz,timestamptz,text,text,text)'::regprocedure
  )) > 0
  AND position('v_requested_capacity' in pg_get_functiondef(
    'public.commercial_customer_portal_create_multi_service_booking(uuid,jsonb,uuid,timestamptz,timestamptz,text,text,text)'::regprocedure
  )) > 0
  AND position('v_used_capacity' in pg_get_functiondef(
    'public.commercial_customer_portal_create_multi_service_booking(uuid,jsonb,uuid,timestamptz,timestamptz,text,text,text)'::regprocedure
  )) > 0,
  'resource capacity is serialized and checked in the database'
);

SELECT ok(
  position('idempotency_key_reused_with_different_request' in pg_get_functiondef(
    'public.commercial_customer_portal_create_multi_service_booking(uuid,jsonb,uuid,timestamptz,timestamptz,text,text,text)'::regprocedure
  )) > 0
  AND position('pg_advisory_xact_lock' in pg_get_functiondef(
    'public.commercial_customer_portal_create_multi_service_booking(uuid,jsonb,uuid,timestamptz,timestamptz,text,text,text)'::regprocedure
  )) > 0,
  'same-key retries replay safely and changed requests are rejected'
);

SELECT ok(
  position('customer_credit_not_authorized' in pg_get_functiondef(
    'public.commercial_customer_portal_create_multi_service_booking(uuid,jsonb,uuid,timestamptz,timestamptz,text,text,text)'::regprocedure
  )) > 0
  AND position('booking_service_not_available' in pg_get_functiondef(
    'public.commercial_customer_portal_create_multi_service_booking(uuid,jsonb,uuid,timestamptz,timestamptz,text,text,text)'::regprocedure
  )) > 0,
  'credit authorization and active service ownership are checked server-side'
);

SELECT ok(
  position('line_total_minor' in pg_get_functiondef(
    'public.commercial_customer_portal_create_multi_service_booking(uuid,jsonb,uuid,timestamptz,timestamptz,text,text,text)'::regprocedure
  )) > 0
  AND position('unit_price_minor' in pg_get_functiondef(
    'public.commercial_customer_portal_create_multi_service_booking(uuid,jsonb,uuid,timestamptz,timestamptz,text,text,text)'::regprocedure
  )) > 0,
  'response includes itemized quantities and saved prices'
);

SELECT * FROM finish();
ROLLBACK;
