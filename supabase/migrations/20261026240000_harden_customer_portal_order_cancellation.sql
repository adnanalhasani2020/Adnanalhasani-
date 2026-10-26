-- Harden customer cancellation against NULL payment_status values.
-- The original NOT IN predicate evaluates to NULL for a NULL status, which PL/pgSQL
-- treats as not-true; use null-safe comparisons so only known unpaid/credit orders pass.
CREATE OR REPLACE FUNCTION public.commercial_customer_portal_cancel_order(
  p_order_id uuid,
  p_cancellation_reason text DEFAULT NULL
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_account_id uuid;
  v_order public.commercial_customer_orders%ROWTYPE;
  v_reason text := nullif(btrim(p_cancellation_reason), '');
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501';
  END IF;
  IF p_order_id IS NULL THEN
    RAISE EXCEPTION 'order_id_required' USING ERRCODE='22023';
  END IF;
  IF v_reason IS NOT NULL AND length(v_reason) > 500 THEN
    RAISE EXCEPTION 'cancellation_reason_too_long' USING ERRCODE='22023';
  END IF;

  PERFORM public.commercial_customer_portal_link_verified_email();

  SELECT a.id
    INTO v_account_id
  FROM public.commercial_customer_orders o
  JOIN public.commercial_party_accounts a
    ON a.id = o.buyer_account_id
   AND a.organization_id = o.organization_id
  JOIN public.commercial_customer_party_accounts cpa
    ON cpa.party_account_id = a.id
   AND cpa.organization_id = a.organization_id
  JOIN public.commercial_customers c
    ON c.id = cpa.customer_id
   AND c.organization_id = cpa.organization_id
   AND c.state = 'active'
   AND c.auth_user_id = v_uid
  WHERE o.id = p_order_id
  LIMIT 1;

  IF v_account_id IS NULL THEN
    RAISE EXCEPTION 'order_not_found_or_not_owned' USING ERRCODE='42501';
  END IF;

  SELECT *
    INTO v_order
  FROM public.commercial_customer_orders o
  WHERE o.id = p_order_id
    AND o.buyer_account_id = v_account_id
  FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'order_not_found_or_not_owned' USING ERRCODE='42501';
  END IF;

  -- Idempotent retry: never perform a second state transition.
  IF v_order.status = 'cancelled' THEN
    RETURN jsonb_build_object(
      'order_id', v_order.id,
      'order_number', v_order.order_number,
      'status', 'cancelled',
      'already_cancelled', true
    );
  END IF;

  -- Booking orders must use the dedicated booking cancellation policy.
  IF v_order.order_type = 'booking'
     OR EXISTS (
       SELECT 1
       FROM public.commercial_bookings b
       WHERE b.order_id = v_order.id
         AND b.organization_id = v_order.organization_id
     ) THEN
    RAISE EXCEPTION 'booking_order_requires_booking_cancellation' USING ERRCODE='22023';
  END IF;

  IF v_order.status IS DISTINCT FROM 'pending' THEN
    RAISE EXCEPTION 'order_cancellation_not_allowed_for_status' USING ERRCODE='22023';
  END IF;
  IF v_order.payment_status IS DISTINCT FROM 'unpaid'
     AND v_order.payment_status IS DISTINCT FROM 'credit' THEN
    RAISE EXCEPTION 'order_cancellation_requires_unpaid_order' USING ERRCODE='22023';
  END IF;

  UPDATE public.commercial_customer_orders
  SET status = 'cancelled',
      customer_cancelled_at = now(),
      customer_cancellation_reason = coalesce(v_reason, 'Cancelled by customer'),
      updated_at = now()
  WHERE id = v_order.id
    AND status = 'pending';

  IF NOT FOUND THEN
    RAISE EXCEPTION 'order_cancellation_race_retry' USING ERRCODE='40001';
  END IF;

  RETURN jsonb_build_object(
    'order_id', v_order.id,
    'order_number', v_order.order_number,
    'status', 'cancelled',
    'already_cancelled', false
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_customer_portal_cancel_order(uuid, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_cancel_order(uuid, text) TO authenticated;
