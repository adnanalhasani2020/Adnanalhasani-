BEGIN;

-- Keep the organization catalog's pricing state synchronized with the actual
-- activity-scoped sellable offerings. This does not publish catalog rows or
-- create/change inventory balances, stock movements, or offering prices.
CREATE OR REPLACE FUNCTION public.commercial_catalog_refresh_item_pricing_status(
  p_organization_id uuid,
  p_global_item_id uuid
) RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
  v_has_priced_offering boolean;
BEGIN
  SELECT EXISTS (
    SELECT 1
    FROM public.commercial_catalog_item_offerings cio
    JOIN public.commercial_offerings o
      ON o.id = cio.offering_id
    WHERE cio.organization_id = p_organization_id
      AND cio.global_item_id = p_global_item_id
      AND o.state = 'active'
      AND o.price_minor > 0
  ) INTO v_has_priced_offering;

  UPDATE public.commercial_organization_catalog_items oi
  SET
    pricing_status = CASE WHEN v_has_priced_offering THEN 'configured' ELSE 'unpriced' END,
    metadata = CASE
      WHEN v_has_priced_offering THEN
        COALESCE(oi.metadata, '{}'::jsonb)
          - 'linked_without_pricing'
          - 'pricing_required'
      ELSE
        COALESCE(oi.metadata, '{}'::jsonb)
          || jsonb_build_object('linked_without_pricing', true, 'pricing_required', true)
    END
  WHERE oi.organization_id = p_organization_id
    AND oi.global_item_id = p_global_item_id
    AND oi.pricing_status IN ('unpriced', 'configured');
END;
$$;

CREATE OR REPLACE FUNCTION public.commercial_catalog_sync_pricing_status_from_item_offering()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    PERFORM public.commercial_catalog_refresh_item_pricing_status(
      OLD.organization_id, OLD.global_item_id
    );
    RETURN OLD;
  END IF;

  IF TG_OP = 'UPDATE'
     AND (OLD.organization_id, OLD.global_item_id)
         IS DISTINCT FROM (NEW.organization_id, NEW.global_item_id) THEN
    PERFORM public.commercial_catalog_refresh_item_pricing_status(
      OLD.organization_id, OLD.global_item_id
    );
  END IF;

  PERFORM public.commercial_catalog_refresh_item_pricing_status(
    NEW.organization_id, NEW.global_item_id
  );
  RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.commercial_catalog_sync_pricing_status_from_offering()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
  v_mapping record;
BEGIN
  FOR v_mapping IN
    SELECT cio.organization_id, cio.global_item_id
    FROM public.commercial_catalog_item_offerings cio
    WHERE cio.offering_id = NEW.id
  LOOP
    PERFORM public.commercial_catalog_refresh_item_pricing_status(
      v_mapping.organization_id, v_mapping.global_item_id
    );
  END LOOP;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS commercial_catalog_item_offering_pricing_status
  ON public.commercial_catalog_item_offerings;
CREATE TRIGGER commercial_catalog_item_offering_pricing_status
AFTER INSERT OR UPDATE OR DELETE
ON public.commercial_catalog_item_offerings
FOR EACH ROW
EXECUTE FUNCTION public.commercial_catalog_sync_pricing_status_from_item_offering();

DROP TRIGGER IF EXISTS commercial_offering_catalog_pricing_status
  ON public.commercial_offerings;
CREATE TRIGGER commercial_offering_catalog_pricing_status
AFTER UPDATE OF price_minor, state
ON public.commercial_offerings
FOR EACH ROW
EXECUTE FUNCTION public.commercial_catalog_sync_pricing_status_from_offering();

-- Repair only rows explicitly marked unpriced that already have a real,
-- active, positive-price offering. Keep is_available unchanged.
UPDATE public.commercial_organization_catalog_items oi
SET
  pricing_status = 'configured',
  metadata = COALESCE(oi.metadata, '{}'::jsonb)
    - 'linked_without_pricing'
    - 'pricing_required'
WHERE oi.pricing_status = 'unpriced'
  AND EXISTS (
    SELECT 1
    FROM public.commercial_catalog_item_offerings cio
    JOIN public.commercial_offerings o
      ON o.id = cio.offering_id
    WHERE cio.organization_id = oi.organization_id
      AND cio.global_item_id = oi.global_item_id
      AND o.state = 'active'
      AND o.price_minor > 0
  );

REVOKE ALL ON FUNCTION public.commercial_catalog_refresh_item_pricing_status(uuid, uuid)
  FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.commercial_catalog_sync_pricing_status_from_item_offering()
  FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.commercial_catalog_sync_pricing_status_from_offering()
  FROM PUBLIC, anon, authenticated;

COMMENT ON FUNCTION public.commercial_catalog_refresh_item_pricing_status(uuid, uuid) IS
'Derives catalog pricing_status from linked active positive-price offerings. Does not toggle catalog availability or mutate stock.';

COMMIT;
