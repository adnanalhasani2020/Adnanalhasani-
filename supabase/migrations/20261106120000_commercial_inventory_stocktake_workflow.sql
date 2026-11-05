BEGIN;

-- A stocktake is a controlled document, not a direct balance edit.
CREATE TABLE IF NOT EXISTS public.commercial_inventory_stocktakes (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  activity_id uuid NOT NULL REFERENCES public.commercial_activities(id) ON DELETE RESTRICT,
  location_id uuid NOT NULL REFERENCES public.commercial_organization_locations(id) ON DELETE RESTRICT,
  status text NOT NULL DEFAULT 'draft'
    CHECK (status IN ('draft','submitted','approved','posted','reversed')),
  idempotency_key text NOT NULL CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 200),
  request_fingerprint text NOT NULL,
  notes text NOT NULL DEFAULT '',
  created_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
  submitted_by uuid REFERENCES auth.users(id) ON DELETE RESTRICT,
  approved_by uuid REFERENCES auth.users(id) ON DELETE RESTRICT,
  posted_by uuid REFERENCES auth.users(id) ON DELETE RESTRICT,
  reversed_by uuid REFERENCES auth.users(id) ON DELETE RESTRICT,
  post_idempotency_key text,
  reverse_idempotency_key text,
  reversal_reason text,
  created_at timestamptz NOT NULL DEFAULT now(),
  submitted_at timestamptz,
  approved_at timestamptz,
  posted_at timestamptz,
  reversed_at timestamptz,
  UNIQUE (organization_id,idempotency_key),
  CHECK (approved_by IS NULL OR approved_by <> created_by),
  CHECK (approved_by IS NULL OR submitted_by IS NULL OR approved_by <> submitted_by),
  CHECK (status <> 'posted' OR (approved_by IS NOT NULL AND posted_by IS NOT NULL AND posted_at IS NOT NULL)),
  CHECK (status <> 'reversed' OR (reversed_by IS NOT NULL AND reversed_at IS NOT NULL AND length(btrim(coalesce(reversal_reason,''))) >= 8))
);

CREATE TABLE IF NOT EXISTS public.commercial_inventory_stocktake_lines (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  stocktake_id uuid NOT NULL REFERENCES public.commercial_inventory_stocktakes(id) ON DELETE RESTRICT,
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  location_id uuid NOT NULL REFERENCES public.commercial_organization_locations(id) ON DELETE RESTRICT,
  offering_id uuid NOT NULL REFERENCES public.commercial_offerings(id) ON DELETE RESTRICT,
  expected_quantity numeric(20,6) NOT NULL CHECK (expected_quantity >= 0),
  counted_quantity numeric(20,6) CHECK (counted_quantity IS NULL OR counted_quantity >= 0),
  adjustment_unit_cost_minor bigint CHECK (adjustment_unit_cost_minor IS NULL OR adjustment_unit_cost_minor >= 0),
  previous_unit_cost_minor bigint CHECK (previous_unit_cost_minor IS NULL OR previous_unit_cost_minor >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (stocktake_id,offering_id),
  UNIQUE (id,stocktake_id)
);
CREATE INDEX IF NOT EXISTS commercial_inventory_stocktake_lines_offering_idx
  ON public.commercial_inventory_stocktake_lines(organization_id,location_id,offering_id,stocktake_id);

-- Negative variances consume real cost layers and preserve their exact provenance.
CREATE TABLE IF NOT EXISTS public.commercial_inventory_stocktake_cost_allocations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  location_id uuid NOT NULL REFERENCES public.commercial_organization_locations(id) ON DELETE RESTRICT,
  offering_id uuid NOT NULL REFERENCES public.commercial_offerings(id) ON DELETE RESTRICT,
  stocktake_id uuid NOT NULL REFERENCES public.commercial_inventory_stocktakes(id) ON DELETE RESTRICT,
  stocktake_line_id uuid NOT NULL REFERENCES public.commercial_inventory_stocktake_lines(id) ON DELETE RESTRICT,
  cost_layer_id uuid NOT NULL REFERENCES public.commercial_inventory_cost_layers(id) ON DELETE RESTRICT,
  costing_method text NOT NULL CHECK (costing_method IN ('fifo','lifo','weighted_average')),
  quantity numeric(20,6) NOT NULL CHECK (quantity > 0),
  unit_cost_minor bigint NOT NULL CHECK (unit_cost_minor >= 0),
  total_cost_minor numeric(38,0) NOT NULL CHECK (total_cost_minor >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (stocktake_line_id,cost_layer_id)
);
CREATE INDEX IF NOT EXISTS commercial_inventory_stocktake_cost_allocations_session_idx
  ON public.commercial_inventory_stocktake_cost_allocations(stocktake_id,stocktake_line_id);

ALTER TABLE public.commercial_inventory_stocktakes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_inventory_stocktake_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_inventory_stocktake_cost_allocations ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS commercial_inventory_stocktakes_member_read ON public.commercial_inventory_stocktakes;
CREATE POLICY commercial_inventory_stocktakes_member_read
  ON public.commercial_inventory_stocktakes FOR SELECT TO authenticated
  USING (EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=commercial_inventory_stocktakes.organization_id
      AND m.user_id=(SELECT auth.uid()) AND m.state='active'
  ));
DROP POLICY IF EXISTS commercial_inventory_stocktake_lines_member_read ON public.commercial_inventory_stocktake_lines;
CREATE POLICY commercial_inventory_stocktake_lines_member_read
  ON public.commercial_inventory_stocktake_lines FOR SELECT TO authenticated
  USING (EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=commercial_inventory_stocktake_lines.organization_id
      AND m.user_id=(SELECT auth.uid()) AND m.state='active'
  ));
DROP POLICY IF EXISTS commercial_inventory_stocktake_cost_allocations_member_read ON public.commercial_inventory_stocktake_cost_allocations;
CREATE POLICY commercial_inventory_stocktake_cost_allocations_member_read
  ON public.commercial_inventory_stocktake_cost_allocations FOR SELECT TO authenticated
  USING (EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=commercial_inventory_stocktake_cost_allocations.organization_id
      AND m.user_id=(SELECT auth.uid()) AND m.state='active'
  ));

REVOKE ALL ON public.commercial_inventory_stocktakes,
  public.commercial_inventory_stocktake_lines,
  public.commercial_inventory_stocktake_cost_allocations FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.commercial_inventory_stocktakes,
  public.commercial_inventory_stocktake_lines,
  public.commercial_inventory_stocktake_cost_allocations TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_create_inventory_stocktake(
  p_organization_id uuid,
  p_activity_id uuid,
  p_location_id uuid,
  p_idempotency_key text,
  p_notes text DEFAULT ''
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_fingerprint text;
  v_existing public.commercial_inventory_stocktakes%ROWTYPE;
  v_stocktake public.commercial_inventory_stocktakes%ROWTYPE;
  v_line_count integer;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR p_activity_id IS NULL OR p_location_id IS NULL
     OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200
     OR length(coalesce(p_notes,'')) > 2000 THEN
    RAISE EXCEPTION 'invalid_inventory_stocktake_request' USING ERRCODE='22023';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=p_organization_id AND m.user_id=v_uid
      AND m.state='active' AND m.role IN ('owner','admin','manager')
  ) THEN RAISE EXCEPTION 'inventory_management_permission_required' USING ERRCODE='42501'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_activities a
    JOIN public.commercial_organization_locations l ON l.organization_id=a.organization_id
    WHERE a.id=p_activity_id AND a.organization_id=p_organization_id
      AND l.id=p_location_id AND l.state='active'
  ) THEN RAISE EXCEPTION 'inventory_stocktake_scope_mismatch' USING ERRCODE='22023'; END IF;

  v_fingerprint := md5(jsonb_build_object(
    'organization_id',p_organization_id,'activity_id',p_activity_id,
    'location_id',p_location_id,'notes',coalesce(p_notes,'')
  )::text);
  PERFORM pg_advisory_xact_lock(
    hashtext(p_organization_id::text),hashtext('inventory-stocktake:'||btrim(p_idempotency_key))
  );
  SELECT * INTO v_existing FROM public.commercial_inventory_stocktakes
  WHERE organization_id=p_organization_id AND idempotency_key=btrim(p_idempotency_key)
  FOR UPDATE;
  IF FOUND THEN
    IF v_existing.request_fingerprint IS DISTINCT FROM v_fingerprint THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='23505';
    END IF;
    SELECT count(*)::integer INTO v_line_count
    FROM public.commercial_inventory_stocktake_lines WHERE stocktake_id=v_existing.id;
    RETURN jsonb_build_object('stocktake_id',v_existing.id,'status',v_existing.status,
      'line_count',v_line_count,'idempotent_replay',true);
  END IF;

  INSERT INTO public.commercial_inventory_stocktakes(
    organization_id,activity_id,location_id,idempotency_key,request_fingerprint,notes,created_by
  ) VALUES (
    p_organization_id,p_activity_id,p_location_id,btrim(p_idempotency_key),v_fingerprint,coalesce(p_notes,''),v_uid
  ) RETURNING * INTO v_stocktake;

  INSERT INTO public.commercial_inventory_stocktake_lines(
    stocktake_id,organization_id,location_id,offering_id,expected_quantity
  )
  SELECT v_stocktake.id,b.organization_id,b.location_id,b.offering_id,b.quantity_on_hand
  FROM public.commercial_inventory_balances b
  JOIN public.commercial_offerings o ON o.id=b.offering_id
  WHERE b.organization_id=p_organization_id AND b.location_id=p_location_id
    AND o.activity_id=p_activity_id AND o.location_ref=p_location_id::text
  ORDER BY b.offering_id;
  GET DIAGNOSTICS v_line_count = ROW_COUNT;

  INSERT INTO public.commercial_audit_events(
    organization_id,actor_user_id,action,entity_type,entity_id,metadata
  ) VALUES (
    p_organization_id,v_uid,'inventory.stocktake.created','inventory_stocktake',v_stocktake.id::text,
    jsonb_build_object('activity_id',p_activity_id,'location_id',p_location_id,'line_count',v_line_count,
      'idempotency_key',btrim(p_idempotency_key))
  );
  RETURN jsonb_build_object('stocktake_id',v_stocktake.id,'status','draft',
    'line_count',v_line_count,'idempotent_replay',false);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_set_inventory_stocktake_counts(
  p_stocktake_id uuid,
  p_lines jsonb
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_stocktake public.commercial_inventory_stocktakes%ROWTYPE;
  v_line jsonb;
  v_line_id uuid;
  v_count numeric(20,6);
  v_cost bigint;
  v_updated integer := 0;
  v_expected integer;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_stocktake_id IS NULL OR p_lines IS NULL OR jsonb_typeof(p_lines)<>'array'
     OR jsonb_array_length(p_lines)=0 OR jsonb_array_length(p_lines)>5000 THEN
    RAISE EXCEPTION 'invalid_inventory_stocktake_lines' USING ERRCODE='22023';
  END IF;
  SELECT * INTO v_stocktake FROM public.commercial_inventory_stocktakes
  WHERE id=p_stocktake_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'inventory_stocktake_not_found' USING ERRCODE='P0002'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=v_stocktake.organization_id AND m.user_id=v_uid
      AND m.state='active' AND m.role IN ('owner','admin','manager')
  ) THEN RAISE EXCEPTION 'inventory_management_permission_required' USING ERRCODE='42501'; END IF;
  IF v_stocktake.status<>'draft' THEN RAISE EXCEPTION 'inventory_stocktake_not_editable' USING ERRCODE='55000'; END IF;
  SELECT count(DISTINCT e.value->>'line_id') INTO v_expected
  FROM jsonb_array_elements(p_lines) AS e(value);
  IF v_expected<>jsonb_array_length(p_lines) THEN
    RAISE EXCEPTION 'duplicate_inventory_stocktake_line' USING ERRCODE='22023';
  END IF;

  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines) LOOP
    BEGIN
      v_line_id := (v_line->>'line_id')::uuid;
    EXCEPTION WHEN others THEN
      RAISE EXCEPTION 'invalid_inventory_stocktake_line_id' USING ERRCODE='22023';
    END;
    IF coalesce(v_line->>'counted_quantity','') !~ '^[0-9]{1,14}([.][0-9]{1,6})?$' THEN
      RAISE EXCEPTION 'invalid_inventory_stocktake_counted_quantity' USING ERRCODE='22023';
    END IF;
    v_count := (v_line->>'counted_quantity')::numeric(20,6);
    v_cost := NULL;
    IF nullif(v_line->>'adjustment_unit_cost_minor','') IS NOT NULL THEN
      IF v_line->>'adjustment_unit_cost_minor' !~ '^[0-9]{1,19}$' THEN
        RAISE EXCEPTION 'invalid_inventory_stocktake_adjustment_cost' USING ERRCODE='22023';
      END IF;
      v_cost := (v_line->>'adjustment_unit_cost_minor')::bigint;
    END IF;
    UPDATE public.commercial_inventory_stocktake_lines
    SET counted_quantity=v_count,adjustment_unit_cost_minor=v_cost,updated_at=now()
    WHERE id=v_line_id AND stocktake_id=v_stocktake.id
      AND organization_id=v_stocktake.organization_id AND location_id=v_stocktake.location_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'inventory_stocktake_line_scope_mismatch' USING ERRCODE='22023'; END IF;
    v_updated := v_updated+1;
  END LOOP;
  INSERT INTO public.commercial_audit_events(
    organization_id,actor_user_id,action,entity_type,entity_id,metadata
  ) VALUES (
    v_stocktake.organization_id,v_uid,'inventory.stocktake.counts_saved','inventory_stocktake',
    v_stocktake.id::text,jsonb_build_object('updated_line_count',v_updated)
  );
  RETURN jsonb_build_object('stocktake_id',v_stocktake.id,'updated_line_count',v_updated,'status','draft');
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_submit_inventory_stocktake(p_stocktake_id uuid)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_stocktake public.commercial_inventory_stocktakes%ROWTYPE;
  v_total integer;
  v_counted integer;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  SELECT * INTO v_stocktake FROM public.commercial_inventory_stocktakes
  WHERE id=p_stocktake_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'inventory_stocktake_not_found' USING ERRCODE='P0002'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=v_stocktake.organization_id AND m.user_id=v_uid
      AND m.state='active' AND m.role IN ('owner','admin','manager')
  ) THEN RAISE EXCEPTION 'inventory_management_permission_required' USING ERRCODE='42501'; END IF;
  IF v_stocktake.status<>'draft' THEN RAISE EXCEPTION 'inventory_stocktake_not_submittable' USING ERRCODE='55000'; END IF;
  SELECT count(*)::integer,count(counted_quantity)::integer INTO v_total,v_counted
  FROM public.commercial_inventory_stocktake_lines WHERE stocktake_id=v_stocktake.id;
  IF v_total=0 OR v_counted<>v_total THEN
    RAISE EXCEPTION 'inventory_stocktake_counts_incomplete' USING ERRCODE='55000';
  END IF;
  UPDATE public.commercial_inventory_stocktakes
  SET status='submitted',submitted_by=v_uid,submitted_at=now()
  WHERE id=v_stocktake.id;
  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
  VALUES(v_stocktake.organization_id,v_uid,'inventory.stocktake.submitted','inventory_stocktake',
    v_stocktake.id::text,jsonb_build_object('line_count',v_total));
  RETURN jsonb_build_object('stocktake_id',v_stocktake.id,'status','submitted','line_count',v_total);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_approve_inventory_stocktake(p_stocktake_id uuid)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_stocktake public.commercial_inventory_stocktakes%ROWTYPE;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  SELECT * INTO v_stocktake FROM public.commercial_inventory_stocktakes
  WHERE id=p_stocktake_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'inventory_stocktake_not_found' USING ERRCODE='P0002'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=v_stocktake.organization_id AND m.user_id=v_uid
      AND m.state='active' AND m.role IN ('owner','admin','manager')
  ) THEN RAISE EXCEPTION 'inventory_management_permission_required' USING ERRCODE='42501'; END IF;
  IF v_stocktake.status<>'submitted' THEN RAISE EXCEPTION 'inventory_stocktake_not_approvable' USING ERRCODE='55000'; END IF;
  IF v_stocktake.created_by=v_uid OR v_stocktake.submitted_by=v_uid THEN
    RAISE EXCEPTION 'inventory_stocktake_self_approval_forbidden' USING ERRCODE='42501';
  END IF;
  UPDATE public.commercial_inventory_stocktakes
  SET status='approved',approved_by=v_uid,approved_at=now()
  WHERE id=v_stocktake.id;
  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
  VALUES(v_stocktake.organization_id,v_uid,'inventory.stocktake.approved','inventory_stocktake',v_stocktake.id::text,'{}'::jsonb);
  RETURN jsonb_build_object('stocktake_id',v_stocktake.id,'status','approved');
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_post_inventory_stocktake(
  p_stocktake_id uuid,
  p_idempotency_key text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_stocktake public.commercial_inventory_stocktakes%ROWTYPE;
  v_line public.commercial_inventory_stocktake_lines%ROWTYPE;
  v_balance public.commercial_inventory_balances%ROWTYPE;
  v_layer public.commercial_inventory_cost_layers%ROWTYPE;
  v_method text;
  v_diff numeric;
  v_remaining numeric;
  v_take numeric;
  v_cost bigint;
  v_new_average numeric;
  v_cost_total numeric;
  v_layer_qty numeric;
  v_adjusted integer := 0;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_stocktake_id IS NULL OR p_idempotency_key IS NULL
     OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
    RAISE EXCEPTION 'invalid_inventory_stocktake_post' USING ERRCODE='22023';
  END IF;
  SELECT * INTO v_stocktake FROM public.commercial_inventory_stocktakes
  WHERE id=p_stocktake_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'inventory_stocktake_not_found' USING ERRCODE='P0002'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=v_stocktake.organization_id AND m.user_id=v_uid
      AND m.state='active' AND m.role IN ('owner','admin','manager')
  ) THEN RAISE EXCEPTION 'inventory_management_permission_required' USING ERRCODE='42501'; END IF;
  IF v_stocktake.status='posted' THEN
    IF v_stocktake.post_idempotency_key IS DISTINCT FROM btrim(p_idempotency_key) THEN
      RAISE EXCEPTION 'inventory_stocktake_already_posted_with_other_key' USING ERRCODE='23505';
    END IF;
    RETURN jsonb_build_object('stocktake_id',v_stocktake.id,'status','posted','idempotent_replay',true);
  END IF;
  IF v_stocktake.status<>'approved' THEN RAISE EXCEPTION 'inventory_stocktake_not_approved' USING ERRCODE='55000'; END IF;

  FOR v_line IN
    SELECT * FROM public.commercial_inventory_stocktake_lines
    WHERE stocktake_id=v_stocktake.id ORDER BY offering_id FOR UPDATE
  LOOP
    IF v_line.counted_quantity IS NULL THEN RAISE EXCEPTION 'inventory_stocktake_counts_incomplete' USING ERRCODE='55000'; END IF;
    SELECT * INTO v_balance FROM public.commercial_inventory_balances
    WHERE organization_id=v_stocktake.organization_id AND location_id=v_stocktake.location_id
      AND offering_id=v_line.offering_id FOR UPDATE;
    IF NOT FOUND OR v_balance.quantity_on_hand IS DISTINCT FROM v_line.expected_quantity THEN
      RAISE EXCEPTION 'inventory_stocktake_stale_snapshot' USING ERRCODE='55000',
        DETAIL='Inventory changed after the count snapshot. Create a new stocktake and recount.';
    END IF;
    IF v_line.counted_quantity < coalesce(v_balance.quantity_reserved,0) THEN
      RAISE EXCEPTION 'inventory_stocktake_below_reserved_quantity' USING ERRCODE='55000';
    END IF;
    v_diff := v_line.counted_quantity-v_line.expected_quantity;
    IF v_diff=0 THEN CONTINUE; END IF;
    SELECT coalesce(sum(quantity_remaining),0) INTO v_layer_qty
    FROM public.commercial_inventory_cost_layers
    WHERE organization_id=v_stocktake.organization_id AND location_id=v_stocktake.location_id
      AND offering_id=v_line.offering_id;
    IF v_layer_qty IS DISTINCT FROM v_balance.quantity_on_hand THEN
      RAISE EXCEPTION 'inventory_stocktake_cost_layer_reconciliation_failed' USING ERRCODE='55000',
        DETAIL='Cost-layer quantity does not match the branch balance; the count cannot post safely.';
    END IF;
    UPDATE public.commercial_inventory_stocktake_lines
    SET previous_unit_cost_minor=v_balance.unit_cost_minor,updated_at=now()
    WHERE id=v_line.id;

    SELECT p.costing_method INTO v_method
    FROM public.commercial_inventory_costing_policies p
    WHERE p.organization_id=v_stocktake.organization_id
      AND (p.location_id=v_stocktake.location_id OR p.location_id IS NULL)
    ORDER BY (p.location_id IS NOT NULL) DESC LIMIT 1;
    v_method := coalesce(v_method,'weighted_average');

    IF v_diff>0 THEN
      IF v_line.adjustment_unit_cost_minor IS NULL THEN
        RAISE EXCEPTION 'inventory_stocktake_positive_variance_cost_required' USING ERRCODE='55000';
      END IF;
      IF v_method='weighted_average' THEN
        IF v_balance.quantity_on_hand>0 AND v_balance.unit_cost_minor IS NULL THEN
          RAISE EXCEPTION 'inventory_stocktake_existing_cost_unknown' USING ERRCODE='55000';
        END IF;
        v_new_average := CASE WHEN v_balance.quantity_on_hand=0 THEN v_line.adjustment_unit_cost_minor
          ELSE round((v_balance.quantity_on_hand*v_balance.unit_cost_minor::numeric
            + v_diff*v_line.adjustment_unit_cost_minor::numeric)
            /(v_balance.quantity_on_hand+v_diff)) END;
        IF v_new_average>9223372036854775807 THEN RAISE EXCEPTION 'inventory_cost_overflow' USING ERRCODE='22003'; END IF;
        UPDATE public.commercial_inventory_cost_layers
        SET unit_cost_minor=v_new_average::bigint
        WHERE organization_id=v_stocktake.organization_id AND location_id=v_stocktake.location_id
          AND offering_id=v_line.offering_id AND quantity_remaining>0;
        INSERT INTO public.commercial_inventory_cost_layers(
          organization_id,location_id,offering_id,source_type,source_ref,received_at,
          quantity_received,quantity_remaining,source_unit_cost_minor,unit_cost_minor
        ) VALUES (
          v_stocktake.organization_id,v_stocktake.location_id,v_line.offering_id,'adjustment',
          'stocktake:'||v_stocktake.id::text||':line:'||v_line.id::text,now(),
          v_diff,v_diff,v_line.adjustment_unit_cost_minor,v_new_average::bigint
        );
        UPDATE public.commercial_inventory_balances
        SET quantity_on_hand=v_line.counted_quantity,unit_cost_minor=v_new_average::bigint,updated_at=now()
        WHERE id=v_balance.id;
      ELSE
        INSERT INTO public.commercial_inventory_cost_layers(
          organization_id,location_id,offering_id,source_type,source_ref,received_at,
          quantity_received,quantity_remaining,source_unit_cost_minor,unit_cost_minor
        ) VALUES (
          v_stocktake.organization_id,v_stocktake.location_id,v_line.offering_id,'adjustment',
          'stocktake:'||v_stocktake.id::text||':line:'||v_line.id::text,now(),
          v_diff,v_diff,v_line.adjustment_unit_cost_minor,v_line.adjustment_unit_cost_minor
        );
        UPDATE public.commercial_inventory_balances
        SET quantity_on_hand=v_line.counted_quantity,updated_at=now()
        WHERE id=v_balance.id;
      END IF;
    ELSE
      v_remaining := abs(v_diff);
      v_cost_total := 0;
      FOR v_layer IN
        SELECT * FROM public.commercial_inventory_cost_layers
        WHERE organization_id=v_stocktake.organization_id AND location_id=v_stocktake.location_id
          AND offering_id=v_line.offering_id AND quantity_remaining>0
        ORDER BY
          CASE WHEN v_method='lifo' THEN received_at END DESC,
          CASE WHEN v_method<>'lifo' THEN received_at END ASC,
          CASE WHEN v_method='lifo' THEN id END DESC,
          CASE WHEN v_method<>'lifo' THEN id END ASC
        FOR UPDATE
      LOOP
        EXIT WHEN v_remaining=0;
        v_take := least(v_remaining,v_layer.quantity_remaining);
        v_cost := CASE WHEN v_method='weighted_average' THEN v_balance.unit_cost_minor ELSE v_layer.unit_cost_minor END;
        IF v_cost IS NULL OR v_cost<0 THEN RAISE EXCEPTION 'inventory_stocktake_cost_layer_invalid' USING ERRCODE='55000'; END IF;
        INSERT INTO public.commercial_inventory_stocktake_cost_allocations(
          organization_id,location_id,offering_id,stocktake_id,stocktake_line_id,cost_layer_id,
          costing_method,quantity,unit_cost_minor,total_cost_minor
        ) VALUES (
          v_stocktake.organization_id,v_stocktake.location_id,v_line.offering_id,v_stocktake.id,
          v_line.id,v_layer.id,v_method,v_take,v_cost,round(v_take*v_cost::numeric)
        );
        UPDATE public.commercial_inventory_cost_layers
        SET quantity_remaining=quantity_remaining-v_take WHERE id=v_layer.id;
        v_cost_total := v_cost_total+round(v_take*v_cost::numeric);
        v_remaining := v_remaining-v_take;
      END LOOP;
      IF v_remaining>0 THEN
        RAISE EXCEPTION 'inventory_stocktake_cost_layers_insufficient' USING ERRCODE='55000';
      END IF;
      UPDATE public.commercial_inventory_balances
      SET quantity_on_hand=v_line.counted_quantity,updated_at=now()
      WHERE id=v_balance.id;
    END IF;

    SELECT coalesce(sum(quantity_remaining),0) INTO v_layer_qty
    FROM public.commercial_inventory_cost_layers
    WHERE organization_id=v_stocktake.organization_id AND location_id=v_stocktake.location_id
      AND offering_id=v_line.offering_id;
    IF v_layer_qty IS DISTINCT FROM v_line.counted_quantity THEN
      RAISE EXCEPTION 'inventory_stocktake_posted_cost_layer_mismatch' USING ERRCODE='55000';
    END IF;

    INSERT INTO public.commercial_stock_movements(
      offering_id,movement_type,quantity_delta,source_ref,created_by
    ) VALUES (
      v_line.offering_id,'adjustment',v_diff,
      'stocktake:'||v_stocktake.id::text||':line:'||v_line.id::text,v_uid
    );
    v_adjusted := v_adjusted+1;
  END LOOP;

  UPDATE public.commercial_inventory_stocktakes
  SET status='posted',posted_by=v_uid,posted_at=now(),post_idempotency_key=btrim(p_idempotency_key)
  WHERE id=v_stocktake.id;
  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
  VALUES(v_stocktake.organization_id,v_uid,'inventory.stocktake.posted','inventory_stocktake',v_stocktake.id::text,
    jsonb_build_object('adjusted_line_count',v_adjusted,'idempotency_key',btrim(p_idempotency_key)));
  RETURN jsonb_build_object('stocktake_id',v_stocktake.id,'status','posted',
    'adjusted_line_count',v_adjusted,'idempotent_replay',false);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_reverse_inventory_stocktake(
  p_stocktake_id uuid,
  p_reason text,
  p_idempotency_key text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_stocktake public.commercial_inventory_stocktakes%ROWTYPE;
  v_line public.commercial_inventory_stocktake_lines%ROWTYPE;
  v_balance public.commercial_inventory_balances%ROWTYPE;
  v_layer public.commercial_inventory_cost_layers%ROWTYPE;
  v_allocation public.commercial_inventory_stocktake_cost_allocations%ROWTYPE;
  v_method text;
  v_diff numeric;
  v_new_qty numeric;
  v_new_average numeric;
  v_layer_qty numeric;
  v_layer_value numeric;
  v_reversed integer := 0;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_stocktake_id IS NULL OR length(btrim(coalesce(p_reason,'')))<8
     OR length(p_reason)>1000 OR p_idempotency_key IS NULL
     OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
    RAISE EXCEPTION 'invalid_inventory_stocktake_reversal' USING ERRCODE='22023';
  END IF;
  SELECT * INTO v_stocktake FROM public.commercial_inventory_stocktakes
  WHERE id=p_stocktake_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'inventory_stocktake_not_found' USING ERRCODE='P0002'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=v_stocktake.organization_id AND m.user_id=v_uid
      AND m.state='active' AND m.role IN ('owner','admin','manager')
  ) THEN RAISE EXCEPTION 'inventory_management_permission_required' USING ERRCODE='42501'; END IF;
  IF v_stocktake.status='reversed' THEN
    IF v_stocktake.reverse_idempotency_key IS DISTINCT FROM btrim(p_idempotency_key) THEN
      RAISE EXCEPTION 'inventory_stocktake_already_reversed_with_other_key' USING ERRCODE='23505';
    END IF;
    RETURN jsonb_build_object('stocktake_id',v_stocktake.id,'status','reversed','idempotent_replay',true);
  END IF;
  IF v_stocktake.status<>'posted' THEN RAISE EXCEPTION 'inventory_stocktake_not_posted' USING ERRCODE='55000'; END IF;

  FOR v_line IN
    SELECT * FROM public.commercial_inventory_stocktake_lines
    WHERE stocktake_id=v_stocktake.id ORDER BY offering_id FOR UPDATE
  LOOP
    v_diff := v_line.counted_quantity-v_line.expected_quantity;
    IF v_diff=0 THEN CONTINUE; END IF;
    SELECT * INTO v_balance FROM public.commercial_inventory_balances
    WHERE organization_id=v_stocktake.organization_id AND location_id=v_stocktake.location_id
      AND offering_id=v_line.offering_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'inventory_balance_missing_for_stocktake_reversal' USING ERRCODE='55000'; END IF;
    v_new_qty := v_balance.quantity_on_hand-v_diff;
    IF v_new_qty<coalesce(v_balance.quantity_reserved,0) OR v_new_qty<0 THEN
      RAISE EXCEPTION 'inventory_stocktake_reversal_would_break_reserved_stock' USING ERRCODE='55000';
    END IF;
    IF EXISTS (
      SELECT 1 FROM public.commercial_stock_movements sm
      WHERE sm.offering_id=v_line.offering_id
        AND sm.created_at>v_stocktake.posted_at
    ) THEN
      RAISE EXCEPTION 'inventory_stocktake_reversal_has_later_stock_movements' USING ERRCODE='55000',
        DETAIL='A later stock movement exists for this item. Reconcile it before reversing the stocktake.';
    END IF;

    SELECT p.costing_method INTO v_method
    FROM public.commercial_inventory_costing_policies p
    WHERE p.organization_id=v_stocktake.organization_id
      AND (p.location_id=v_stocktake.location_id OR p.location_id IS NULL)
    ORDER BY (p.location_id IS NOT NULL) DESC LIMIT 1;
    v_method := coalesce(v_method,'weighted_average');

    SELECT coalesce(sum(quantity_remaining),0) INTO v_layer_qty
    FROM public.commercial_inventory_cost_layers
    WHERE organization_id=v_stocktake.organization_id AND location_id=v_stocktake.location_id
      AND offering_id=v_line.offering_id;
    IF v_layer_qty IS DISTINCT FROM v_balance.quantity_on_hand THEN
      RAISE EXCEPTION 'inventory_stocktake_reversal_cost_layer_reconciliation_failed' USING ERRCODE='55000';
    END IF;

    IF v_diff>0 THEN
      SELECT * INTO v_layer FROM public.commercial_inventory_cost_layers
      WHERE organization_id=v_stocktake.organization_id AND location_id=v_stocktake.location_id
        AND offering_id=v_line.offering_id
        AND source_ref='stocktake:'||v_stocktake.id::text||':line:'||v_line.id::text
      FOR UPDATE;
      IF NOT FOUND OR v_layer.quantity_remaining<v_diff THEN
        RAISE EXCEPTION 'inventory_stocktake_reversal_adjustment_layer_consumed' USING ERRCODE='55000',
          DETAIL='The positive stocktake adjustment has already been consumed; reversal would create unsupported negative stock.';
      END IF;
      UPDATE public.commercial_inventory_cost_layers
      SET quantity_remaining=quantity_remaining-v_diff WHERE id=v_layer.id;
    ELSE
      FOR v_allocation IN
        SELECT * FROM public.commercial_inventory_stocktake_cost_allocations
        WHERE stocktake_line_id=v_line.id ORDER BY cost_layer_id FOR UPDATE
      LOOP
        UPDATE public.commercial_inventory_cost_layers
        SET quantity_remaining=quantity_remaining+v_allocation.quantity
        WHERE id=v_allocation.cost_layer_id
          AND quantity_remaining+v_allocation.quantity<=quantity_received;
        IF NOT FOUND THEN RAISE EXCEPTION 'inventory_stocktake_reversal_layer_quantity_invalid' USING ERRCODE='55000'; END IF;
      END LOOP;
    END IF;

    SELECT coalesce(sum(quantity_remaining),0) INTO v_layer_qty
    FROM public.commercial_inventory_cost_layers
    WHERE organization_id=v_stocktake.organization_id AND location_id=v_stocktake.location_id
      AND offering_id=v_line.offering_id;
    IF v_layer_qty IS DISTINCT FROM v_new_qty THEN
      RAISE EXCEPTION 'inventory_stocktake_reversal_cost_layer_reconciliation_failed' USING ERRCODE='55000';
    END IF;

    UPDATE public.commercial_inventory_balances
    SET quantity_on_hand=v_new_qty,updated_at=now()
    WHERE id=v_balance.id;

    IF v_method='weighted_average' THEN
      IF v_diff>0 THEN
        -- The original average is snapshotted before posting. Restore it only
        -- after proving no later movement has consumed/revalued the adjustment.
        UPDATE public.commercial_inventory_cost_layers
        SET unit_cost_minor=v_line.previous_unit_cost_minor
        WHERE organization_id=v_stocktake.organization_id AND location_id=v_stocktake.location_id
          AND offering_id=v_line.offering_id AND quantity_remaining>0;
        UPDATE public.commercial_inventory_balances
        SET unit_cost_minor=v_line.previous_unit_cost_minor WHERE id=v_balance.id;
      ELSE
        SELECT coalesce(sum(quantity_remaining),0),
               coalesce(sum(quantity_remaining*unit_cost_minor::numeric),0)
        INTO v_layer_qty,v_layer_value
        FROM public.commercial_inventory_cost_layers
        WHERE organization_id=v_stocktake.organization_id AND location_id=v_stocktake.location_id
          AND offering_id=v_line.offering_id AND quantity_remaining>0;
        IF v_new_qty>0 AND v_layer_qty<>v_new_qty THEN
          RAISE EXCEPTION 'inventory_stocktake_reversal_cost_layer_reconciliation_failed' USING ERRCODE='55000';
        END IF;
        IF v_layer_qty>0 THEN
          v_new_average:=round(v_layer_value/v_layer_qty);
        ELSE
          v_new_average:=NULL;
        END IF;
        UPDATE public.commercial_inventory_balances
        SET unit_cost_minor=v_new_average::bigint WHERE id=v_balance.id;
        IF v_layer_qty>0 THEN
          UPDATE public.commercial_inventory_cost_layers
          SET unit_cost_minor=v_new_average::bigint
          WHERE organization_id=v_stocktake.organization_id AND location_id=v_stocktake.location_id
            AND offering_id=v_line.offering_id AND quantity_remaining>0;
        END IF;
      END IF;
    END IF;

    INSERT INTO public.commercial_stock_movements(
      offering_id,movement_type,quantity_delta,source_ref,created_by
    ) VALUES (
      v_line.offering_id,'adjustment',-v_diff,
      'stocktake-reversal:'||v_stocktake.id::text||':line:'||v_line.id::text,v_uid
    );
    v_reversed:=v_reversed+1;
  END LOOP;

  UPDATE public.commercial_inventory_stocktakes
  SET status='reversed',reversed_by=v_uid,reversed_at=now(),
      reversal_reason=btrim(p_reason),reverse_idempotency_key=btrim(p_idempotency_key)
  WHERE id=v_stocktake.id;
  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
  VALUES(v_stocktake.organization_id,v_uid,'inventory.stocktake.reversed','inventory_stocktake',v_stocktake.id::text,
    jsonb_build_object('reason',btrim(p_reason),'reversed_line_count',v_reversed,'idempotency_key',btrim(p_idempotency_key)));
  RETURN jsonb_build_object('stocktake_id',v_stocktake.id,'status','reversed',
    'reversed_line_count',v_reversed,'idempotent_replay',false);
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_create_inventory_stocktake(uuid,uuid,uuid,text,text) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_set_inventory_stocktake_counts(uuid,jsonb) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_submit_inventory_stocktake(uuid) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_approve_inventory_stocktake(uuid) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_post_inventory_stocktake(uuid,text) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_reverse_inventory_stocktake(uuid,text,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_create_inventory_stocktake(uuid,uuid,uuid,text,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_set_inventory_stocktake_counts(uuid,jsonb) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_submit_inventory_stocktake(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_approve_inventory_stocktake(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_post_inventory_stocktake(uuid,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_reverse_inventory_stocktake(uuid,text,text) TO authenticated;

COMMENT ON TABLE public.commercial_inventory_stocktakes IS
  'Controlled inventory count documents. Counts are snapshotted, submitted, approved by a different user, posted through atomic stock/cost ledger updates, and reversed only through compensating movements.';
COMMENT ON FUNCTION public.commercial_post_inventory_stocktake(uuid,text) IS
  'Posts an approved stocktake only when every live balance still matches its snapshot; positive variances require an explicit unit cost and negative variances consume verified cost layers.';
COMMENT ON FUNCTION public.commercial_reverse_inventory_stocktake(uuid,text,text) IS
  'Reverses a posted stocktake through compensating stock movements and cost-layer restoration; refuses reversal if a positive adjustment layer has already been consumed.';

COMMIT;
