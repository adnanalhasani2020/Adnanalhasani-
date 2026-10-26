-- Keep customer booking and its linked order in one consistent state on cancellation.
-- Reject cancellation before any state change if the linked order is missing or no longer pending.
CREATE OR REPLACE FUNCTION public.commercial_customer_portal_cancel_booking(
  p_booking_id uuid,
  p_cancellation_reason text DEFAULT NULL
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_booking public.commercial_bookings%ROWTYPE;
  v_order public.commercial_customer_orders%ROWTYPE;
  v_account_id uuid;
  v_reason text := nullif(btrim(p_cancellation_reason), '');
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501';
  END IF;
  IF p_booking_id IS NULL THEN
    RAISE EXCEPTION 'booking_id_required' USING ERRCODE='22023';
  END IF;
  IF v_reason IS NOT NULL AND length(v_reason) > 500 THEN
    RAISE EXCEPTION 'cancellation_reason_too_long' USING ERRCODE='22023';
  END IF;
  PERFORM public.commercial_customer_portal_link_verified_email();

  SELECT a.id INTO v_account_id
  FROM public.commercial_bookings b
  JOIN public.commercial_party_accounts a
    ON a.id = b.customer_account_id AND a.organization_id = b.organization_id
  JOIN public.commercial_customer_party_accounts cpa
    ON cpa.party_account_id = a.id AND cpa.organization_id = a.organization_id
  JOIN public.commercial_customers c
    ON c.id = cpa.customer_id AND c.organization_id = cpa.organization_id
   AND c.state = 'active' AND c.auth_user_id = v_uid
  WHERE b.id = p_booking_id
  LIMIT 1;

  IF v_account_id IS NULL THEN
    RAISE EXCEPTION 'booking_not_found_or_not_owned' USING ERRCODE='42501';
  END IF;

  -- Lock the booking first, then its linked order. No state changes occur until both
  -- rows are locked and both cancellation policies have passed.
  SELECT * INTO v_booking
  FROM public.commercial_bookings
  WHERE id = p_booking_id AND customer_account_id = v_account_id
  FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'booking_not_found_or_not_owned' USING ERRCODE='42501';
  END IF;
  IF v_booking.status IS DISTINCT FROM 'requested' THEN
    RAISE EXCEPTION 'booking_cancellation_not_allowed_for_status' USING ERRCODE='22023';
  END IF;
  IF v_booking.starts_at <= now() THEN
    RAISE EXCEPTION 'booking_cancellation_window_closed' USING ERRCODE='22023';
  END IF;
  IF v_booking.order_id IS NULL THEN
    RAISE EXCEPTION 'booking_order_missing' USING ERRCODE='22023';
  END IF;

  SELECT * INTO v_order
  FROM public.commercial_customer_orders o
  WHERE o.id = v_booking.order_id
    AND o.organization_id = v_booking.organization_id
    AND o.buyer_account_id = v_account_id
  FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'booking_order_not_found' USING ERRCODE='22023';
  END IF;
  IF v_order.status IS DISTINCT FROM 'pending' THEN
    RAISE EXCEPTION 'booking_order_cancellation_not_allowed_for_status' USING ERRCODE='22023';
  END IF;
  IF v_order.payment_status IS DISTINCT FROM 'unpaid'
     AND v_order.payment_status IS DISTINCT FROM 'credit' THEN
    RAISE EXCEPTION 'booking_order_cancellation_requires_unpaid_order' USING ERRCODE='22023';
  END IF;

  UPDATE public.commercial_bookings
  SET status = 'cancelled',
      cancellation_reason = coalesce(v_reason, 'Cancelled by customer'),
      updated_at = now()
  WHERE id = v_booking.id
    AND status = 'requested';

  IF NOT FOUND THEN
    RAISE EXCEPTION 'booking_cancellation_race_retry' USING ERRCODE='40001';
  END IF;

  UPDATE public.commercial_customer_orders
  SET status = 'cancelled', updated_at = now()
  WHERE id = v_order.id
    AND organization_id = v_booking.organization_id
    AND status = 'pending';

  IF NOT FOUND THEN
    -- Raising aborts the transaction, rolling back the booking update as well.
    RAISE EXCEPTION 'booking_order_cancellation_race_retry' USING ERRCODE='40001';
  END IF;

  RETURN jsonb_build_object(
    'booking_id', v_booking.id,
    'booking_number', v_booking.booking_number,
    'status', 'cancelled',
    'order_id', v_order.id,
    'order_status', 'cancelled'
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_customer_portal_cancel_booking(uuid, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_cancel_booking(uuid, text) TO authenticated;
