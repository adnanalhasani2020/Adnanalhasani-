BEGIN;

-- A sale item can consume more than one physical layer. created_at defaults to
-- now(), which is transaction-stable, and UUID order is random; neither records
-- the actual order in which layers were consumed. Keep a per-item sequence for
-- new allocations. Historical rows remain NULL because their true order cannot
-- be reconstructed safely.
ALTER TABLE public.commercial_inventory_cost_allocations
  ADD COLUMN IF NOT EXISTS allocation_sequence bigint;

CREATE UNIQUE INDEX IF NOT EXISTS commercial_inventory_cost_allocations_item_sequence_uidx
  ON public.commercial_inventory_cost_allocations(sale_item_id,allocation_sequence)
  WHERE allocation_sequence IS NOT NULL;

CREATE OR REPLACE FUNCTION commercial_private.commercial_assign_inventory_cost_allocation_sequence()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
  v_next bigint;
BEGIN
  -- Serialize sequence assignment for this sale item. The allocator also locks
  -- the item, but keep the invariant local to the table write boundary.
  PERFORM 1
  FROM public.commercial_sale_items i
  WHERE i.id=NEW.sale_item_id
  FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'sale_item_not_found_for_allocation_sequence' USING ERRCODE='P0002';
  END IF;

  SELECT coalesce(max(a.allocation_sequence),0)+1
    INTO v_next
  FROM public.commercial_inventory_cost_allocations a
  WHERE a.sale_item_id=NEW.sale_item_id;

  -- Never accept a caller-supplied sequence; the database owns the order.
  NEW.allocation_sequence:=v_next;
  RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION commercial_private.commercial_assign_inventory_cost_allocation_sequence()
  FROM PUBLIC,anon,authenticated;

DROP TRIGGER IF EXISTS commercial_inventory_cost_allocation_sequence_trg
  ON public.commercial_inventory_cost_allocations;
CREATE TRIGGER commercial_inventory_cost_allocation_sequence_trg
BEFORE INSERT ON public.commercial_inventory_cost_allocations
FOR EACH ROW
EXECUTE FUNCTION commercial_private.commercial_assign_inventory_cost_allocation_sequence();

CREATE OR REPLACE FUNCTION commercial_private.commercial_restore_return_cost_layers()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
  v_delta numeric;
  v_return record;
  v_sale_item public.commercial_sale_items%ROWTYPE;
  v_posting public.commercial_return_restock_postings%ROWTYPE;
  v_alloc record;
  v_remaining numeric;
  v_take numeric;
  v_layer_qty numeric := 0;
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

  -- Legacy sale lines predate cost-layer allocations. Preserve their existing
  -- return restock path; the return GL routine falls back to posting snapshots.
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_inventory_cost_allocations a
    WHERE a.sale_item_id=NEW.sale_item_id
  ) THEN
    RETURN NEW;
  END IF;

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
    -- Restore the most recently consumed allocation first. New rows have an
    -- exact sequence; legacy NULL sequences retain the prior deterministic
    -- created_at/UUID fallback without inventing historical ordering.
    ORDER BY a.allocation_sequence DESC NULLS LAST,a.created_at DESC,a.id DESC
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
  IF v_layer_qty>v_balance.quantity_on_hand THEN
    RAISE EXCEPTION 'return_cost_layer_balance_quantity_mismatch' USING ERRCODE='55000';
  END IF;
  IF v_layer_qty<=0 THEN
    RAISE EXCEPTION 'return_cost_layer_balance_empty' USING ERRCODE='55000';
  END IF;

  -- The existing restock RPC has already updated the moving-average balance
  -- using the sale-line cost snapshot. Keep every active layer aligned to that
  -- current average; source_unit_cost_minor and return allocations retain the
  -- original receipt/sale cost evidence. FIFO/LIFO layer costs stay untouched.
  IF v_method='weighted_average' THEN
    IF v_balance.unit_cost_minor IS NULL THEN
      RAISE EXCEPTION 'return_weighted_average_cost_unknown' USING ERRCODE='55000';
    END IF;
    UPDATE public.commercial_inventory_cost_layers
    SET unit_cost_minor=v_balance.unit_cost_minor
    WHERE organization_id=v_posting.organization_id
      AND location_id=v_posting.location_id
      AND offering_id=v_posting.offering_id
      AND quantity_remaining>0;
  END IF;

  RETURN NEW;
END;
$function$;

COMMIT;
