BEGIN;

-- Return quantities restore the exact source cost layers consumed by the sale.
-- This is separate from the refund posting: stock restoration and cash refund
-- remain distinct, existing workflows.
CREATE TABLE IF NOT EXISTS public.commercial_inventory_return_cost_allocations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  location_id uuid NOT NULL REFERENCES public.commercial_organization_locations(id) ON DELETE RESTRICT,
  offering_id uuid NOT NULL REFERENCES public.commercial_offerings(id) ON DELETE RESTRICT,
  return_id uuid NOT NULL REFERENCES public.commercial_sales_returns(id) ON DELETE RESTRICT,
  return_item_id uuid NOT NULL REFERENCES public.commercial_sale_return_items(id) ON DELETE RESTRICT,
  sale_id uuid NOT NULL REFERENCES public.commercial_sales(id) ON DELETE RESTRICT,
  sale_item_id uuid NOT NULL REFERENCES public.commercial_sale_items(id) ON DELETE RESTRICT,
  sale_cost_allocation_id uuid NOT NULL REFERENCES public.commercial_inventory_cost_allocations(id) ON DELETE RESTRICT,
  cost_layer_id uuid NOT NULL REFERENCES public.commercial_inventory_cost_layers(id) ON DELETE RESTRICT,
  quantity numeric(20,6) NOT NULL CHECK (quantity > 0),
  unit_cost_minor bigint NOT NULL CHECK (unit_cost_minor >= 0),
  total_cost_minor numeric(30,0) NOT NULL CHECK (total_cost_minor >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (return_item_id,sale_cost_allocation_id)
);
CREATE INDEX IF NOT EXISTS commercial_inventory_return_cost_allocations_sale_idx
  ON public.commercial_inventory_return_cost_allocations(organization_id,sale_id,sale_item_id);
CREATE INDEX IF NOT EXISTS commercial_inventory_return_cost_allocations_layer_idx
  ON public.commercial_inventory_return_cost_allocations(cost_layer_id);

-- A sale cancellation reverses only the quantity that has not already been
-- restored by a completed return. Original sale allocations remain immutable
-- audit evidence; this table records the compensating stock/cost effect.
CREATE TABLE IF NOT EXISTS public.commercial_inventory_cost_reversals (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  location_id uuid NOT NULL REFERENCES public.commercial_organization_locations(id) ON DELETE RESTRICT,
  offering_id uuid NOT NULL REFERENCES public.commercial_offerings(id) ON DELETE RESTRICT,
  sale_id uuid NOT NULL REFERENCES public.commercial_sales(id) ON DELETE RESTRICT,
  sale_item_id uuid NOT NULL REFERENCES public.commercial_sale_items(id) ON DELETE RESTRICT,
  sale_cost_allocation_id uuid NOT NULL REFERENCES public.commercial_inventory_cost_allocations(id) ON DELETE RESTRICT,
  cost_layer_id uuid NOT NULL REFERENCES public.commercial_inventory_cost_layers(id) ON DELETE RESTRICT,
  reversal_type text NOT NULL CHECK (reversal_type IN ('sale_cancel')),
  quantity numeric(20,6) NOT NULL CHECK (quantity > 0),
  unit_cost_minor bigint NOT NULL CHECK (unit_cost_minor >= 0),
  total_cost_minor numeric(30,0) NOT NULL CHECK (total_cost_minor >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (sale_cost_allocation_id,reversal_type)
);
CREATE INDEX IF NOT EXISTS commercial_inventory_cost_reversals_sale_idx
  ON public.commercial_inventory_cost_reversals(organization_id,sale_id,sale_item_id);

ALTER TABLE public.commercial_inventory_return_cost_allocations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_inventory_cost_reversals ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS commercial_inventory_return_cost_allocations_member_read
  ON public.commercial_inventory_return_cost_allocations;
CREATE POLICY commercial_inventory_return_cost_allocations_member_read
  ON public.commercial_inventory_return_cost_allocations
  FOR SELECT TO authenticated
  USING (EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=commercial_inventory_return_cost_allocations.organization_id
      AND m.user_id=(SELECT auth.uid()) AND m.state='active'
  ));

DROP POLICY IF EXISTS commercial_inventory_cost_reversals_member_read
  ON public.commercial_inventory_cost_reversals;
CREATE POLICY commercial_inventory_cost_reversals_member_read
  ON public.commercial_inventory_cost_reversals
  FOR SELECT TO authenticated
  USING (EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=commercial_inventory_cost_reversals.organization_id
      AND m.user_id=(SELECT auth.uid()) AND m.state='active'
  ));

REVOKE ALL ON public.commercial_inventory_return_cost_allocations,
  public.commercial_inventory_cost_reversals FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.commercial_inventory_return_cost_allocations,
  public.commercial_inventory_cost_reversals TO authenticated;

CREATE OR REPLACE FUNCTION commercial_private.commercial_restore_return_cost_layers()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
  v_delta numeric;
  v_return public.commercial_sales_returns%ROWTYPE;
  v_sale_item public.commercial_sale_items%ROWTYPE;
  v_posting public.commercial_return_restock_postings%ROWTYPE;
  v_alloc record;
  v_remaining numeric;
  v_take numeric;
  v_return_cost numeric := 0;
  v_before_value numeric := 0;
  v_before_qty numeric := 0;
  v_layer_qty numeric := 0;
  v_new_average numeric;
  v_method text;
  v_balance public.commercial_inventory_balances%ROWTYPE;
BEGIN
  IF NEW.restocked_quantity IS NOT DISTINCT FROM OLD.restocked_quantity
     OR NEW.restocked_quantity < OLD.restocked_quantity THEN
    RETURN NEW;
  END IF;
  v_delta := NEW.restocked_quantity::numeric-OLD.restocked_quantity::numeric;
  IF v_delta<=0 THEN RETURN NEW; END IF;

  SELECT * INTO v_return
  FROM public.commercial_sales_returns
  WHERE id=NEW.return_id
  FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'return_not_found_for_cost_restoration' USING ERRCODE='55000'; END IF;

  SELECT * INTO v_sale_item
  FROM public.commercial_sale_items
  WHERE id=NEW.sale_item_id
  FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'sale_item_not_found_for_cost_restoration' USING ERRCODE='55000'; END IF;

  SELECT * INTO v_posting
  FROM public.commercial_return_restock_postings
  WHERE return_item_id=NEW.id
  FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'return_restock_posting_missing_for_cost_restoration' USING ERRCODE='55000';
  END IF;
  IF v_posting.return_id<>NEW.return_id
     OR v_posting.organization_id IS DISTINCT FROM v_return.organization_id
     OR v_posting.offering_id IS DISTINCT FROM v_sale_item.offering_id
     OR v_posting.quantity<NEW.restocked_quantity THEN
    RAISE EXCEPTION 'return_restock_posting_scope_mismatch' USING ERRCODE='55000';
  END IF;

  SELECT * INTO v_balance
  FROM public.commercial_inventory_balances b
  WHERE b.organization_id=v_posting.organization_id
    AND b.location_id=v_posting.location_id
    AND b.offering_id=v_posting.offering_id
  FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'return_inventory_balance_missing_for_cost_restoration' USING ERRCODE='55000'; END IF;

  SELECT coalesce(sum(l.quantity_remaining),0),
         coalesce(sum(l.quantity_remaining*l.unit_cost_minor::numeric),0)
    INTO v_before_qty,v_before_value
  FROM public.commercial_inventory_cost_layers l
  WHERE l.organization_id=v_posting.organization_id
    AND l.location_id=v_posting.location_id
    AND l.offering_id=v_posting.offering_id;

  v_remaining:=v_delta;
  FOR v_alloc IN
    SELECT a.*,
      greatest(a.quantity
        - coalesce((SELECT sum(ra.quantity)
                    FROM public.commercial_inventory_return_cost_allocations ra
                    WHERE ra.sale_cost_allocation_id=a.id),0)
        - coalesce((SELECT sum(cr.quantity)
                    FROM public.commercial_inventory_cost_reversals cr
                    WHERE cr.sale_cost_allocation_id=a.id),0),0) AS available_quantity
    FROM public.commercial_inventory_cost_allocations a
    WHERE a.sale_item_id=NEW.sale_item_id
      AND a.organization_id=v_posting.organization_id
      AND a.location_id=v_posting.location_id
      AND a.offering_id=v_posting.offering_id
    ORDER BY a.created_at DESC,a.id DESC
    FOR UPDATE OF a
  LOOP
    EXIT WHEN v_remaining=0;
    IF v_alloc.available_quantity<=0 THEN CONTINUE; END IF;
    v_take:=least(v_remaining,v_alloc.available_quantity);

    INSERT INTO public.commercial_inventory_return_cost_allocations(
      organization_id,location_id,offering_id,return_id,return_item_id,
      sale_id,sale_item_id,sale_cost_allocation_id,cost_layer_id,
      quantity,unit_cost_minor,total_cost_minor
    ) VALUES (
      v_posting.organization_id,v_posting.location_id,v_posting.offering_id,
      v_return.id,NEW.id,v_return.sale_id,NEW.sale_item_id,v_alloc.id,
      v_alloc.cost_layer_id,v_take,v_alloc.unit_cost_minor,
      round(v_take*v_alloc.unit_cost_minor::numeric)
    )
    ON CONFLICT (return_item_id,sale_cost_allocation_id)
    DO UPDATE SET
      quantity=public.commercial_inventory_return_cost_allocations.quantity+EXCLUDED.quantity,
      total_cost_minor=public.commercial_inventory_return_cost_allocations.total_cost_minor+EXCLUDED.total_cost_minor;

    UPDATE public.commercial_inventory_cost_layers
    SET quantity_remaining=quantity_remaining+v_take
    WHERE id=v_alloc.cost_layer_id
      AND organization_id=v_posting.organization_id
      AND location_id=v_posting.location_id
      AND offering_id=v_posting.offering_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'return_cost_layer_scope_mismatch' USING ERRCODE='55000'; END IF;

    v_return_cost:=v_return_cost+round(v_take*v_alloc.unit_cost_minor::numeric);
    v_remaining:=v_remaining-v_take;
    v_method:=v_alloc.costing_method;
  END LOOP;

  IF v_remaining>0 THEN
    RAISE EXCEPTION 'return_cost_allocation_insufficient' USING ERRCODE='55000',
      DETAIL='Returned quantity exceeds the original unreturned cost allocations.';
  END IF;

  SELECT coalesce(sum(l.quantity_remaining),0)
    INTO v_layer_qty
  FROM public.commercial_inventory_cost_layers l
  WHERE l.organization_id=v_posting.organization_id
    AND l.location_id=v_posting.location_id
    AND l.offering_id=v_posting.offering_id;
  IF v_layer_qty IS DISTINCT FROM v_balance.quantity_on_hand THEN
    RAISE EXCEPTION 'return_cost_layer_balance_quantity_mismatch' USING ERRCODE='55000';
  END IF;
  IF v_layer_qty<=0 THEN
    RAISE EXCEPTION 'return_cost_layer_balance_empty' USING ERRCODE='55000';
  END IF;

  IF v_method='weighted_average' THEN
    v_new_average:=round((v_before_value+v_return_cost)/v_layer_qty);
    IF v_new_average>9223372036854775807 THEN
      RAISE EXCEPTION 'return_cost_average_overflow' USING ERRCODE='22003';
    END IF;
    UPDATE public.commercial_inventory_cost_layers
    SET unit_cost_minor=v_new_average::bigint
    WHERE organization_id=v_posting.organization_id
      AND location_id=v_posting.location_id
      AND offering_id=v_posting.offering_id
      AND quantity_remaining>0;
  ELSE
    SELECT round(sum(l.quantity_remaining*l.unit_cost_minor::numeric)/v_layer_qty)
      INTO v_new_average
    FROM public.commercial_inventory_cost_layers l
    WHERE l.organization_id=v_posting.organization_id
      AND l.location_id=v_posting.location_id
      AND l.offering_id=v_posting.offering_id
      AND l.quantity_remaining>0;
  END IF;

  IF v_new_average>9223372036854775807 THEN
    RAISE EXCEPTION 'return_cost_average_overflow' USING ERRCODE='22003';
  END IF;
  UPDATE public.commercial_inventory_balances
  SET unit_cost_minor=v_new_average::bigint,updated_at=now()
  WHERE id=v_balance.id;

  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS commercial_return_restore_cost_layers_trg
  ON public.commercial_sale_return_items;
CREATE TRIGGER commercial_return_restore_cost_layers_trg
AFTER UPDATE OF restocked_quantity ON public.commercial_sale_return_items
FOR EACH ROW
WHEN (NEW.restocked_quantity>OLD.restocked_quantity)
EXECUTE FUNCTION commercial_private.commercial_restore_return_cost_layers();

CREATE OR REPLACE FUNCTION commercial_private.commercial_reverse_sale_cost_layers()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
  v_alloc record;
  v_available numeric;
  v_layer_qty numeric;
  v_balance_qty numeric;
  v_entry public.commercial_journal_entries%ROWTYPE;
  v_currency text;
  v_lines jsonb;
  v_actor uuid;
BEGIN
  IF NEW.status<>'cancelled' OR OLD.status IS NOT DISTINCT FROM NEW.status THEN
    RETURN NEW;
  END IF;

  -- Do not orphan an in-flight return. Resolve or reject it before cancelling
  -- the source sale; completed returns are also guarded by the existing trigger.
  IF EXISTS (
    SELECT 1 FROM public.commercial_sales_returns r
    WHERE r.sale_id=NEW.id AND r.status NOT IN ('rejected')
  ) THEN
    RAISE EXCEPTION 'sale_with_open_return_cannot_be_cancelled_resolve_return_first'
      USING ERRCODE='55000';
  END IF;

  v_actor:=coalesce(auth.uid(),NEW.user_id);

  FOR v_alloc IN
    SELECT a.*,
      greatest(a.quantity
        - coalesce((SELECT sum(ra.quantity)
                    FROM public.commercial_inventory_return_cost_allocations ra
                    WHERE ra.sale_cost_allocation_id=a.id),0)
        - coalesce((SELECT sum(cr.quantity)
                    FROM public.commercial_inventory_cost_reversals cr
                    WHERE cr.sale_cost_allocation_id=a.id),0),0) AS available_quantity
    FROM public.commercial_inventory_cost_allocations a
    WHERE a.sale_id=NEW.id
    ORDER BY a.offering_id,a.sale_item_id,a.created_at DESC,a.id DESC
    FOR UPDATE OF a
  LOOP
    v_available:=v_alloc.available_quantity;
    IF v_available<=0 THEN CONTINUE; END IF;

    INSERT INTO public.commercial_inventory_cost_reversals(
      organization_id,location_id,offering_id,sale_id,sale_item_id,
      sale_cost_allocation_id,cost_layer_id,reversal_type,
      quantity,unit_cost_minor,total_cost_minor
    ) VALUES (
      v_alloc.organization_id,v_alloc.location_id,v_alloc.offering_id,
      NEW.id,v_alloc.sale_item_id,v_alloc.id,v_alloc.cost_layer_id,
      'sale_cancel',v_available,v_alloc.unit_cost_minor,
      round(v_available*v_alloc.unit_cost_minor::numeric)
    );

    UPDATE public.commercial_inventory_cost_layers
    SET quantity_remaining=quantity_remaining+v_available
    WHERE id=v_alloc.cost_layer_id
      AND organization_id=v_alloc.organization_id
      AND location_id=v_alloc.location_id
      AND offering_id=v_alloc.offering_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'cancel_cost_layer_scope_mismatch' USING ERRCODE='55000'; END IF;
  END LOOP;

  -- Existing cancellation logic has already restored the stock balance. Verify
  -- the layer ledger now reconciles to it for every offering with cost history.
  FOR v_alloc IN
    SELECT DISTINCT a.organization_id,a.location_id,a.offering_id
    FROM public.commercial_inventory_cost_allocations a
    WHERE a.sale_id=NEW.id
  LOOP
    SELECT coalesce(sum(l.quantity_remaining),0) INTO v_layer_qty
    FROM public.commercial_inventory_cost_layers l
    WHERE l.organization_id=v_alloc.organization_id
      AND l.location_id=v_alloc.location_id
      AND l.offering_id=v_alloc.offering_id;
    SELECT b.quantity_on_hand INTO v_balance_qty
    FROM public.commercial_inventory_balances b
    WHERE b.organization_id=v_alloc.organization_id
      AND b.location_id=v_alloc.location_id
      AND b.offering_id=v_alloc.offering_id
    FOR UPDATE;
    IF NOT FOUND OR v_layer_qty IS DISTINCT FROM v_balance_qty THEN
      RAISE EXCEPTION 'cancel_cost_layer_balance_quantity_mismatch' USING ERRCODE='55000';
    END IF;
  END LOOP;

  -- The pre-existing cancellation trigger reverses the sale and collection
  -- journals. Reverse our additional COGS/inventory valuation adjustments too.
  FOR v_entry IN
    SELECT e.*
    FROM public.commercial_journal_entries e
    WHERE e.organization_id=NEW.organization_id
      AND e.source_type='sale_cost_adjustment'
      AND e.source_id=NEW.id
      AND e.status='posted'
    ORDER BY e.entry_number DESC
    FOR UPDATE
  LOOP
    SELECT l.currency_code INTO v_currency
    FROM public.commercial_journal_lines l
    WHERE l.journal_entry_id=v_entry.id
    ORDER BY l.line_number
    LIMIT 1;
    SELECT jsonb_agg(jsonb_build_object(
      'ledger_account_id',l.ledger_account_id,
      'debit_minor',l.credit_minor,
      'credit_minor',l.debit_minor,
      'description','Sale cancellation cost adjustment reversal',
      'party_account_id',l.party_account_id
    ) ORDER BY l.line_number)
    INTO v_lines
    FROM public.commercial_journal_lines l
    WHERE l.journal_entry_id=v_entry.id;

    PERFORM commercial_private.commercial_gl_post_entry(
      NEW.organization_id,'reversal:'||v_entry.id::text,'reversal',
      v_entry.id,'Cancellation reversal of inventory costing adjustment '||v_entry.entry_number,
      v_currency,v_lines,v_actor,v_entry.id
    );
    UPDATE public.commercial_journal_entries
    SET status='reversed'
    WHERE id=v_entry.id AND status='posted';
  END LOOP;

  RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION commercial_private.commercial_restore_return_cost_layers() FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION commercial_private.commercial_reverse_sale_cost_layers() FROM PUBLIC,anon,authenticated;

DROP TRIGGER IF EXISTS commercial_sale_cost_layers_cancel_reversal_trg
  ON public.commercial_sales;
CREATE TRIGGER commercial_sale_cost_layers_cancel_reversal_trg
AFTER UPDATE OF status ON public.commercial_sales
FOR EACH ROW
WHEN (NEW.status='cancelled' AND OLD.status IS DISTINCT FROM NEW.status)
EXECUTE FUNCTION commercial_private.commercial_reverse_sale_cost_layers();

COMMIT;
