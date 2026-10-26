-- Structural regression checks for retry-safe, atomic customer booking cancellation.
BEGIN;
SELECT plan(5);

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

SELECT ok(
  position('idempotent_replay' in pg_get_functiondef(
    'public.commercial_customer_portal_cancel_booking(uuid,text)'::regprocedure
  )) > 0
  AND position('cancelled_booking_order_state_inconsistent' in pg_get_functiondef(
    'public.commercial_customer_portal_cancel_booking(uuid,text)'::regprocedure
  )) > 0
  AND position('if v_booking.status = ''cancelled'' then' in pg_get_functiondef(
    'public.commercial_customer_portal_cancel_booking(uuid,text)'::regprocedure
  )) > 0,
  'retry returns success only for an owned cancelled booking with a cancelled linked order'
);

SELECT * FROM finish();
ROLLBACK;
