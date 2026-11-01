BEGIN;

-- Keep branch coordinates editable through one authenticated, audited RPC.
-- Coordinates are optional, but latitude and longitude must be set or cleared together.
CREATE OR REPLACE FUNCTION public.commercial_update_location_coordinates(
  p_organization_id uuid,
  p_location_id uuid,
  p_latitude numeric,
  p_longitude numeric
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog','public'
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_location public.commercial_organization_locations%ROWTYPE;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501';
  END IF;
  IF p_organization_id IS NULL OR p_location_id IS NULL THEN
    RAISE EXCEPTION 'location_scope_required' USING ERRCODE='22023';
  END IF;
  IF (p_latitude IS NULL) <> (p_longitude IS NULL) THEN
    RAISE EXCEPTION 'coordinates_must_be_set_or_cleared_together' USING ERRCODE='22023';
  END IF;
  IF p_latitude IS NOT NULL AND (p_latitude < -90 OR p_latitude > 90) THEN
    RAISE EXCEPTION 'latitude_out_of_range' USING ERRCODE='22023';
  END IF;
  IF p_longitude IS NOT NULL AND (p_longitude < -180 OR p_longitude > 180) THEN
    RAISE EXCEPTION 'longitude_out_of_range' USING ERRCODE='22023';
  END IF;

  SELECT l.* INTO v_location
  FROM public.commercial_organization_locations l
  WHERE l.id=p_location_id
    AND l.organization_id=p_organization_id
    AND l.state='active'
  FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'active_location_not_found_in_organization' USING ERRCODE='P0002';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM public.commercial_organization_members m
    WHERE m.organization_id=p_organization_id
      AND m.user_id=v_uid
      AND m.state='active'
      AND m.role IN ('owner','admin','manager')
  ) THEN
    RAISE EXCEPTION 'location_coordinates_permission_required' USING ERRCODE='42501';
  END IF;

  UPDATE public.commercial_organization_locations
  SET latitude=p_latitude,
      longitude=p_longitude,
      updated_at=now()
  WHERE id=p_location_id
    AND organization_id=p_organization_id;

  INSERT INTO public.commercial_audit_events(
    organization_id,actor_user_id,action,entity_type,entity_id,metadata
  ) VALUES (
    p_organization_id,v_uid,'organization_location.coordinates_updated',
    'organization_location',p_location_id::text,
    jsonb_build_object(
      'latitude',p_latitude,
      'longitude',p_longitude,
      'previously_configured',v_location.latitude IS NOT NULL AND v_location.longitude IS NOT NULL
    )
  );

  RETURN jsonb_build_object(
    'location_id',p_location_id,
    'latitude',p_latitude,
    'longitude',p_longitude,
    'coordinates_configured',p_latitude IS NOT NULL,
    'updated',true
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_update_location_coordinates(uuid,uuid,numeric,numeric)
  FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_update_location_coordinates(uuid,uuid,numeric,numeric)
  TO authenticated;

COMMIT;
