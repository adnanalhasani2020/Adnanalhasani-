-- Surface merchant-selected alias/icon for delegated child accounts in the merchant's operational order and booking queues.
CREATE OR REPLACE FUNCTION public.commercial_customer_portal_list_organization_orders_v2(
  p_organization_id uuid, p_limit integer DEFAULT 200, p_include_hidden boolean DEFAULT false
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active'
      AND m.role IN ('owner','admin','manager')
  ) THEN RAISE EXCEPTION 'customer_order_management_permission_required' USING ERRCODE='42501'; END IF;
  IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 200 THEN RAISE EXCEPTION 'invalid_customer_order_list_limit' USING ERRCODE='22023'; END IF;

  SELECT coalesce(jsonb_agg(row_data ORDER BY created_at DESC, id DESC),'[]'::jsonb)
  INTO v_result
  FROM (
    SELECT o.id,o.created_at,jsonb_build_object(
      'id',o.id,'organization_id',o.organization_id,'order_number',o.order_number,
      'created_at',o.created_at,'updated_at',o.updated_at,'status',o.status,
      'payment_status',o.payment_status,'order_type',o.order_type,'currency_code',o.currency_code,
      'total_minor',o.total_minor,'customer_note',coalesce(o.customer_note,''),
      'customer_name',coalesce(c.display_name,''),
      'delegated_display_name',coalesce(nullif(d.merchant_display_name,''),ca.display_name,''),
      'delegated_icon_key',coalesce(d.merchant_icon_key,''),'acting_account_number',coalesce(ca.account_number,''),
      'merchant_hidden',o.merchant_hidden_at IS NOT NULL,
      'merchant_hidden_at',o.merchant_hidden_at,'merchant_reviewed_at',o.merchant_reviewed_at,
      'merchant_decision_reason',coalesce(o.merchant_decision_reason,''),
      'fulfillment',(
        SELECT jsonb_build_object('sale_id',f.sale_id,'receipt_number',f.result->'receipt_number',
          'fulfilled_at',f.fulfilled_at,'fulfilled_by',f.fulfilled_by,'location_id',f.location_id,
          'paid_minor',f.result->'paid_minor','outstanding_minor',f.result->'outstanding_minor',
          'journal_entry_id',f.result->'journal_entry_id')
        FROM public.commercial_customer_order_fulfillments f WHERE f.order_id=o.id
      ),
      'fulfillment_supported',(
        o.order_type='purchase' AND o.payment_status IN ('unpaid','credit')
        AND NOT EXISTS(SELECT 1 FROM public.commercial_customer_order_items x WHERE x.order_id=o.id
          AND (x.item_type<>'product' OR x.quantity<=0 OR x.quantity<>trunc(x.quantity) OR x.quantity>100000))
        AND EXISTS(SELECT 1 FROM public.commercial_customer_order_items x WHERE x.order_id=o.id)
        AND (SELECT count(DISTINCT a.id) FROM public.commercial_customer_order_items x
          JOIN public.commercial_offerings ofr ON ofr.id=x.offering_id
          JOIN public.commercial_activities a ON a.id=ofr.activity_id
          WHERE x.order_id=o.id AND a.organization_id=o.organization_id)=1
      ),
      'items',coalesce((SELECT jsonb_agg(jsonb_build_object(
        'offering_id',oi.offering_id,'name',oi.item_name_snapshot,'item_type',oi.item_type,
        'quantity',oi.quantity,'unit_price_minor',oi.unit_price_minor,'line_total_minor',oi.line_total_minor
      ) ORDER BY oi.created_at,oi.id) FROM public.commercial_customer_order_items oi WHERE oi.order_id=o.id),'[]'::jsonb)
    ) row_data
    FROM public.commercial_customer_orders o
    LEFT JOIN public.commercial_customer_party_accounts cpa
      ON cpa.organization_id=o.organization_id AND cpa.party_account_id=o.buyer_account_id
    LEFT JOIN public.commercial_customers c ON c.id=cpa.customer_id AND c.organization_id=cpa.organization_id
    LEFT JOIN public.commercial_account_delegations d ON d.id=o.delegated_access_id AND d.organization_id=o.organization_id
    LEFT JOIN public.commercial_party_accounts ca ON ca.id=o.acting_account_id AND ca.account_type='personal'
    WHERE o.organization_id=p_organization_id
      AND (p_include_hidden OR o.merchant_hidden_at IS NULL)
    ORDER BY o.created_at DESC,o.id DESC LIMIT p_limit
  ) q;
  RETURN v_result;
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_list_organization_bookings(
  p_organization_id uuid, p_limit integer DEFAULT 200, p_include_hidden boolean DEFAULT false
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id
      AND m.user_id=v_uid AND m.state='active' AND m.role IN ('owner','admin','manager')
  ) THEN RAISE EXCEPTION 'customer_order_management_permission_required' USING ERRCODE='42501'; END IF;
  IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 200 THEN RAISE EXCEPTION 'invalid_customer_order_list_limit' USING ERRCODE='22023'; END IF;
  SELECT coalesce(jsonb_agg(row_data ORDER BY starts_at DESC,id DESC),'[]'::jsonb) INTO v_result
  FROM (
    SELECT b.id,b.starts_at,jsonb_build_object(
      'id',b.id,'organization_id',b.organization_id,'booking_number',b.booking_number,
      'order_id',b.order_id,'order_number',o.order_number,'created_at',b.created_at,
      'starts_at',b.starts_at,'ends_at',b.ends_at,'status',b.status,'currency_code',b.currency_code,
      'price_minor',b.price_minor,'notes',coalesce(b.notes,''),'customer_name',coalesce(c.display_name,''),
      'delegated_display_name',coalesce(nullif(d.merchant_display_name,''),ca.display_name,''),
      'delegated_icon_key',coalesce(d.merchant_icon_key,''),'acting_account_number',coalesce(ca.account_number,''),
      'resource_name',coalesce(r.name,''),'timezone',coalesce(r.timezone,'UTC'),'order_status',coalesce(o.status,'missing'),
      'payment_status',coalesce(o.payment_status,'unknown'),'merchant_hidden',b.merchant_hidden_at IS NOT NULL,
      'merchant_decision_reason',coalesce(b.merchant_decision_reason,''),
      'items',coalesce((SELECT jsonb_agg(jsonb_build_object(
        'name',oi.item_name_snapshot,'quantity',oi.quantity,'unit_price_minor',oi.unit_price_minor,
        'line_total_minor',oi.line_total_minor,'item_type',oi.item_type
      ) ORDER BY oi.created_at,oi.id) FROM public.commercial_customer_order_items oi WHERE oi.order_id=b.order_id),'[]'::jsonb)
    ) row_data
    FROM public.commercial_bookings b
    LEFT JOIN public.commercial_booking_resources r ON r.id=b.resource_id AND r.organization_id=b.organization_id
    LEFT JOIN public.commercial_customer_orders o ON o.id=b.order_id AND o.organization_id=b.organization_id
    LEFT JOIN public.commercial_customer_party_accounts cpa ON cpa.organization_id=b.organization_id AND cpa.party_account_id=b.customer_account_id
    LEFT JOIN public.commercial_customers c ON c.id=cpa.customer_id AND c.organization_id=cpa.organization_id
    LEFT JOIN public.commercial_account_delegations d ON d.id=b.delegated_access_id AND d.organization_id=b.organization_id
    LEFT JOIN public.commercial_party_accounts ca ON ca.id=b.acting_account_id AND ca.account_type='personal'
    WHERE b.organization_id=p_organization_id AND (p_include_hidden OR b.merchant_hidden_at IS NULL)
    ORDER BY b.starts_at DESC,b.id DESC LIMIT p_limit
  ) q;
  RETURN v_result;
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_customer_portal_list_organization_orders_v2(uuid,integer,boolean) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_list_organization_bookings(uuid,integer,boolean) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_list_organization_orders_v2(uuid,integer,boolean) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_list_organization_bookings(uuid,integer,boolean) TO authenticated;
