-- Delegated purchase reporting with explicit scope and permission checks.
CREATE OR REPLACE FUNCTION public.commercial_account_delegated_spend_report(p_delegation_id uuid)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_row public.commercial_account_delegations%ROWTYPE;
  v_parent_owner uuid;
  v_child_owner uuid;
  v_is_manager boolean := false;
  v_can_read boolean := false;
  v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  SELECT d.* INTO v_row FROM public.commercial_account_delegations d WHERE d.id=p_delegation_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'delegation_not_found' USING ERRCODE='P0002'; END IF;
  SELECT owner_user_id INTO v_parent_owner FROM public.commercial_party_accounts WHERE id=v_row.parent_account_id;
  SELECT owner_user_id INTO v_child_owner FROM public.commercial_party_accounts WHERE id=v_row.child_account_id;
  SELECT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=v_row.organization_id AND m.user_id=v_uid
      AND m.state='active' AND m.role IN ('owner','admin','manager')
  ) INTO v_is_manager;
  v_can_read := v_uid=v_parent_owner OR v_is_manager OR (
    v_uid=v_child_owner AND v_row.status='active'
    AND (v_row.expires_at IS NULL OR v_row.expires_at>now())
    AND coalesce((v_row.permissions->>'view_reports')::boolean,false)
  );
  IF NOT v_can_read THEN RAISE EXCEPTION 'delegated_report_permission_required' USING ERRCODE='42501'; END IF;

  SELECT jsonb_build_object(
    'delegation_id',v_row.id,
    'organization_id',v_row.organization_id,
    'status',v_row.status,
    'currency_code',v_row.spend_currency_code,
    'spend_limit_minor',v_row.spend_limit_minor,
    'tracked_spend_minor',v_row.spent_minor,
    'orders',coalesce((
      SELECT jsonb_agg(jsonb_build_object(
        'order_id',o.id,'order_number',o.order_number,'created_at',o.created_at,
        'status',o.status,'payment_status',o.payment_status,
        'total_minor',o.total_minor,'currency_code',o.currency_code
      ) ORDER BY o.created_at DESC)
      FROM public.commercial_customer_orders o
      WHERE o.delegated_access_id=v_row.id
        AND o.principal_account_id=v_row.parent_account_id
        AND o.acting_account_id=v_row.child_account_id
    ),'[]'::jsonb)
  ) INTO v_result;
  RETURN v_result;
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_account_delegated_spend_report(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_account_delegated_spend_report(uuid) TO authenticated;
