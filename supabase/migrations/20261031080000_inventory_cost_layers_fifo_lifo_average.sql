BEGIN;

-- Per-organization / per-branch valuation policy. No policy row means the
-- existing moving-average balance cost remains the active default.
CREATE TABLE IF NOT EXISTS public.commercial_inventory_costing_policies (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  location_id uuid REFERENCES public.commercial_organization_locations(id) ON DELETE RESTRICT,
  costing_method text NOT NULL DEFAULT 'weighted_average'
    CHECK (costing_method IN ('fifo','lifo','weighted_average')),
  effective_at timestamptz NOT NULL DEFAULT now(),
  created_by uuid REFERENCES auth.users(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS commercial_inventory_costing_policy_org_location_uq
  ON public.commercial_inventory_costing_policies(organization_id,location_id)
  WHERE location_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS commercial_inventory_costing_policy_org_default_uq
  ON public.commercial_inventory_costing_policies(organization_id)
  WHERE location_id IS NULL;

-- Immutable receipt/opening provenance plus a mutable remaining quantity.
-- unit_cost_minor is the current valuation cost (revalued for moving average);
-- source_unit_cost_minor preserves the original receipt/opening cost.
CREATE TABLE IF NOT EXISTS public.commercial_inventory_cost_layers (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  location_id uuid NOT NULL REFERENCES public.commercial_organization_locations(id) ON DELETE RESTRICT,
  offering_id uuid NOT NULL REFERENCES public.commercial_offerings(id) ON DELETE RESTRICT,
  inventory_receipt_id uuid REFERENCES public.commercial_inventory_receipts(id) ON DELETE RESTRICT,
  source_type text NOT NULL CHECK (source_type IN ('opening_balance','purchase_receipt','adjustment')),
  source_ref text NOT NULL CHECK (length(btrim(source_ref)) BETWEEN 1 AND 240),
  received_at timestamptz NOT NULL DEFAULT now(),
  quantity_received numeric(20,6) NOT NULL CHECK (quantity_received > 0),
  quantity_remaining numeric(20,6) NOT NULL CHECK (quantity_remaining >= 0 AND quantity_remaining <= quantity_received),
  source_unit_cost_minor bigint NOT NULL CHECK (source_unit_cost_minor >= 0),
  unit_cost_minor bigint NOT NULL CHECK (unit_cost_minor >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (organization_id,location_id,offering_id,source_ref),
  CHECK ((source_type = 'purchase_receipt') = (inventory_receipt_id IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS commercial_inventory_cost_layers_fifo_idx
  ON public.commercial_inventory_cost_layers(organization_id,location_id,offering_id,received_at,id)
  WHERE quantity_remaining > 0;
CREATE INDEX IF NOT EXISTS commercial_inventory_cost_layers_lifo_idx
  ON public.commercial_inventory_cost_layers(organization_id,location_id,offering_id,received_at DESC,id DESC)
  WHERE quantity_remaining > 0;

CREATE TABLE IF NOT EXISTS public.commercial_inventory_cost_allocations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  location_id uuid NOT NULL REFERENCES public.commercial_organization_locations(id) ON DELETE RESTRICT,
  offering_id uuid NOT NULL REFERENCES public.commercial_offerings(id) ON DELETE RESTRICT,
  sale_id uuid NOT NULL REFERENCES public.commercial_sales(id) ON DELETE RESTRICT,
  sale_item_id uuid NOT NULL REFERENCES public.commercial_sale_items(id) ON DELETE RESTRICT,
  cost_layer_id uuid NOT NULL REFERENCES public.commercial_inventory_cost_layers(id) ON DELETE RESTRICT,
  costing_method text NOT NULL CHECK (costing_method IN ('fifo','lifo','weighted_average')),
  quantity numeric(20,6) NOT NULL CHECK (quantity > 0),
  unit_cost_minor bigint NOT NULL CHECK (unit_cost_minor >= 0),
  total_cost_minor numeric(30,0) NOT NULL CHECK (total_cost_minor >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (sale_item_id,cost_layer_id)
);
CREATE INDEX IF NOT EXISTS commercial_inventory_cost_allocations_sale_idx
  ON public.commercial_inventory_cost_allocations(organization_id,sale_id,sale_item_id);
CREATE INDEX IF NOT EXISTS commercial_inventory_cost_allocations_layer_idx
  ON public.commercial_inventory_cost_allocations(cost_layer_id);

-- Prevent a manager from attaching a costing policy to another organization's branch.
CREATE OR REPLACE FUNCTION commercial_private.commercial_validate_inventory_costing_policy_scope()
RETURNS trigger
LANGUAGE plpgsql
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
BEGIN
  IF NEW.location_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_locations l
    WHERE l.id=NEW.location_id AND l.organization_id=NEW.organization_id
  ) THEN
    RAISE EXCEPTION 'inventory_costing_policy_location_scope_mismatch' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$function$;
DROP TRIGGER IF EXISTS commercial_inventory_costing_policy_scope_trg
  ON public.commercial_inventory_costing_policies;
CREATE TRIGGER commercial_inventory_costing_policy_scope_trg
BEFORE INSERT OR UPDATE OF organization_id,location_id
ON public.commercial_inventory_costing_policies
FOR EACH ROW EXECUTE FUNCTION commercial_private.commercial_validate_inventory_costing_policy_scope();

-- Seed current positive stock as a single opening layer only where a known
-- balance cost exists. This preserves current valuation without inventing
-- historical receipt prices or double-counting old receipts.
INSERT INTO public.commercial_inventory_cost_layers(
  organization_id,location_id,offering_id,source_type,source_ref,received_at,
  quantity_received,quantity_remaining,source_unit_cost_minor,unit_cost_minor
)
SELECT b.organization_id,b.location_id,b.offering_id,'opening_balance',
       'opening-balance:'||b.id::text,coalesce(b.updated_at,now()),
       b.quantity_on_hand,b.quantity_on_hand,b.unit_cost_minor,b.unit_cost_minor
FROM public.commercial_inventory_balances b
WHERE b.quantity_on_hand > 0
  AND b.unit_cost_minor IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM public.commercial_inventory_cost_layers l
    WHERE l.organization_id=b.organization_id
      AND l.location_id=b.location_id AND l.offering_id=b.offering_id
  )
ON CONFLICT (organization_id,location_id,offering_id,source_ref) DO NOTHING;

-- New receipts become layers atomically in the same transaction as receipt
-- posting. For weighted average, all remaining layers are revalued to the
-- newly calculated moving average; FIFO/LIFO retain each receipt's actual cost.
CREATE OR REPLACE FUNCTION commercial_private.commercial_inventory_receipt_cost_layer()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
  v_balance public.commercial_inventory_balances%ROWTYPE;
  v_method text := 'weighted_average';
  v_new_average numeric;
BEGIN
  SELECT * INTO v_balance
  FROM public.commercial_inventory_balances
  WHERE organization_id=NEW.organization_id
    AND location_id=NEW.location_id
    AND offering_id=NEW.offering_id
  FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'inventory_cost_layer_balance_missing' USING ERRCODE='P0002';
  END IF;

  SELECT p.costing_method INTO v_method
  FROM public.commercial_inventory_costing_policies p
  WHERE p.organization_id=NEW.organization_id
    AND (p.location_id=NEW.location_id OR p.location_id IS NULL)
  ORDER BY (p.location_id IS NOT NULL) DESC
  LIMIT 1;
  v_method := coalesce(v_method,'weighted_average');

  IF v_method='weighted_average' THEN
    IF v_balance.quantity_on_hand > 0 AND v_balance.unit_cost_minor IS NULL THEN
      RAISE EXCEPTION 'inventory_cost_unknown_for_weighted_average' USING ERRCODE='55000';
    END IF;
    v_new_average := CASE
      WHEN v_balance.quantity_on_hand=0 THEN NEW.unit_cost_minor
      ELSE round(
        (v_balance.quantity_on_hand::numeric*v_balance.unit_cost_minor::numeric
         + NEW.quantity::numeric*NEW.unit_cost_minor::numeric)
        /(v_balance.quantity_on_hand::numeric+NEW.quantity::numeric)
      )
    END;
    IF v_new_average > 9223372036854775807 THEN
      RAISE EXCEPTION 'inventory_cost_overflow' USING ERRCODE='22003';
    END IF;
    UPDATE public.commercial_inventory_cost_layers
    SET unit_cost_minor=v_new_average::bigint
    WHERE organization_id=NEW.organization_id
      AND location_id=NEW.location_id AND offering_id=NEW.offering_id
      AND quantity_remaining>0;
  ELSE
    v_new_average := NEW.unit_cost_minor;
  END IF;

  INSERT INTO public.commercial_inventory_cost_layers(
    organization_id,location_id,offering_id,inventory_receipt_id,
    source_type,source_ref,received_at,quantity_received,quantity_remaining,
    source_unit_cost_minor,unit_cost_minor
  ) VALUES (
    NEW.organization_id,NEW.location_id,NEW.offering_id,NEW.id,
    'purchase_receipt','receipt:'||NEW.id::text,now(),NEW.quantity,NEW.quantity,
    NEW.unit_cost_minor,v_new_average::bigint
  )
  ON CONFLICT (organization_id,location_id,offering_id,source_ref) DO NOTHING;

  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS commercial_inventory_receipt_cost_layer_trg
  ON public.commercial_inventory_receipts;
CREATE TRIGGER commercial_inventory_receipt_cost_layer_trg
AFTER INSERT ON public.commercial_inventory_receipts
FOR EACH ROW EXECUTE FUNCTION commercial_private.commercial_inventory_receipt_cost_layer();

-- Allocate a sale line against its actual remaining cost layers. The checkout
-- transaction must call this after inserting the sale line and before reducing
-- the balance. It returns the exact extended cost and persists the allocation.
CREATE OR REPLACE FUNCTION commercial_private.commercial_allocate_sale_item_cost(
  p_sale_item_id uuid,
  p_location_id uuid
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
  v_item public.commercial_sale_items%ROWTYPE;
  v_org uuid;
  v_sale_user uuid;
  v_method text := 'weighted_average';
  v_balance public.commercial_inventory_balances%ROWTYPE;
  v_layer public.commercial_inventory_cost_layers%ROWTYPE;
  v_quantity numeric;
  v_remaining numeric;
  v_take numeric;
  v_total_cost numeric := 0;
  v_unit_cost bigint;
  v_layer_cost bigint;
  v_existing_qty numeric;
  v_existing_cost numeric;
BEGIN
  IF auth.uid() IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501';
  END IF;
  IF p_sale_item_id IS NULL OR p_location_id IS NULL THEN
    RAISE EXCEPTION 'invalid_inventory_cost_allocation_request' USING ERRCODE='22023';
  END IF;

  SELECT i.* INTO v_item
  FROM public.commercial_sale_items i
  WHERE i.id=p_sale_item_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'sale_item_not_found' USING ERRCODE='P0002';
  END IF;
  SELECT s.organization_id,s.user_id INTO v_org,v_sale_user
  FROM public.commercial_sales s WHERE s.id=v_item.sale_id;
  IF NOT FOUND OR v_sale_user IS DISTINCT FROM auth.uid() THEN
    RAISE EXCEPTION 'sale_item_not_owned_by_authenticated_user' USING ERRCODE='42501';
  END IF;

  v_quantity := coalesce(v_item.quantity_in_stock_units::numeric,v_item.quantity::numeric);
  IF v_quantity IS NULL OR v_quantity<=0 THEN
    RAISE EXCEPTION 'invalid_sale_item_stock_quantity' USING ERRCODE='22023';
  END IF;

  SELECT p.costing_method INTO v_method
  FROM public.commercial_inventory_costing_policies p
  WHERE p.organization_id=v_org
    AND (p.location_id=p_location_id OR p.location_id IS NULL)
  ORDER BY (p.location_id IS NOT NULL) DESC
  LIMIT 1;
  v_method := coalesce(v_method,'weighted_average');

  SELECT * INTO v_balance
  FROM public.commercial_inventory_balances b
  WHERE b.organization_id=v_org AND b.location_id=p_location_id
    AND b.offering_id=v_item.offering_id
  FOR UPDATE;
  IF NOT FOUND OR v_balance.quantity_on_hand < v_quantity THEN
    RAISE EXCEPTION 'inventory_balance_insufficient_for_cost_allocation' USING ERRCODE='55000';
  END IF;

  SELECT coalesce(sum(a.quantity),0),coalesce(sum(a.total_cost_minor),0)
    INTO v_existing_qty,v_existing_cost
  FROM public.commercial_inventory_cost_allocations a
  WHERE a.sale_item_id=p_sale_item_id;
  IF v_existing_qty>0 THEN
    IF v_existing_qty<>v_quantity THEN
      RAISE EXCEPTION 'sale_item_cost_allocation_quantity_mismatch' USING ERRCODE='55000';
    END IF;
    v_unit_cost := round(v_existing_cost/v_existing_qty)::bigint;
    UPDATE public.commercial_sale_items
    SET unit_cost_minor=v_unit_cost,cost_total_minor=v_existing_cost::bigint
    WHERE id=p_sale_item_id;
    RETURN jsonb_build_object('sale_item_id',p_sale_item_id,'costing_method',v_method,
      'quantity',v_existing_qty,'cost_total_minor',v_existing_cost::bigint,
      'unit_cost_minor',v_unit_cost,'idempotent_replay',true);
  END IF;

  IF v_method='weighted_average' AND v_balance.unit_cost_minor IS NULL THEN
    RAISE EXCEPTION 'inventory_average_cost_unknown' USING ERRCODE='55000';
  END IF;

  v_remaining := v_quantity;
  FOR v_layer IN
    SELECT l.*
    FROM public.commercial_inventory_cost_layers l
    WHERE l.organization_id=v_org AND l.location_id=p_location_id
      AND l.offering_id=v_item.offering_id AND l.quantity_remaining>0
    ORDER BY
      CASE WHEN v_method='lifo' THEN l.received_at END DESC,
      CASE WHEN v_method<>'lifo' THEN l.received_at END ASC,
      CASE WHEN v_method='lifo' THEN l.id END DESC,
      CASE WHEN v_method<>'lifo' THEN l.id END ASC
    FOR UPDATE
  LOOP
    EXIT WHEN v_remaining=0;
    v_take := least(v_remaining,v_layer.quantity_remaining);
    v_layer_cost := CASE WHEN v_method='weighted_average' THEN v_balance.unit_cost_minor ELSE v_layer.unit_cost_minor END;
    INSERT INTO public.commercial_inventory_cost_allocations(
      organization_id,location_id,offering_id,sale_id,sale_item_id,cost_layer_id,
      costing_method,quantity,unit_cost_minor,total_cost_minor
    ) VALUES (
      v_org,p_location_id,v_item.offering_id,v_item.sale_id,p_sale_item_id,
      v_layer.id,v_method,v_take,v_layer_cost,
      round(v_take*v_layer_cost::numeric)
    );
    UPDATE public.commercial_inventory_cost_layers
    SET quantity_remaining=quantity_remaining-v_take
    WHERE id=v_layer.id;
    v_total_cost := v_total_cost+round(v_take*v_layer_cost::numeric);
    v_remaining := v_remaining-v_take;
  END LOOP;

  IF v_remaining>0 THEN
    RAISE EXCEPTION 'inventory_cost_layers_insufficient' USING ERRCODE='55000',
      DETAIL='The sale cannot use the layer engine until opening stock has a verified cost layer.';
  END IF;
  IF v_total_cost>9223372036854775807 THEN
    RAISE EXCEPTION 'inventory_cost_overflow' USING ERRCODE='22003';
  END IF;
  v_unit_cost := round(v_total_cost/v_quantity)::bigint;
  UPDATE public.commercial_sale_items
  SET unit_cost_minor=v_unit_cost,cost_total_minor=v_total_cost::bigint
  WHERE id=p_sale_item_id;

  RETURN jsonb_build_object('sale_item_id',p_sale_item_id,'costing_method',v_method,
    'quantity',v_quantity,'cost_total_minor',v_total_cost::bigint,
    'unit_cost_minor',v_unit_cost,'idempotent_replay',false);
END;
$function$;

REVOKE ALL ON FUNCTION commercial_private.commercial_inventory_receipt_cost_layer() FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION commercial_private.commercial_allocate_sale_item_cost(uuid,uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION commercial_private.commercial_inventory_receipt_cost_layer() TO postgres,service_role;
GRANT EXECUTE ON FUNCTION commercial_private.commercial_allocate_sale_item_cost(uuid,uuid) TO postgres,service_role;

ALTER TABLE public.commercial_inventory_costing_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_inventory_cost_layers ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_inventory_cost_allocations ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS commercial_inventory_costing_policies_member_read
  ON public.commercial_inventory_costing_policies;
CREATE POLICY commercial_inventory_costing_policies_member_read
  ON public.commercial_inventory_costing_policies FOR SELECT TO authenticated
  USING (EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=commercial_inventory_costing_policies.organization_id
      AND m.user_id=(SELECT auth.uid()) AND m.state='active'
  ));
DROP POLICY IF EXISTS commercial_inventory_costing_policies_manager_write
  ON public.commercial_inventory_costing_policies;
CREATE POLICY commercial_inventory_costing_policies_manager_write
  ON public.commercial_inventory_costing_policies FOR ALL TO authenticated
  USING (EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=commercial_inventory_costing_policies.organization_id
      AND m.user_id=(SELECT auth.uid()) AND m.state='active'
      AND m.role IN ('owner','admin','manager')
  ))
  WITH CHECK (EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=commercial_inventory_costing_policies.organization_id
      AND m.user_id=(SELECT auth.uid()) AND m.state='active'
      AND m.role IN ('owner','admin','manager')
  ));

DROP POLICY IF EXISTS commercial_inventory_cost_layers_member_read
  ON public.commercial_inventory_cost_layers;
CREATE POLICY commercial_inventory_cost_layers_member_read
  ON public.commercial_inventory_cost_layers FOR SELECT TO authenticated
  USING (EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=commercial_inventory_cost_layers.organization_id
      AND m.user_id=(SELECT auth.uid()) AND m.state='active'
  ));
DROP POLICY IF EXISTS commercial_inventory_cost_allocations_member_read
  ON public.commercial_inventory_cost_allocations;
CREATE POLICY commercial_inventory_cost_allocations_member_read
  ON public.commercial_inventory_cost_allocations FOR SELECT TO authenticated
  USING (EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=commercial_inventory_cost_allocations.organization_id
      AND m.user_id=(SELECT auth.uid()) AND m.state='active'
  ));

REVOKE ALL ON public.commercial_inventory_costing_policies,
  public.commercial_inventory_cost_layers,
  public.commercial_inventory_cost_allocations FROM anon;
GRANT SELECT,INSERT,UPDATE ON public.commercial_inventory_costing_policies TO authenticated;
GRANT SELECT ON public.commercial_inventory_cost_layers,
  public.commercial_inventory_cost_allocations TO authenticated;

COMMIT;
