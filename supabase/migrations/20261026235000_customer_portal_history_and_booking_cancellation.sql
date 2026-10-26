-- Customer portal history and safe customer-requested booking cancellation.
CREATE OR REPLACE FUNCTION public.commercial_customer_portal_list_history(p_organization_id uuid DEFAULT NULL)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_orders jsonb;
  v_bookings jsonb;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501';
  END IF;
  PERFORM public.commercial_customer_portal_link_verified_email();

  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'id', o.id,
    'organization_id', o.organization_id,
    'organization_name', org.name,
    'order_number', o.order_number,
    'created_at', o.created_at,
    'status', o.status,
    'payment_status', o.payment_status,
    'order_type', o.order_type,
    'currency_code', o.currency_code,
    'subtotal_minor', o.subtotal_minor,
    'discount_minor', o.discount_minor,
    'tax_minor', o.tax_minor,
    'total_minor', o.total_minor,
    'customer_note', coalesce(o.customer_note, ''),
    'reserved_from', o.reserved_from,
    'reserved_until', o.reserved_until,
    'items', coalesce((
      SELECT jsonb_agg(jsonb_build_object(
        'id', oi.id,
        'offering_id', oi.offering_id,
        'name', oi.item_name_snapshot,
        'item_type', oi.item_type,
        'quantity', oi.quantity,
        'unit_price_minor', oi.unit_price_minor,
        'line_total_minor', oi.line_total_minor
      ) ORDER BY oi.created_at, oi.id)
      FROM public.commercial_customer_order_items oi
      WHERE oi.order_id = o.id
    ), '[]'::jsonb)
  ) ORDER BY o.created_at DESC, o.id DESC), '[]'::jsonb)
  INTO v_orders
  FROM public.commercial_customer_orders o
  JOIN public.commercial_party_accounts a
    ON a.id = o.buyer_account_id AND a.organization_id = o.organization_id
  JOIN public.commercial_customer_party_accounts cpa
    ON cpa.party_account_id = a.id AND cpa.organization_id = a.organization_id
  JOIN public.commercial_customers c
    ON c.id = cpa.customer_id AND c.organization_id = cpa.organization_id
   AND c.state = 'active' AND c.auth_user_id = v_uid
  JOIN public.commercial_organizations org
    ON org.id = o.organization_id AND org.state = 'active' AND org.deleted_at IS NULL
  WHERE (p_organization_id IS NULL OR o.organization_id = p_organization_id);

  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'id', b.id,
    'organization_id', b.organization_id,
    'organization_name', org.name,
    'booking_number', b.booking_number,
    'order_id', b.order_id,
    'order_number', o.order_number,
    'status', b.status,
    'starts_at', b.starts_at,
    'ends_at', b.ends_at,
    'timezone', coalesce(r.timezone, 'UTC'),
    'resource_id', b.resource_id,
    'resource_name', coalesce(r.name, ''),
    'resource_type', coalesce(r.resource_type, ''),
    'price_minor', b.price_minor,
    'paid_minor', b.paid_minor,
    'currency_code', b.currency_code,
    'notes', coalesce(b.notes, ''),
    'cancellation_reason', coalesce(b.cancellation_reason, '')
  ) ORDER BY b.starts_at DESC, b.id DESC), '[]'::jsonb)
  INTO v_bookings
  FROM public.commercial_bookings b
  JOIN public.commercial_party_accounts a
    ON a.id = b.customer_account_id AND a.organization_id = b.organization_id
  JOIN public.commercial_customer_party_accounts cpa
    ON cpa.party_account_id = a.id AND cpa.organization_id = a.organization_id
  JOIN public.commercial_customers c
    ON c.id = cpa.customer_id AND c.organization_id = cpa.organization_id
   AND c.state = 'active' AND c.auth_user_id = v_uid
  JOIN public.commercial_organizations org
    ON org.id = b.organization_id AND org.state = 'active' AND org.deleted_at IS NULL
  LEFT JOIN public.commercial_booking_resources r
    ON r.id = b.resource_id AND r.organization_id = b.organization_id
  LEFT JOIN public.commercial_customer_orders o
    ON o.id = b.order_id AND o.organization_id = b.organization_id
  WHERE (p_organization_id IS NULL OR b.organization_id = p_organization_id);

  RETURN jsonb_build_object('orders', v_orders, 'bookings', v_bookings, 'refreshed_at', now());
END;
$function$;

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

  SELECT * INTO v_booking
  FROM public.commercial_bookings
  WHERE id = p_booking_id AND customer_account_id = v_account_id
  FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'booking_not_found_or_not_owned' USING ERRCODE='42501';
  END IF;
  IF v_booking.status <> 'requested' THEN
    RAISE EXCEPTION 'booking_cancellation_not_allowed_for_status' USING ERRCODE='22023';
  END IF;
  IF v_booking.starts_at <= now() THEN
    RAISE EXCEPTION 'booking_cancellation_window_closed' USING ERRCODE='22023';
  END IF;

  UPDATE public.commercial_bookings
  SET status = 'cancelled',
      cancellation_reason = coalesce(v_reason, 'Cancelled by customer'),
      updated_at = now()
  WHERE id = v_booking.id;

  -- Only cancel the still-pending order created for this booking. Never rewrite a reviewed order.
  UPDATE public.commercial_customer_orders
  SET status = 'cancelled', updated_at = now()
  WHERE id = v_booking.order_id
    AND organization_id = v_booking.organization_id
    AND status = 'pending';

  RETURN jsonb_build_object(
    'booking_id', v_booking.id,
    'booking_number', v_booking.booking_number,
    'status', 'cancelled',
    'order_id', v_booking.order_id
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_customer_portal_list_history(uuid) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_cancel_booking(uuid, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_list_history(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_cancel_booking(uuid, text) TO authenticated;

CREATE INDEX IF NOT EXISTS commercial_bookings_customer_account_recent_idx
  ON public.commercial_bookings(customer_account_id, starts_at DESC);
