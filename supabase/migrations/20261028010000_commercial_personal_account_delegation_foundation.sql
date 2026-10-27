-- Personal account delegation foundation: scoped merchant approval, permissions, budgets and audit attribution.
-- Authentication remains Supabase Auth email/password; this migration does not enable Google OAuth.
CREATE TABLE IF NOT EXISTS public.commercial_account_delegations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  parent_account_id uuid NOT NULL REFERENCES public.commercial_party_accounts(id) ON DELETE RESTRICT,
  child_account_id uuid NOT NULL REFERENCES public.commercial_party_accounts(id) ON DELETE RESTRICT,
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  activity_id uuid NULL REFERENCES public.commercial_activities(id) ON DELETE RESTRICT,
  permissions jsonb NOT NULL DEFAULT '{"view_reports":false,"book":false,"purchase":false}'::jsonb,
  expires_at timestamptz NULL,
  spend_limit_minor bigint NULL,
  spend_currency_code text NOT NULL,
  spent_minor bigint NOT NULL DEFAULT 0,
  status text NOT NULL DEFAULT 'pending_merchant_approval'
    CHECK (status IN ('pending_merchant_approval','active','rejected','revoked','expired')),
  merchant_display_name text NULL,
  merchant_icon_key text NULL,
  requested_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
  reviewed_by uuid NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
  reviewed_at timestamptz NULL,
  revoked_by uuid NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
  revoked_at timestamptz NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT commercial_account_delegations_not_self CHECK (parent_account_id <> child_account_id),
  CONSTRAINT commercial_account_delegations_spend_nonnegative CHECK (spent_minor >= 0),
  CONSTRAINT commercial_account_delegations_spend_currency CHECK (spend_currency_code ~ '^[A-Z]{3}
  CONSTRAINT commercial_account_delegations_limit_nonnegative CHECK (spend_limit_minor IS NULL OR spend_limit_minor >= 0),
  CONSTRAINT commercial_account_delegations_permissions_object CHECK (
    jsonb_typeof(permissions) = 'object'
    AND permissions ? 'view_reports'
    AND permissions ? 'book'
    AND permissions ? 'purchase'
    AND jsonb_typeof(permissions->'view_reports') = 'boolean'
    AND jsonb_typeof(permissions->'book') = 'boolean'
    AND jsonb_typeof(permissions->'purchase') = 'boolean'
    AND (permissions - 'view_reports' - 'book' - 'purchase') = '{}'::jsonb
  ),
  CONSTRAINT commercial_account_delegations_expiry_future CHECK (expires_at IS NULL OR expires_at > created_at)
);

CREATE UNIQUE INDEX IF NOT EXISTS commercial_account_delegations_scope_uidx
  ON public.commercial_account_delegations(parent_account_id, child_account_id, organization_id, coalesce(activity_id, '00000000-0000-0000-0000-000000000000'::uuid))
  WHERE status IN ('pending_merchant_approval','active');
CREATE INDEX IF NOT EXISTS commercial_account_delegations_parent_idx
  ON public.commercial_account_delegations(parent_account_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS commercial_account_delegations_child_idx
  ON public.commercial_account_delegations(child_account_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS commercial_account_delegations_organization_idx
  ON public.commercial_account_delegations(organization_id, status, created_at DESC);

ALTER TABLE public.commercial_account_delegations ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.commercial_account_delegations FROM anon, authenticated;

-- Add transaction attribution fields without rewriting historical transactions.
ALTER TABLE public.commercial_customer_orders
  ADD COLUMN IF NOT EXISTS delegated_access_id uuid NULL REFERENCES public.commercial_account_delegations(id) ON DELETE RESTRICT,
  ADD COLUMN IF NOT EXISTS principal_account_id uuid NULL REFERENCES public.commercial_party_accounts(id) ON DELETE RESTRICT,
  ADD COLUMN IF NOT EXISTS acting_account_id uuid NULL REFERENCES public.commercial_party_accounts(id) ON DELETE RESTRICT;
ALTER TABLE public.commercial_bookings
  ADD COLUMN IF NOT EXISTS delegated_access_id uuid NULL REFERENCES public.commercial_account_delegations(id) ON DELETE RESTRICT,
  ADD COLUMN IF NOT EXISTS principal_account_id uuid NULL REFERENCES public.commercial_party_accounts(id) ON DELETE RESTRICT,
  ADD COLUMN IF NOT EXISTS acting_account_id uuid NULL REFERENCES public.commercial_party_accounts(id) ON DELETE RESTRICT;
ALTER TABLE public.commercial_sales
  ADD COLUMN IF NOT EXISTS delegated_access_id uuid NULL REFERENCES public.commercial_account_delegations(id) ON DELETE RESTRICT,
  ADD COLUMN IF NOT EXISTS principal_account_id uuid NULL REFERENCES public.commercial_party_accounts(id) ON DELETE RESTRICT,
  ADD COLUMN IF NOT EXISTS acting_account_id uuid NULL REFERENCES public.commercial_party_accounts(id) ON DELETE RESTRICT;

CREATE OR REPLACE FUNCTION public.commercial_account_lookup_personal_account(p_account_number text)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid := auth.uid(); v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_account_number IS NULL OR length(btrim(p_account_number)) NOT BETWEEN 3 AND 80 THEN
    RAISE EXCEPTION 'invalid_account_number' USING ERRCODE='22023';
  END IF;
  SELECT jsonb_build_object(
    'account_id', a.id,
    'account_number', a.account_number,
    'display_name', a.display_name,
    'account_type', a.account_type
  ) INTO v_result
  FROM public.commercial_party_accounts a
  WHERE upper(a.account_number)=upper(btrim(p_account_number))
    AND a.account_type='personal' AND a.status='active';
  IF v_result IS NULL THEN RAISE EXCEPTION 'personal_account_not_found' USING ERRCODE='P0002'; END IF;
  -- Do not disclose private email, phone, or identity documents during account lookup.
  RETURN v_result;
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_account_create_delegation(
  p_parent_account_id uuid,
  p_child_account_number text,
  p_organization_id uuid,
  p_activity_id uuid DEFAULT NULL,
  p_permissions jsonb DEFAULT '{"view_reports":false,"book":false,"purchase":false}'::jsonb,
  p_expires_at timestamptz DEFAULT NULL,
  p_spend_limit_minor bigint DEFAULT NULL
)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_child_id uuid;
  v_delegation_id uuid;
  v_org_name text;
  v_parent_currency text;
  v_spend_currency text;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_permissions IS NULL OR jsonb_typeof(p_permissions) <> 'object'
     OR NOT (p_permissions ? 'view_reports' AND p_permissions ? 'book' AND p_permissions ? 'purchase')
     OR jsonb_typeof(p_permissions->'view_reports') <> 'boolean'
     OR jsonb_typeof(p_permissions->'book') <> 'boolean'
     OR jsonb_typeof(p_permissions->'purchase') <> 'boolean'
     OR (p_permissions - 'view_reports' - 'book' - 'purchase') <> '{}'::jsonb
     OR NOT ((p_permissions->>'view_reports')::boolean OR (p_permissions->>'book')::boolean OR (p_permissions->>'purchase')::boolean)
  THEN RAISE EXCEPTION 'invalid_delegation_permissions' USING ERRCODE='22023'; END IF;
  IF p_spend_limit_minor IS NOT NULL AND p_spend_limit_minor < 0 THEN
    RAISE EXCEPTION 'invalid_spend_limit' USING ERRCODE='22023';
  END IF;
  IF p_expires_at IS NOT NULL AND p_expires_at <= now() THEN
    RAISE EXCEPTION 'delegation_expiry_must_be_future' USING ERRCODE='22023';
  END IF;
  SELECT a.currency_code INTO v_parent_currency
  FROM public.commercial_party_accounts a
  WHERE a.id=p_parent_account_id AND a.owner_user_id=v_uid
    AND a.account_type='personal' AND a.status='active';
  IF v_parent_currency IS NULL THEN RAISE EXCEPTION 'parent_personal_account_not_owned' USING ERRCODE='42501'; END IF;
  SELECT a.id INTO v_child_id
  FROM public.commercial_party_accounts a
  WHERE upper(a.account_number)=upper(btrim(p_child_account_number))
    AND a.account_type='personal' AND a.status='active'
    AND a.owner_user_id IS NOT NULL AND a.owner_user_id <> v_uid;
  IF v_child_id IS NULL THEN RAISE EXCEPTION 'child_personal_account_not_found_or_invalid' USING ERRCODE='P0002'; END IF;
  SELECT o.name, o.default_currency_code INTO v_org_name, v_spend_currency
  FROM public.commercial_organizations o
  WHERE o.id=p_organization_id AND o.state='active' AND o.deleted_at IS NULL;
  IF v_org_name IS NULL THEN RAISE EXCEPTION 'organization_not_active' USING ERRCODE='22023'; END IF;
  IF p_activity_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM public.commercial_activities a
    WHERE a.id=p_activity_id AND a.organization_id=p_organization_id
      AND a.state='active' AND a.deleted_at IS NULL
  ) THEN RAISE EXCEPTION 'activity_not_in_organization' USING ERRCODE='22023'; END IF;

  INSERT INTO public.commercial_account_delegations(
    parent_account_id, child_account_id, organization_id, activity_id, permissions,
    expires_at, spend_limit_minor, spend_currency_code, requested_by
  ) VALUES (
    p_parent_account_id, v_child_id, p_organization_id, p_activity_id, p_permissions,
    p_expires_at, p_spend_limit_minor, v_spend_currency, v_uid
  ) RETURNING id INTO v_delegation_id;

  INSERT INTO public.commercial_user_notifications(user_id, organization_id, notification_type, title, body, entity_type, entity_id, priority, metadata)
  SELECT m.user_id, p_organization_id, 'delegated_account_approval',
         'طلب ربط حساب فرعي', 'يوجد طلب جديد لربط حساب فرعي يحتاج موافقة إدارة المؤسسة.',
         'account_delegation', v_delegation_id, 'high',
         jsonb_build_object('delegation_id',v_delegation_id,'organization_name',v_org_name)
  FROM public.commercial_organization_members m
  WHERE m.organization_id=p_organization_id AND m.state='active' AND m.role IN ('owner','admin','manager');
  RETURN v_delegation_id;
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_account_review_delegation(
  p_delegation_id uuid,
  p_approve boolean,
  p_merchant_display_name text DEFAULT NULL,
  p_merchant_icon_key text DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_row public.commercial_account_delegations%ROWTYPE;
  v_parent_owner uuid;
  v_child_owner uuid;
  v_parent_email text;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  SELECT d.* INTO v_row
  FROM public.commercial_account_delegations d
  WHERE d.id=p_delegation_id
  FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'delegation_not_found' USING ERRCODE='P0002'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=v_row.organization_id AND m.user_id=v_uid
      AND m.state='active' AND m.role IN ('owner','admin','manager')
  ) THEN RAISE EXCEPTION 'organization_manager_required' USING ERRCODE='42501'; END IF;
  IF v_row.status <> 'pending_merchant_approval' THEN RAISE EXCEPTION 'delegation_already_reviewed' USING ERRCODE='22023'; END IF;
  IF p_approve AND (p_merchant_display_name IS NULL OR length(btrim(p_merchant_display_name)) NOT BETWEEN 1 AND 100) THEN
    RAISE EXCEPTION 'merchant_display_name_required' USING ERRCODE='22023';
  END IF;
  IF p_merchant_icon_key IS NOT NULL AND p_merchant_icon_key !~ '^[a-z0-9_-]{1,48}$' THEN
    RAISE EXCEPTION 'invalid_merchant_icon_key' USING ERRCODE='22023';
  END IF;
  UPDATE public.commercial_account_delegations
  SET status=CASE WHEN p_approve THEN 'active' ELSE 'rejected' END,
      merchant_display_name=CASE WHEN p_approve THEN btrim(p_merchant_display_name) ELSE NULL END,
      merchant_icon_key=CASE WHEN p_approve THEN p_merchant_icon_key ELSE NULL END,
      reviewed_by=v_uid, reviewed_at=now(), updated_at=now()
  WHERE id=p_delegation_id;

  SELECT owner_user_id INTO v_parent_owner FROM public.commercial_party_accounts WHERE id=v_row.parent_account_id;
  SELECT owner_user_id INTO v_child_owner FROM public.commercial_party_accounts WHERE id=v_row.child_account_id;

  -- Link the parent to the merchant's existing customer account only when a verified email maps unambiguously.
  -- This preserves the parent as the merchant receivable owner without guessing across duplicate customer records.
  IF p_approve THEN
    SELECT u.email INTO v_parent_email
    FROM auth.users u
    WHERE u.id=v_parent_owner AND u.email_confirmed_at IS NOT NULL;
    IF v_parent_email IS NOT NULL AND (
      SELECT count(*) FROM public.commercial_customers c
      WHERE c.organization_id=v_row.organization_id AND c.state='active'
        AND c.auth_user_id IS NULL AND c.email IS NOT NULL
        AND lower(btrim(c.email))=lower(btrim(v_parent_email))
        AND EXISTS (
          SELECT 1 FROM public.commercial_customer_party_accounts cpa
          JOIN public.commercial_party_accounts a ON a.id=cpa.party_account_id
            AND a.organization_id=cpa.organization_id
            AND a.account_type IN ('business','organization') AND a.status='active'
          WHERE cpa.customer_id=c.id AND cpa.organization_id=c.organization_id
        )
    ) = 1 THEN
      UPDATE public.commercial_customers c SET auth_user_id=v_parent_owner, updated_at=now()
      WHERE c.organization_id=v_row.organization_id AND c.state='active'
        AND c.auth_user_id IS NULL AND c.email IS NOT NULL
        AND lower(btrim(c.email))=lower(btrim(v_parent_email))
        AND EXISTS (
          SELECT 1 FROM public.commercial_customer_party_accounts cpa
          JOIN public.commercial_party_accounts a ON a.id=cpa.party_account_id
            AND a.organization_id=cpa.organization_id
            AND a.account_type IN ('business','organization') AND a.status='active'
          WHERE cpa.customer_id=c.id AND cpa.organization_id=c.organization_id
        );
    END IF;
  END IF;

  INSERT INTO public.commercial_user_notifications(user_id, organization_id, notification_type, title, body, entity_type, entity_id, priority, metadata)
  VALUES
    (v_parent_owner, v_row.organization_id, 'delegated_account_reviewed',
     CASE WHEN p_approve THEN 'تمت الموافقة على الحساب الفرعي' ELSE 'تم رفض طلب الحساب الفرعي' END,
     CASE WHEN p_approve THEN 'وافقت المؤسسة على ربط الحساب الفرعي.' ELSE 'رفضت المؤسسة طلب ربط الحساب الفرعي.' END,
     'account_delegation', p_delegation_id, 'normal', jsonb_build_object('delegation_id',p_delegation_id,'status',CASE WHEN p_approve THEN 'active' ELSE 'rejected' END)),
    (v_child_owner, v_row.organization_id, 'delegated_account_reviewed',
     CASE WHEN p_approve THEN 'تم اعتمادك كحساب فرعي' ELSE 'تم رفض طلب ربط حسابك' END,
     CASE WHEN p_approve THEN 'اعتمدت المؤسسة ربط حسابك بالحساب الرئيسي ضمن الصلاحيات المحددة.' ELSE 'لم توافق المؤسسة على طلب الربط.' END,
     'account_delegation', p_delegation_id, 'normal', jsonb_build_object('delegation_id',p_delegation_id,'status',CASE WHEN p_approve THEN 'active' ELSE 'rejected' END));
  RETURN jsonb_build_object('delegation_id',p_delegation_id,'status',CASE WHEN p_approve THEN 'active' ELSE 'rejected' END);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_account_update_delegation(
  p_delegation_id uuid,
  p_permissions jsonb,
  p_expires_at timestamptz DEFAULT NULL,
  p_spend_limit_minor bigint DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid := auth.uid(); v_parent_id uuid; v_status text;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_permissions IS NULL OR jsonb_typeof(p_permissions) <> 'object'
     OR NOT (p_permissions ? 'view_reports' AND p_permissions ? 'book' AND p_permissions ? 'purchase')
     OR jsonb_typeof(p_permissions->'view_reports') <> 'boolean'
     OR jsonb_typeof(p_permissions->'book') <> 'boolean'
     OR jsonb_typeof(p_permissions->'purchase') <> 'boolean'
     OR (p_permissions - 'view_reports' - 'book' - 'purchase') <> '{}'::jsonb
     OR (p_spend_limit_minor IS NOT NULL AND p_spend_limit_minor < 0)
     OR (p_expires_at IS NOT NULL AND p_expires_at <= now())
  THEN RAISE EXCEPTION 'invalid_delegation_policy' USING ERRCODE='22023'; END IF;
  SELECT d.parent_account_id,d.status INTO v_parent_id,v_status
  FROM public.commercial_account_delegations d WHERE d.id=p_delegation_id FOR UPDATE;
  IF v_parent_id IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.commercial_party_accounts a
    WHERE a.id=v_parent_id AND a.owner_user_id=v_uid AND a.account_type='personal' AND a.status='active'
  ) THEN RAISE EXCEPTION 'parent_account_required' USING ERRCODE='42501'; END IF;
  IF v_status NOT IN ('pending_merchant_approval','active') THEN RAISE EXCEPTION 'delegation_not_editable' USING ERRCODE='22023'; END IF;
  UPDATE public.commercial_account_delegations
  SET permissions=p_permissions, expires_at=p_expires_at, spend_limit_minor=p_spend_limit_minor,
      status=CASE WHEN v_status='active' THEN 'pending_merchant_approval' ELSE v_status END,
      reviewed_by=NULL, reviewed_at=NULL, updated_at=now()
  WHERE id=p_delegation_id;
  RETURN jsonb_build_object('delegation_id',p_delegation_id,'status','pending_merchant_approval');
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_account_revoke_delegation(p_delegation_id uuid)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid := auth.uid(); v_parent_id uuid;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  SELECT d.parent_account_id INTO v_parent_id FROM public.commercial_account_delegations d WHERE d.id=p_delegation_id FOR UPDATE;
  IF v_parent_id IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.commercial_party_accounts a WHERE a.id=v_parent_id AND a.owner_user_id=v_uid AND a.account_type='personal'
  ) THEN RAISE EXCEPTION 'parent_account_required' USING ERRCODE='42501'; END IF;
  UPDATE public.commercial_account_delegations
  SET status='revoked', revoked_by=v_uid, revoked_at=now(), updated_at=now()
  WHERE id=p_delegation_id AND status IN ('pending_merchant_approval','active');
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_account_list_delegations()
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid := auth.uid(); v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'id',d.id,'parent_account_id',d.parent_account_id,'child_account_id',d.child_account_id,
    'parent_account_number',pa.account_number,'parent_display_name',pa.display_name,
    'child_account_number',ca.account_number,'child_display_name',ca.display_name,
    'organization_id',d.organization_id,'organization_name',o.name,'activity_id',d.activity_id,
    'permissions',d.permissions,'expires_at',d.expires_at,'spend_limit_minor',d.spend_limit_minor,
    'spent_minor',d.spent_minor,'status',d.status,'merchant_display_name',d.merchant_display_name,
    'merchant_icon_key',d.merchant_icon_key,'created_at',d.created_at
  ) ORDER BY d.created_at DESC), '[]'::jsonb) INTO v_result
  FROM public.commercial_account_delegations d
  JOIN public.commercial_party_accounts pa ON pa.id=d.parent_account_id
  JOIN public.commercial_party_accounts ca ON ca.id=d.child_account_id
  JOIN public.commercial_organizations o ON o.id=d.organization_id
  WHERE pa.owner_user_id=v_uid OR ca.owner_user_id=v_uid
     OR EXISTS (SELECT 1 FROM public.commercial_organization_members m
                WHERE m.organization_id=d.organization_id AND m.user_id=v_uid
                  AND m.state='active' AND m.role IN ('owner','admin','manager'));
  RETURN v_result;
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_account_lookup_personal_account(text) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_account_create_delegation(uuid,text,uuid,uuid,jsonb,timestamptz,bigint) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_account_review_delegation(uuid,boolean,text,text) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_account_update_delegation(uuid,jsonb,timestamptz,bigint) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_account_revoke_delegation(uuid) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_account_list_delegations() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_account_lookup_personal_account(text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_account_create_delegation(uuid,text,uuid,uuid,jsonb,timestamptz,bigint) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_account_review_delegation(uuid,boolean,text,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_account_update_delegation(uuid,jsonb,timestamptz,bigint) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_account_revoke_delegation(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_account_list_delegations() TO authenticated;
),
  CONSTRAINT commercial_account_delegations_limit_nonnegative CHECK (spend_limit_minor IS NULL OR spend_limit_minor >= 0),
  CONSTRAINT commercial_account_delegations_permissions_object CHECK (
    jsonb_typeof(permissions) = 'object'
    AND permissions ? 'view_reports'
    AND permissions ? 'book'
    AND permissions ? 'purchase'
    AND jsonb_typeof(permissions->'view_reports') = 'boolean'
    AND jsonb_typeof(permissions->'book') = 'boolean'
    AND jsonb_typeof(permissions->'purchase') = 'boolean'
    AND (permissions - 'view_reports' - 'book' - 'purchase') = '{}'::jsonb
  ),
  CONSTRAINT commercial_account_delegations_expiry_future CHECK (expires_at IS NULL OR expires_at > created_at)
);

CREATE UNIQUE INDEX IF NOT EXISTS commercial_account_delegations_scope_uidx
  ON public.commercial_account_delegations(parent_account_id, child_account_id, organization_id, coalesce(activity_id, '00000000-0000-0000-0000-000000000000'::uuid))
  WHERE status IN ('pending_merchant_approval','active');
CREATE INDEX IF NOT EXISTS commercial_account_delegations_parent_idx
  ON public.commercial_account_delegations(parent_account_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS commercial_account_delegations_child_idx
  ON public.commercial_account_delegations(child_account_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS commercial_account_delegations_organization_idx
  ON public.commercial_account_delegations(organization_id, status, created_at DESC);

ALTER TABLE public.commercial_account_delegations ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.commercial_account_delegations FROM anon, authenticated;

-- Add transaction attribution fields without rewriting historical transactions.
ALTER TABLE public.commercial_customer_orders
  ADD COLUMN IF NOT EXISTS delegated_access_id uuid NULL REFERENCES public.commercial_account_delegations(id) ON DELETE RESTRICT,
  ADD COLUMN IF NOT EXISTS principal_account_id uuid NULL REFERENCES public.commercial_party_accounts(id) ON DELETE RESTRICT,
  ADD COLUMN IF NOT EXISTS acting_account_id uuid NULL REFERENCES public.commercial_party_accounts(id) ON DELETE RESTRICT;
ALTER TABLE public.commercial_bookings
  ADD COLUMN IF NOT EXISTS delegated_access_id uuid NULL REFERENCES public.commercial_account_delegations(id) ON DELETE RESTRICT,
  ADD COLUMN IF NOT EXISTS principal_account_id uuid NULL REFERENCES public.commercial_party_accounts(id) ON DELETE RESTRICT,
  ADD COLUMN IF NOT EXISTS acting_account_id uuid NULL REFERENCES public.commercial_party_accounts(id) ON DELETE RESTRICT;
ALTER TABLE public.commercial_sales
  ADD COLUMN IF NOT EXISTS delegated_access_id uuid NULL REFERENCES public.commercial_account_delegations(id) ON DELETE RESTRICT,
  ADD COLUMN IF NOT EXISTS principal_account_id uuid NULL REFERENCES public.commercial_party_accounts(id) ON DELETE RESTRICT,
  ADD COLUMN IF NOT EXISTS acting_account_id uuid NULL REFERENCES public.commercial_party_accounts(id) ON DELETE RESTRICT;

CREATE OR REPLACE FUNCTION public.commercial_account_lookup_personal_account(p_account_number text)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid := auth.uid(); v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_account_number IS NULL OR length(btrim(p_account_number)) NOT BETWEEN 3 AND 80 THEN
    RAISE EXCEPTION 'invalid_account_number' USING ERRCODE='22023';
  END IF;
  SELECT jsonb_build_object(
    'account_id', a.id,
    'account_number', a.account_number,
    'display_name', a.display_name,
    'account_type', a.account_type
  ) INTO v_result
  FROM public.commercial_party_accounts a
  WHERE upper(a.account_number)=upper(btrim(p_account_number))
    AND a.account_type='personal' AND a.status='active';
  IF v_result IS NULL THEN RAISE EXCEPTION 'personal_account_not_found' USING ERRCODE='P0002'; END IF;
  -- Do not disclose private email, phone, or identity documents during account lookup.
  RETURN v_result;
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_account_create_delegation(
  p_parent_account_id uuid,
  p_child_account_number text,
  p_organization_id uuid,
  p_activity_id uuid DEFAULT NULL,
  p_permissions jsonb DEFAULT '{"view_reports":false,"book":false,"purchase":false}'::jsonb,
  p_expires_at timestamptz DEFAULT NULL,
  p_spend_limit_minor bigint DEFAULT NULL
)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_child_id uuid;
  v_delegation_id uuid;
  v_org_name text;
  v_parent_currency text;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_permissions IS NULL OR jsonb_typeof(p_permissions) <> 'object'
     OR NOT (p_permissions ? 'view_reports' AND p_permissions ? 'book' AND p_permissions ? 'purchase')
     OR jsonb_typeof(p_permissions->'view_reports') <> 'boolean'
     OR jsonb_typeof(p_permissions->'book') <> 'boolean'
     OR jsonb_typeof(p_permissions->'purchase') <> 'boolean'
     OR (p_permissions - 'view_reports' - 'book' - 'purchase') <> '{}'::jsonb
     OR NOT ((p_permissions->>'view_reports')::boolean OR (p_permissions->>'book')::boolean OR (p_permissions->>'purchase')::boolean)
  THEN RAISE EXCEPTION 'invalid_delegation_permissions' USING ERRCODE='22023'; END IF;
  IF p_spend_limit_minor IS NOT NULL AND p_spend_limit_minor < 0 THEN
    RAISE EXCEPTION 'invalid_spend_limit' USING ERRCODE='22023';
  END IF;
  IF p_expires_at IS NOT NULL AND p_expires_at <= now() THEN
    RAISE EXCEPTION 'delegation_expiry_must_be_future' USING ERRCODE='22023';
  END IF;
  SELECT a.currency_code INTO v_parent_currency
  FROM public.commercial_party_accounts a
  WHERE a.id=p_parent_account_id AND a.owner_user_id=v_uid
    AND a.account_type='personal' AND a.status='active';
  IF v_parent_currency IS NULL THEN RAISE EXCEPTION 'parent_personal_account_not_owned' USING ERRCODE='42501'; END IF;
  SELECT a.id INTO v_child_id
  FROM public.commercial_party_accounts a
  WHERE upper(a.account_number)=upper(btrim(p_child_account_number))
    AND a.account_type='personal' AND a.status='active'
    AND a.owner_user_id IS NOT NULL AND a.owner_user_id <> v_uid;
  IF v_child_id IS NULL THEN RAISE EXCEPTION 'child_personal_account_not_found_or_invalid' USING ERRCODE='P0002'; END IF;
  SELECT o.name INTO v_org_name
  FROM public.commercial_organizations o
  WHERE o.id=p_organization_id AND o.state='active' AND o.deleted_at IS NULL;
  IF v_org_name IS NULL THEN RAISE EXCEPTION 'organization_not_active' USING ERRCODE='22023'; END IF;
  IF p_activity_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM public.commercial_activities a
    WHERE a.id=p_activity_id AND a.organization_id=p_organization_id
      AND a.state='active' AND a.deleted_at IS NULL
  ) THEN RAISE EXCEPTION 'activity_not_in_organization' USING ERRCODE='22023'; END IF;

  INSERT INTO public.commercial_account_delegations(
    parent_account_id, child_account_id, organization_id, activity_id, permissions,
    expires_at, spend_limit_minor, requested_by
  ) VALUES (
    p_parent_account_id, v_child_id, p_organization_id, p_activity_id, p_permissions,
    p_expires_at, p_spend_limit_minor, v_uid
  ) RETURNING id INTO v_delegation_id;

  INSERT INTO public.commercial_user_notifications(user_id, organization_id, notification_type, title, body, entity_type, entity_id, priority, metadata)
  SELECT m.user_id, p_organization_id, 'delegated_account_approval',
         'طلب ربط حساب فرعي', 'يوجد طلب جديد لربط حساب فرعي يحتاج موافقة إدارة المؤسسة.',
         'account_delegation', v_delegation_id, 'high',
         jsonb_build_object('delegation_id',v_delegation_id,'organization_name',v_org_name)
  FROM public.commercial_organization_members m
  WHERE m.organization_id=p_organization_id AND m.state='active' AND m.role IN ('owner','admin','manager');
  RETURN v_delegation_id;
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_account_review_delegation(
  p_delegation_id uuid,
  p_approve boolean,
  p_merchant_display_name text DEFAULT NULL,
  p_merchant_icon_key text DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_row public.commercial_account_delegations%ROWTYPE;
  v_parent_owner uuid;
  v_child_owner uuid;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  SELECT d.* INTO v_row
  FROM public.commercial_account_delegations d
  WHERE d.id=p_delegation_id
  FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'delegation_not_found' USING ERRCODE='P0002'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=v_row.organization_id AND m.user_id=v_uid
      AND m.state='active' AND m.role IN ('owner','admin','manager')
  ) THEN RAISE EXCEPTION 'organization_manager_required' USING ERRCODE='42501'; END IF;
  IF v_row.status <> 'pending_merchant_approval' THEN RAISE EXCEPTION 'delegation_already_reviewed' USING ERRCODE='22023'; END IF;
  IF p_approve AND (p_merchant_display_name IS NULL OR length(btrim(p_merchant_display_name)) NOT BETWEEN 1 AND 100) THEN
    RAISE EXCEPTION 'merchant_display_name_required' USING ERRCODE='22023';
  END IF;
  IF p_merchant_icon_key IS NOT NULL AND p_merchant_icon_key !~ '^[a-z0-9_-]{1,48}$' THEN
    RAISE EXCEPTION 'invalid_merchant_icon_key' USING ERRCODE='22023';
  END IF;
  UPDATE public.commercial_account_delegations
  SET status=CASE WHEN p_approve THEN 'active' ELSE 'rejected' END,
      merchant_display_name=CASE WHEN p_approve THEN btrim(p_merchant_display_name) ELSE NULL END,
      merchant_icon_key=CASE WHEN p_approve THEN p_merchant_icon_key ELSE NULL END,
      reviewed_by=v_uid, reviewed_at=now(), updated_at=now()
  WHERE id=p_delegation_id;

  SELECT owner_user_id INTO v_parent_owner FROM public.commercial_party_accounts WHERE id=v_row.parent_account_id;
  SELECT owner_user_id INTO v_child_owner FROM public.commercial_party_accounts WHERE id=v_row.child_account_id;
  INSERT INTO public.commercial_user_notifications(user_id, organization_id, notification_type, title, body, entity_type, entity_id, priority, metadata)
  VALUES
    (v_parent_owner, v_row.organization_id, 'delegated_account_reviewed',
     CASE WHEN p_approve THEN 'تمت الموافقة على الحساب الفرعي' ELSE 'تم رفض طلب الحساب الفرعي' END,
     CASE WHEN p_approve THEN 'وافقت المؤسسة على ربط الحساب الفرعي.' ELSE 'رفضت المؤسسة طلب ربط الحساب الفرعي.' END,
     'account_delegation', p_delegation_id, 'normal', jsonb_build_object('delegation_id',p_delegation_id,'status',CASE WHEN p_approve THEN 'active' ELSE 'rejected' END)),
    (v_child_owner, v_row.organization_id, 'delegated_account_reviewed',
     CASE WHEN p_approve THEN 'تم اعتمادك كحساب فرعي' ELSE 'تم رفض طلب ربط حسابك' END,
     CASE WHEN p_approve THEN 'اعتمدت المؤسسة ربط حسابك بالحساب الرئيسي ضمن الصلاحيات المحددة.' ELSE 'لم توافق المؤسسة على طلب الربط.' END,
     'account_delegation', p_delegation_id, 'normal', jsonb_build_object('delegation_id',p_delegation_id,'status',CASE WHEN p_approve THEN 'active' ELSE 'rejected' END));
  RETURN jsonb_build_object('delegation_id',p_delegation_id,'status',CASE WHEN p_approve THEN 'active' ELSE 'rejected' END);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_account_update_delegation(
  p_delegation_id uuid,
  p_permissions jsonb,
  p_expires_at timestamptz DEFAULT NULL,
  p_spend_limit_minor bigint DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid := auth.uid(); v_parent_id uuid; v_status text;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_permissions IS NULL OR jsonb_typeof(p_permissions) <> 'object'
     OR NOT (p_permissions ? 'view_reports' AND p_permissions ? 'book' AND p_permissions ? 'purchase')
     OR jsonb_typeof(p_permissions->'view_reports') <> 'boolean'
     OR jsonb_typeof(p_permissions->'book') <> 'boolean'
     OR jsonb_typeof(p_permissions->'purchase') <> 'boolean'
     OR (p_permissions - 'view_reports' - 'book' - 'purchase') <> '{}'::jsonb
     OR (p_spend_limit_minor IS NOT NULL AND p_spend_limit_minor < 0)
     OR (p_expires_at IS NOT NULL AND p_expires_at <= now())
  THEN RAISE EXCEPTION 'invalid_delegation_policy' USING ERRCODE='22023'; END IF;
  SELECT d.parent_account_id,d.status INTO v_parent_id,v_status
  FROM public.commercial_account_delegations d WHERE d.id=p_delegation_id FOR UPDATE;
  IF v_parent_id IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.commercial_party_accounts a
    WHERE a.id=v_parent_id AND a.owner_user_id=v_uid AND a.account_type='personal' AND a.status='active'
  ) THEN RAISE EXCEPTION 'parent_account_required' USING ERRCODE='42501'; END IF;
  IF v_status NOT IN ('pending_merchant_approval','active') THEN RAISE EXCEPTION 'delegation_not_editable' USING ERRCODE='22023'; END IF;
  UPDATE public.commercial_account_delegations
  SET permissions=p_permissions, expires_at=p_expires_at, spend_limit_minor=p_spend_limit_minor,
      status=CASE WHEN v_status='active' THEN 'pending_merchant_approval' ELSE v_status END,
      reviewed_by=NULL, reviewed_at=NULL, updated_at=now()
  WHERE id=p_delegation_id;
  RETURN jsonb_build_object('delegation_id',p_delegation_id,'status','pending_merchant_approval');
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_account_revoke_delegation(p_delegation_id uuid)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid := auth.uid(); v_parent_id uuid;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  SELECT d.parent_account_id INTO v_parent_id FROM public.commercial_account_delegations d WHERE d.id=p_delegation_id FOR UPDATE;
  IF v_parent_id IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.commercial_party_accounts a WHERE a.id=v_parent_id AND a.owner_user_id=v_uid AND a.account_type='personal'
  ) THEN RAISE EXCEPTION 'parent_account_required' USING ERRCODE='42501'; END IF;
  UPDATE public.commercial_account_delegations
  SET status='revoked', revoked_by=v_uid, revoked_at=now(), updated_at=now()
  WHERE id=p_delegation_id AND status IN ('pending_merchant_approval','active');
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_account_list_delegations()
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid := auth.uid(); v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'id',d.id,'parent_account_id',d.parent_account_id,'child_account_id',d.child_account_id,
    'parent_account_number',pa.account_number,'parent_display_name',pa.display_name,
    'child_account_number',ca.account_number,'child_display_name',ca.display_name,
    'organization_id',d.organization_id,'organization_name',o.name,'activity_id',d.activity_id,
    'permissions',d.permissions,'expires_at',d.expires_at,'spend_limit_minor',d.spend_limit_minor,
    'spent_minor',d.spent_minor,'status',d.status,'merchant_display_name',d.merchant_display_name,
    'merchant_icon_key',d.merchant_icon_key,'created_at',d.created_at
  ) ORDER BY d.created_at DESC), '[]'::jsonb) INTO v_result
  FROM public.commercial_account_delegations d
  JOIN public.commercial_party_accounts pa ON pa.id=d.parent_account_id
  JOIN public.commercial_party_accounts ca ON ca.id=d.child_account_id
  JOIN public.commercial_organizations o ON o.id=d.organization_id
  WHERE pa.owner_user_id=v_uid OR ca.owner_user_id=v_uid
     OR EXISTS (SELECT 1 FROM public.commercial_organization_members m
                WHERE m.organization_id=d.organization_id AND m.user_id=v_uid
                  AND m.state='active' AND m.role IN ('owner','admin','manager'));
  RETURN v_result;
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_account_lookup_personal_account(text) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_account_create_delegation(uuid,text,uuid,uuid,jsonb,timestamptz,bigint) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_account_review_delegation(uuid,boolean,text,text) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_account_update_delegation(uuid,jsonb,timestamptz,bigint) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_account_revoke_delegation(uuid) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_account_list_delegations() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_account_lookup_personal_account(text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_account_create_delegation(uuid,text,uuid,uuid,jsonb,timestamptz,bigint) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_account_review_delegation(uuid,boolean,text,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_account_update_delegation(uuid,jsonb,timestamptz,bigint) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_account_revoke_delegation(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_account_list_delegations() TO authenticated;
