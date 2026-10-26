-- Manager-only queue for reviewing and fulfilling customer portal product orders.
CREATE OR REPLACE FUNCTION public.commercial_customer_portal_list_organization_orders(
  p_organization_id uuid, p_limit integer DEFAULT 100
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_org_id uuid := p_organization_id;
  v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501';
  END IF;
  IF v_org_id IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members
    WHERE organization_id = v_org_id AND user_id = v_uid AND state = 'active'
      AND role IN ('owner','admin','manager')
  ) THEN
    RAISE EXCEPTION 'customer_order_management_permission_required' USING ERRCODE='42501';
  END IF;
  IF p_limit IS NULL OR p_limit < 1 OR p_limit > 200 THEN
    RAISE EXCEPTION 'invalid_customer_order_list_limit' USING ERRCODE='22023';
  END IF;

  SELECT coalesce(jsonb_agg(row_data ORDER BY created_at DESC, id DESC), '[]'::jsonb)
  INTO v_result
  FROM (
    SELECT o.id, o.created_at, jsonb_build_object(
      'id', o.id,
      'organization_id', o.organization_id,
      'order_number', o.order_number,
      'created_at', o.created_at,
      'status', o.status,
      'payment_status', o.payment_status,
      'order_type', o.order_type,
      'currency_code', o.currency_code,
      'total_minor', o.total_minor,
      'customer_note', coalesce(o.customer_note, ''),
      'customer_name', coalesce(c.display_name, ''),
      'fulfillment_supported', (
        o.order_type = 'purchase'
        AND o.payment_status IN ('unpaid','credit')
        AND NOT EXISTS (
          SELECT 1 FROM public.commercial_customer_order_items x
          WHERE x.order_id = o.id
            AND (x.item_type <> 'product' OR x.quantity <= 0
              OR x.quantity <> trunc(x.quantity) OR x.quantity > 100000)
        )
        AND EXISTS (SELECT 1 FROM public.commercial_customer_order_items x WHERE x.order_id=o.id)
        AND (SELECT count(DISTINCT a.id)
          FROM public.commercial_customer_order_items x
          JOIN public.commercial_offerings ofr ON ofr.id=x.offering_id
          JOIN public.commercial_activities a ON a.id=ofr.activity_id
          WHERE x.order_id=o.id AND a.organization_id=o.organization_id) = 1
      ),
      'items', coalesce((
        SELECT jsonb_agg(jsonb_build_object(
          'offering_id', oi.offering_id,
          'name', oi.item_name_snapshot,
          'item_type', oi.item_type,
          'quantity', oi.quantity,
          'unit_price_minor', oi.unit_price_minor,
          'line_total_minor', oi.line_total_minor
        ) ORDER BY oi.created_at, oi.id)
        FROM public.commercial_customer_order_items oi
        WHERE oi.order_id=o.id
      ), '[]'::jsonb)
    ) AS row_data
    FROM public.commercial_customer_orders o
    LEFT JOIN public.commercial_customer_party_accounts cpa
      ON cpa.organization_id=o.organization_id AND cpa.party_account_id=o.buyer_account_id
    LEFT JOIN public.commercial_customers c
      ON c.id=cpa.customer_id AND c.organization_id=cpa.organization_id
    WHERE o.organization_id=v_org_id
      AND o.status IN ('pending','confirmed','in_progress','fulfilled','cancelled','rejected')
    ORDER BY o.created_at DESC, o.id DESC
    LIMIT p_limit
  ) q;

  RETURN v_result;
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_customer_portal_list_organization_orders(uuid,integer) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_list_organization_orders(integer) TO authenticated;
