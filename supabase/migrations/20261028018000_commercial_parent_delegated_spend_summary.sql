-- Consolidated parent report across every delegated child and institution.
CREATE OR REPLACE FUNCTION public.commercial_account_parent_spend_summary()
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;

  SELECT jsonb_build_object(
    'delegations',coalesce(jsonb_agg(jsonb_build_object(
      'delegation_id',d.id,
      'organization_id',o.id,
      'organization_name',o.name,
      'parent_account_number',pa.account_number,
      'child_account_number',ca.account_number,
      'child_display_name',coalesce(d.merchant_display_name,ca.display_name),
      'merchant_icon_key',d.merchant_icon_key,
      'status',CASE WHEN d.status='active' AND d.expires_at IS NOT NULL AND d.expires_at<=now() THEN 'expired' ELSE d.status END,
      'currency_code',d.spend_currency_code,
      'spend_limit_minor',d.spend_limit_minor,
      'tracked_spend_minor',d.spent_minor,
      'expires_at',d.expires_at,
      'orders_count',coalesce(s.orders_count,0),
      'purchase_total_minor',coalesce(s.purchase_total_minor,0),
      'booking_total_minor',coalesce(s.booking_total_minor,0),
      'other_total_minor',coalesce(s.other_total_minor,0)
    ) ORDER BY o.name,coalesce(d.merchant_display_name,ca.display_name),d.created_at DESC),'[]'::jsonb)
  ) INTO v_result
  FROM public.commercial_account_delegations d
  JOIN public.commercial_party_accounts pa ON pa.id=d.parent_account_id
  JOIN public.commercial_party_accounts ca ON ca.id=d.child_account_id
  JOIN public.commercial_organizations o ON o.id=d.organization_id
  LEFT JOIN LATERAL (
    SELECT
      count(*) FILTER (WHERE x.status NOT IN ('cancelled','rejected') OR x.payment_status NOT IN ('unpaid','credit','refunded'))::integer AS orders_count,
      coalesce(sum(x.total_minor) FILTER (WHERE x.order_type='purchase' AND (x.status NOT IN ('cancelled','rejected') OR x.payment_status NOT IN ('unpaid','credit','refunded'))),0)::bigint AS purchase_total_minor,
      coalesce(sum(x.total_minor) FILTER (WHERE x.order_type IN ('booking','service') AND (x.status NOT IN ('cancelled','rejected') OR x.payment_status NOT IN ('unpaid','credit','refunded'))),0)::bigint AS booking_total_minor,
      coalesce(sum(x.total_minor) FILTER (WHERE x.order_type NOT IN ('purchase','booking','service') AND (x.status NOT IN ('cancelled','rejected') OR x.payment_status NOT IN ('unpaid','credit','refunded'))),0)::bigint AS other_total_minor
    FROM public.commercial_customer_orders x
    WHERE x.delegated_access_id=d.id
      AND x.principal_account_id=d.parent_account_id
      AND x.acting_account_id=d.child_account_id
      AND x.organization_id=d.organization_id
  ) s ON true
  WHERE pa.owner_user_id=v_uid AND pa.account_type='personal' AND pa.status='active';

  RETURN v_result;
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_account_parent_spend_summary() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_account_parent_spend_summary() TO authenticated;
