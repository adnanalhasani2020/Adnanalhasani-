-- Structural regression checks for atomic customer booking cancellation.
BEGIN;
SELECT plan(4);

SELECT has_function(
  'public',
  'commercial_customer_portal_cancel_booking',
  ARRAY['uuid','text'],
  'customer booking cancellation RPC exists'
);

SELECT ok(
  position('booking_order_cancellation_not_allowed_for_status' in pg_get_functiondef(
    'public.commercial_customer_portal_cancel_booking(uuid,text)'::regprocedure
  )) > 0
  AND position('FOR UPDATE' in pg_get_functiondef(
    'public.commercial_customer_portal_cancel_booking(uuid,text)'::regprocedure
  )) > 0,
  'linked order is locked and must still be pending before cancellation'
);

SELECT ok(
  position('booking_order_missing' in pg_get_functiondef(
    'public.commercial_customer_portal_cancel_booking(uuid,text)'::regprocedure
  )) > 0
  AND position('booking_order_not_found' in pg_get_functiondef(
    'public.commercial_customer_portal_cancel_booking(uuid,text)'::regprocedure
  )) > 0,
  'cancellation rejects a missing or mismatched linked order'
);

SELECT ok(
  position('booking_cancellation_race_retry' in pg_get_functiondef(
    'public.commercial_customer_portal_cancel_booking(uuid,text)'::regprocedure
  )) > 0
  AND position('booking_order_cancellation_race_retry' in pg_get_functiondef(
    'public.commercial_customer_portal_cancel_booking(uuid,text)'::regprocedure
  )) > 0
  AND position('order_status' in pg_get_functiondef(
    'public.commercial_customer_portal_cancel_booking(uuid,text)'::regprocedure
  )) > 0,
  'concurrent state changes abort the transaction rather than leave mismatched states'
);

SELECT * FROM finish();
ROLLBACK;
