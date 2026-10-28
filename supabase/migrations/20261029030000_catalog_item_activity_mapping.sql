-- Explicit organization-scoped mapping between catalog items and the selected business activity taxonomy.
-- This is separate from sector/level hints: one item may serve multiple activity specializations.
CREATE TABLE IF NOT EXISTS public.commercial_organization_catalog_item_activities (
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE CASCADE,
  global_item_id uuid NOT NULL REFERENCES public.commercial_global_catalog_items(id) ON DELETE CASCADE,
  activity_node_id uuid NOT NULL REFERENCES public.commercial_global_business_activity_nodes(id) ON DELETE RESTRICT,
  created_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (organization_id, global_item_id, activity_node_id),
  FOREIGN KEY (organization_id, global_item_id)
    REFERENCES public.commercial_organization_catalog_items(organization_id, global_item_id)
    ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_org_catalog_item_activities_activity
  ON public.commercial_organization_catalog_item_activities(organization_id, activity_node_id, global_item_id);

ALTER TABLE public.commercial_organization_catalog_item_activities ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS commercial_org_catalog_item_activities_read ON public.commercial_organization_catalog_item_activities;
CREATE POLICY commercial_org_catalog_item_activities_read
ON public.commercial_organization_catalog_item_activities
FOR SELECT TO authenticated
USING (public.commercial_catalog_member_of(organization_id, ARRAY['owner','admin','manager','staff']::text[]));

CREATE OR REPLACE FUNCTION public.commercial_catalog_assign_items_to_activity(
  p_organization_id uuid,
  p_activity_node_code text,
  p_global_item_ids uuid[]
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
  v_uid uuid := auth.uid();
  v_node_id uuid;
  v_requested integer;
  v_linked integer;
  v_added integer := 0;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501';
  END IF;
  IF p_organization_id IS NULL OR nullif(btrim(p_activity_node_code),'') IS NULL
     OR p_global_item_ids IS NULL OR cardinality(p_global_item_ids) NOT BETWEEN 1 AND 100
     OR array_position(p_global_item_ids, NULL) IS NOT NULL THEN
    RAISE EXCEPTION 'invalid_activity_item_selection' USING ERRCODE='22023';
  END IF;
  IF NOT public.commercial_catalog_member_of(
    p_organization_id, ARRAY['owner','admin','manager']::text[]
  ) THEN
    RAISE EXCEPTION 'catalog_activity_assignment_forbidden' USING ERRCODE='42501';
  END IF;

  SELECT n.id INTO v_node_id
  FROM public.commercial_global_business_activity_nodes n
  WHERE n.code=upper(btrim(p_activity_node_code))
    AND n.status='active' AND n.is_selectable;
  IF v_node_id IS NULL THEN
    RAISE EXCEPTION 'active_selectable_activity_not_found' USING ERRCODE='22023';
  END IF;

  SELECT count(DISTINCT x) INTO v_requested FROM unnest(p_global_item_ids) AS x;
  IF v_requested <> cardinality(p_global_item_ids) THEN
    RAISE EXCEPTION 'duplicate_catalog_item_selection' USING ERRCODE='22023';
  END IF;

  SELECT count(*) INTO v_linked
  FROM public.commercial_organization_catalog_items oi
  WHERE oi.organization_id=p_organization_id
    AND oi.global_item_id=ANY(p_global_item_ids)
    AND oi.is_enabled;
  IF v_linked <> v_requested THEN
    RAISE EXCEPTION 'all_items_must_be_linked_and_enabled_first' USING ERRCODE='22023';
  END IF;

  INSERT INTO public.commercial_organization_catalog_item_activities(
    organization_id, global_item_id, activity_node_id, created_by
  )
  SELECT p_organization_id, x, v_node_id, v_uid
  FROM unnest(p_global_item_ids) AS x
  ON CONFLICT (organization_id, global_item_id, activity_node_id) DO NOTHING;
  GET DIAGNOSTICS v_added = ROW_COUNT;

  RETURN jsonb_build_object(
    'organization_id',p_organization_id,
    'activity_node_code',upper(btrim(p_activity_node_code)),
    'requested_count',v_requested,
    'newly_assigned_count',v_added,
    'already_assigned_count',v_requested-v_added
  );
END;
$$;

REVOKE ALL ON FUNCTION public.commercial_catalog_assign_items_to_activity(uuid,text,uuid[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_assign_items_to_activity(uuid,text,uuid[]) TO authenticated;

COMMENT ON TABLE public.commercial_organization_catalog_item_activities IS
'Explicit organization-specific mapping of linked catalog items to selectable global business activities; supports many-to-many assignment.';
