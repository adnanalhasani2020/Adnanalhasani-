-- Read-only discovery for active delegations used by the customer portal.
CREATE OR REPLACE FUNCTION public.commercial_account_list_my_active_delegations()
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid := auth.uid(); v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'delegation_id',d.id,'organization_id',o.id,'name',o.name,
    'description','استخدام المؤسسة عبر حساب رئيسي مفوض',
    'currency_code',d.spend_currency_code,'logo_url',o.logo_url,
    'allow_credit',coalesce(parent_customer_account.allow_credit,false),
    'parent_account_number',pa.account_number,'parent_display_name',pa.display_name,
    'merchant_display_name',d.merchant_display_name,'permissions',d.permissions,
    'spend_limit_minor',d.spend_limit_minor,'spent_minor',d.spent_minor,
    'activity_id',d.activity_id
  ) ORDER BY o.name, d.created_at DESC),'[]'::jsonb)
  INTO v_result
  FROM public.commercial_account_delegations d
  JOIN public.commercial_party_accounts ca ON ca.id=d.child_account_id
  JOIN public.commercial_party_accounts pa ON pa.id=d.parent_account_id
  JOIN public.commercial_organizations o ON o.id=d.organization_id AND o.state='active' AND o.deleted_at IS NULL
  LEFT JOIN LATERAL (
    SELECT a.allow_credit
    FROM public.commercial_customers c
    JOIN public.commercial_customer_party_accounts cpa ON cpa.customer_id=c.id AND cpa.organization_id=c.organization_id
    JOIN public.commercial_party_accounts a ON a.id=cpa.party_account_id AND a.organization_id=cpa.organization_id
      AND a.account_type IN ('business','organization') AND a.status='active'
    WHERE c.organization_id=d.organization_id AND c.auth_user_id=pa.owner_user_id AND c.state='active'
    ORDER BY c.created_at DESC LIMIT 1
  ) parent_customer_account ON true
  WHERE ca.owner_user_id=v_uid AND ca.account_type='personal' AND ca.status='active'
    AND d.status='active' AND (d.expires_at IS NULL OR d.expires_at>now())
    AND (coalesce((d.permissions->>'purchase')::boolean,false) OR coalesce((d.permissions->>'book')::boolean,false));
  RETURN v_result;
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_account_list_delegated_offerings(p_delegation_id uuid)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid := auth.uid(); v_row public.commercial_account_delegations%ROWTYPE; v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  SELECT d.* INTO v_row FROM public.commercial_account_delegations d
  JOIN public.commercial_party_accounts ca ON ca.id=d.child_account_id
  WHERE d.id=p_delegation_id AND ca.owner_user_id=v_uid AND ca.account_type='personal'
    AND d.status='active' AND (d.expires_at IS NULL OR d.expires_at>now())
    AND (coalesce((d.permissions->>'purchase')::boolean,false) OR coalesce((d.permissions->>'book')::boolean,false));
  IF NOT FOUND THEN RAISE EXCEPTION 'delegated_access_not_active_or_not_owned' USING ERRCODE='42501'; END IF;
  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'id',ofr.id,'name',p.name,'description',p.description,'unit_name',p.unit_name,
    'price_minor',ofr.price_minor,'currency_code',ofr.currency_code,
    'item_type',CASE WHEN a.activity_type='service' THEN 'service' ELSE 'product' END
  ) ORDER BY p.name),'[]'::jsonb)
  INTO v_result
  FROM public.commercial_offerings ofr
  JOIN public.commercial_activities a ON a.id=ofr.activity_id
  JOIN public.commercial_products p ON p.id=ofr.product_id
  WHERE a.organization_id=v_row.organization_id AND a.state='active' AND a.deleted_at IS NULL
    AND (v_row.activity_id IS NULL OR a.id=v_row.activity_id)
    AND ofr.state='active' AND ofr.valid_from<=now() AND (ofr.valid_to IS NULL OR ofr.valid_to>now())
    AND p.state='active'
    AND ((coalesce((v_row.permissions->>'purchase')::boolean,false))
      OR (a.activity_type='service' AND coalesce((v_row.permissions->>'book')::boolean,false)));
  RETURN v_result;
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_account_list_delegated_booking_resources(p_delegation_id uuid)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid := auth.uid(); v_row public.commercial_account_delegations%ROWTYPE; v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  SELECT d.* INTO v_row FROM public.commercial_account_delegations d
  JOIN public.commercial_party_accounts ca ON ca.id=d.child_account_id
  WHERE d.id=p_delegation_id AND ca.owner_user_id=v_uid AND ca.account_type='personal'
    AND d.status='active' AND (d.expires_at IS NULL OR d.expires_at>now())
    AND coalesce((d.permissions->>'book')::boolean,false);
  IF NOT FOUND THEN RAISE EXCEPTION 'delegated_booking_permission_required' USING ERRCODE='42501'; END IF;
  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'id',r.id,'name',r.name,'resource_type',r.resource_type,'capacity',r.capacity,'timezone',r.timezone
  ) ORDER BY r.name),'[]'::jsonb)
  INTO v_result
  FROM public.commercial_booking_resources r
  WHERE r.organization_id=v_row.organization_id AND r.status='active';
  RETURN v_result;
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_account_list_my_active_delegations() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_account_list_delegated_offerings(uuid) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_account_list_delegated_booking_resources(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_account_list_my_active_delegations() TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_account_list_delegated_offerings(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_account_list_delegated_booking_resources(uuid) TO authenticated;
