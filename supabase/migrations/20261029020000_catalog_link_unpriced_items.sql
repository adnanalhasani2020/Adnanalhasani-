-- Link selected global catalog items to an organization without making them sellable.
-- Existing catalog rows/prices are preserved. Newly linked items remain unavailable until explicitly priced.
ALTER TABLE public.commercial_organization_catalog_items
  ADD COLUMN IF NOT EXISTS pricing_status text NOT NULL DEFAULT 'configured';

ALTER TABLE public.commercial_organization_catalog_items
  DROP CONSTRAINT IF EXISTS commercial_organization_catalog_items_pricing_status_check;
ALTER TABLE public.commercial_organization_catalog_items
  ADD CONSTRAINT commercial_organization_catalog_items_pricing_status_check
  CHECK (pricing_status IN ('unpriced','configured','review_required'));

CREATE INDEX IF NOT EXISTS ix_org_catalog_items_pricing_status
  ON public.commercial_organization_catalog_items(organization_id, pricing_status);

CREATE OR REPLACE FUNCTION public.commercial_catalog_link_unpriced_items(
  p_organization_id uuid,
  p_global_item_ids uuid[]
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
  v_requested integer;
  v_valid integer;
  v_added integer := 0;
  v_uid uuid := auth.uid();
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501';
  END IF;
  IF p_organization_id IS NULL OR p_global_item_ids IS NULL
     OR cardinality(p_global_item_ids) NOT BETWEEN 1 AND 100
     OR array_position(p_global_item_ids, NULL) IS NOT NULL THEN
    RAISE EXCEPTION 'invalid_catalog_item_selection' USING ERRCODE='22023';
  END IF;
  IF NOT public.commercial_catalog_member_of(
    p_organization_id, ARRAY['owner','admin','manager']::text[]
  ) THEN
    RAISE EXCEPTION 'catalog_link_forbidden' USING ERRCODE='42501';
  END IF;

  SELECT count(DISTINCT x) INTO v_requested FROM unnest(p_global_item_ids) AS x;
  IF v_requested <> cardinality(p_global_item_ids) THEN
    RAISE EXCEPTION 'duplicate_catalog_item_selection' USING ERRCODE='22023';
  END IF;

  SELECT count(*) INTO v_valid
  FROM public.commercial_global_catalog_items p
  WHERE p.id = ANY(p_global_item_ids)
    AND p.is_active
    AND (p.owner_organization_id IS NULL OR p.owner_organization_id = p_organization_id);
  IF v_valid <> v_requested THEN
    RAISE EXCEPTION 'catalog_item_missing_inactive_or_not_accessible' USING ERRCODE='22023';
  END IF;

  INSERT INTO public.commercial_organization_catalog_items(
    organization_id, global_item_id, merchant_display_name, merchant_description,
    price_minor, currency_code, is_available, metadata, created_by,
    is_enabled, imported_at, pricing_status
  )
  SELECT p_organization_id, p.id, p.name_ar, p.description_ar,
    0, 'YER', false,
    jsonb_build_object('linked_without_pricing', true, 'pricing_required', true, 'source', 'global_catalog'),
    v_uid, true, now(), 'unpriced'
  FROM public.commercial_global_catalog_items p
  WHERE p.id = ANY(p_global_item_ids)
  ON CONFLICT (organization_id, global_item_id) DO NOTHING;

  GET DIAGNOSTICS v_added = ROW_COUNT;

  RETURN jsonb_build_object(
    'organization_id', p_organization_id,
    'requested_count', v_requested,
    'newly_linked_count', v_added,
    'already_linked_count', v_requested - v_added,
    'pricing_status', 'unpriced',
    'is_available', false
  );
END;
$$;

REVOKE ALL ON FUNCTION public.commercial_catalog_link_unpriced_items(uuid,uuid[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_link_unpriced_items(uuid,uuid[]) TO authenticated;

COMMENT ON FUNCTION public.commercial_catalog_link_unpriced_items(uuid,uuid[]) IS
'Idempotently links active global catalog items to an organization without pricing; new rows are unavailable for sale until configured.';
