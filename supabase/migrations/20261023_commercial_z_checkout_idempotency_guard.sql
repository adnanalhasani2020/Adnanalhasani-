-- Preserve idempotent replay before rejecting a newly unavailable offering or tenant.
CREATE OR REPLACE FUNCTION public.commercial_checkout_sale(
  p_offering_id uuid, p_location_id uuid, p_quantity integer, p_idempotency_key text
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path TO 'pg_catalog', 'public', 'commercial_private'
AS $function$
DECLARE
  v_org_id uuid;
BEGIN
  IF auth.uid() IS NULL
     OR p_offering_id IS NULL OR p_location_id IS NULL
     OR p_quantity IS NULL OR p_quantity < 1 OR p_quantity > 100000
     OR p_idempotency_key IS NULL
     OR length(btrim(p_idempotency_key)) < 8 OR length(btrim(p_idempotency_key)) > 200 THEN
    RETURN commercial_private.commercial_checkout_sale(
      p_offering_id, p_location_id, p_quantity, p_idempotency_key
    );
  END IF;

  IF EXISTS (
    SELECT 1 FROM public.commercial_sales s
    WHERE s.user_id = auth.uid() AND s.idempotency_key = p_idempotency_key
  ) THEN
    RETURN commercial_private.commercial_checkout_sale(
      p_offering_id, p_location_id, p_quantity, p_idempotency_key
    );
  END IF;

  SELECT a.organization_id INTO v_org_id
  FROM public.commercial_offerings o
  JOIN public.commercial_activities a ON a.id = o.activity_id
  WHERE o.id = p_offering_id
    AND o.state = 'active'
    AND o.valid_from <= now()
    AND (o.valid_to IS NULL OR o.valid_to > now())
    AND a.state = 'active'
    AND a.deleted_at IS NULL;

  IF NOT FOUND OR v_org_id IS NULL THEN
    RAISE EXCEPTION 'offering_not_available' USING ERRCODE = 'P0002';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organizations o
    WHERE o.id = v_org_id AND o.state = 'active' AND o.deleted_at IS NULL
  ) THEN
    RAISE EXCEPTION 'organization_inactive' USING ERRCODE = '42501';
  END IF;

  RETURN commercial_private.commercial_checkout_sale(
    p_offering_id, p_location_id, p_quantity, p_idempotency_key
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_checkout_sale(uuid, uuid, integer, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_checkout_sale(uuid, uuid, integer, text) TO authenticated;
