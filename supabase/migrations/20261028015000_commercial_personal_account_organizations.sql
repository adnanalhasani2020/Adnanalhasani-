-- Explicitly associate a user's businesses/organizations with their personal platform account.
CREATE TABLE IF NOT EXISTS public.commercial_personal_account_organizations (
  personal_account_id uuid NOT NULL REFERENCES public.commercial_party_accounts(id) ON DELETE RESTRICT,
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  linked_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (personal_account_id, organization_id)
);
CREATE INDEX IF NOT EXISTS commercial_personal_account_organizations_org_idx
  ON public.commercial_personal_account_organizations(organization_id);

ALTER TABLE public.commercial_personal_account_organizations ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.commercial_personal_account_organizations FROM anon, authenticated;

CREATE OR REPLACE FUNCTION public.commercial_account_link_my_organization(
  p_personal_account_id uuid,
  p_organization_id uuid
)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid := auth.uid();
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_party_accounts a
    WHERE a.id=p_personal_account_id AND a.owner_user_id=v_uid
      AND a.account_type='personal' AND a.status='active'
  ) THEN RAISE EXCEPTION 'personal_account_not_owned' USING ERRCODE='42501'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organizations o
    WHERE o.id=p_organization_id AND o.state='active' AND o.deleted_at IS NULL
      AND (
        o.created_by=v_uid OR EXISTS (
          SELECT 1 FROM public.commercial_organization_members m
          WHERE m.organization_id=o.id AND m.user_id=v_uid AND m.state='active' AND m.role='owner'
        )
      )
  ) THEN RAISE EXCEPTION 'organization_not_owned_or_managed' USING ERRCODE='42501'; END IF;
  INSERT INTO public.commercial_personal_account_organizations(personal_account_id,organization_id,linked_by)
  VALUES (p_personal_account_id,p_organization_id,v_uid)
  ON CONFLICT (personal_account_id,organization_id) DO NOTHING;
  RETURN jsonb_build_object('personal_account_id',p_personal_account_id,'organization_id',p_organization_id,'linked',true);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_account_list_my_organizations()
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid := auth.uid(); v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'organization_id',o.id,'name',o.name,'organization_type',o.organization_type,
    'currency_code',o.default_currency_code,'state',o.state,
    'activity_count',(SELECT count(*) FROM public.commercial_activities a WHERE a.organization_id=o.id AND a.deleted_at IS NULL),
    'linked_to_personal_account',EXISTS (
      SELECT 1 FROM public.commercial_personal_account_organizations pao
      JOIN public.commercial_party_accounts pa ON pa.id=pao.personal_account_id
      WHERE pao.organization_id=o.id AND pa.owner_user_id=v_uid AND pa.account_type='personal'
    )
  ) ORDER BY o.name),'[]'::jsonb)
  INTO v_result
  FROM public.commercial_organizations o
  WHERE o.state='active' AND o.deleted_at IS NULL
    AND (o.created_by=v_uid OR EXISTS (
      SELECT 1 FROM public.commercial_organization_members m
      WHERE m.organization_id=o.id AND m.user_id=v_uid AND m.state='active' AND m.role='owner'
    ));
  RETURN v_result;
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_auto_link_new_organization_to_personal_account()
RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
BEGIN
  IF NEW.created_by IS NOT NULL THEN
    INSERT INTO public.commercial_personal_account_organizations(personal_account_id,organization_id,linked_by)
    SELECT a.id,NEW.id,NEW.created_by
    FROM public.commercial_party_accounts a
    WHERE a.owner_user_id=NEW.created_by AND a.account_type='personal' AND a.status='active'
    ORDER BY a.created_at DESC LIMIT 1
    ON CONFLICT (personal_account_id,organization_id) DO NOTHING;
  END IF;
  RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_auto_link_personal_account_to_owned_organizations()
RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
BEGIN
  IF NEW.account_type='personal' AND NEW.owner_user_id IS NOT NULL AND NEW.status='active' THEN
    INSERT INTO public.commercial_personal_account_organizations(personal_account_id,organization_id,linked_by)
    SELECT NEW.id,o.id,NEW.owner_user_id
    FROM public.commercial_organizations o
    WHERE o.state='active' AND o.deleted_at IS NULL
      AND (o.created_by=NEW.owner_user_id OR EXISTS (
        SELECT 1 FROM public.commercial_organization_members m
        WHERE m.organization_id=o.id AND m.user_id=NEW.owner_user_id AND m.state='active' AND m.role='owner'
      ))
    ON CONFLICT (personal_account_id,organization_id) DO NOTHING;
  END IF;
  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS trg_commercial_auto_link_new_organization_to_personal_account ON public.commercial_organizations;
CREATE TRIGGER trg_commercial_auto_link_new_organization_to_personal_account
AFTER INSERT ON public.commercial_organizations
FOR EACH ROW EXECUTE FUNCTION public.commercial_auto_link_new_organization_to_personal_account();

DROP TRIGGER IF EXISTS trg_commercial_auto_link_personal_account_to_owned_organizations ON public.commercial_party_accounts;
CREATE TRIGGER trg_commercial_auto_link_personal_account_to_owned_organizations
AFTER INSERT ON public.commercial_party_accounts
FOR EACH ROW WHEN (NEW.account_type='personal' AND NEW.owner_user_id IS NOT NULL)
EXECUTE FUNCTION public.commercial_auto_link_personal_account_to_owned_organizations();

REVOKE ALL ON FUNCTION public.commercial_account_link_my_organization(uuid,uuid) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_account_list_my_organizations() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_account_link_my_organization(uuid,uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_account_list_my_organizations() TO authenticated;
REVOKE ALL ON FUNCTION public.commercial_auto_link_new_organization_to_personal_account() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.commercial_auto_link_personal_account_to_owned_organizations() FROM PUBLIC, anon, authenticated;
