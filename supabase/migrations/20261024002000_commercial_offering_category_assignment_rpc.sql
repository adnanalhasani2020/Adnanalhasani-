-- Safe, idempotent category assignment for existing cloud offerings.
-- All scope checks and writes happen in one transaction inside this RPC.

CREATE OR REPLACE FUNCTION public.commercial_assign_offering_category(
  p_organization_id uuid,
  p_activity_id uuid,
  p_offering_id uuid,
  p_category_id uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $function$
DECLARE
  v_actor uuid := auth.uid();
  v_category_name text;
BEGIN
  IF v_actor IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE = '42501';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM public.commercial_organization_members m
    WHERE m.organization_id = p_organization_id
      AND m.user_id = v_actor
      AND m.state = 'active'
      AND m.role IN ('owner', 'admin', 'manager')
  ) THEN
    RAISE EXCEPTION 'organization_manager_required' USING ERRCODE = '42501';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM public.commercial_offerings o
    JOIN public.commercial_activities a
      ON a.id = o.activity_id
     AND a.organization_id = p_organization_id
    WHERE o.id = p_offering_id
      AND o.activity_id = p_activity_id
  ) THEN
    RAISE EXCEPTION 'offering_activity_scope_mismatch' USING ERRCODE = '23514';
  END IF;

  SELECT c.name_ar
    INTO v_category_name
  FROM public.commercial_product_categories c
  WHERE c.id = p_category_id
    AND c.organization_id = p_organization_id
    AND c.state = 'active';

  IF v_category_name IS NULL THEN
    RAISE EXCEPTION 'active_category_not_found_in_organization' USING ERRCODE = '23514';
  END IF;

  -- Clear the previous primary category first; the operation remains atomic.
  UPDATE public.commercial_offering_categories
     SET is_primary = false
   WHERE organization_id = p_organization_id
     AND offering_id = p_offering_id
     AND is_primary = true
     AND category_id <> p_category_id;

  INSERT INTO public.commercial_offering_categories
    (organization_id, offering_id, category_id, is_primary, created_by)
  VALUES
    (p_organization_id, p_offering_id, p_category_id, true, v_actor)
  ON CONFLICT (offering_id, category_id)
  DO UPDATE SET is_primary = true;

  INSERT INTO public.commercial_audit_events
    (organization_id, actor_user_id, action, entity_type, entity_id, metadata)
  VALUES
    (p_organization_id, v_actor, 'offering.category_assigned',
     'commercial_offering', p_offering_id::text,
     jsonb_build_object('category_id', p_category_id, 'category_name_ar', v_category_name));

  RETURN jsonb_build_object(
    'ok', true,
    'offering_id', p_offering_id,
    'category_id', p_category_id,
    'category_name_ar', v_category_name
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_assign_offering_category(uuid, uuid, uuid, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_assign_offering_category(uuid, uuid, uuid, uuid) TO authenticated;

COMMENT ON FUNCTION public.commercial_assign_offering_category(uuid, uuid, uuid, uuid)
IS 'Atomically assigns an active organization category to an offering, makes it primary, validates activity and membership scope, and records an audit event.';
