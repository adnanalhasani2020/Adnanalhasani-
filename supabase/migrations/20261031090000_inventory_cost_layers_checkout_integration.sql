BEGIN;

-- Checkout has already committed its stock movement inside the same transaction
-- before this deferred trigger runs. Verify that exact movement before consuming
-- valuation layers; this also runs after the mixed-selling-unit wrapper has split
-- the final sale_item rows.
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
  v_expected_sale_quantity numeric;
  v_movement_count integer;
  v_movement_delta numeric;
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
  WHERE i.id=p_sale_item_id
  FOR UPDATE;
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

  -- Require proof that checkout posted the matching stock deduction. The sum
  -- includes all final selling-unit rows for this offering, not just this row.
  SELECT count(*)::integer, min(sm.quantity_delta)
    INTO v_movement_count,v_movement_delta
  FROM public.commercial_stock_movements sm
  WHERE sm.sale_id=v_item.sale_id
    AND sm.offering_id=v_item.offering_id
    AND sm.movement_type='sale'
    AND sm.source_ref='sale:'||v_item.sale_id::text||':offering:'||v_item.offering_id::text||':location:'||p_location_id::text;
  SELECT coalesce(sum(coalesce(i.quantity_in_stock_units,i.quantity)::numeric),0)
    INTO v_expected_sale_quantity
  FROM public.commercial_sale_items i
  WHERE i.sale_id=v_item.sale_id AND i.offering_id=v_item.offering_id;
  IF v_movement_count<>1 OR v_movement_delta IS DISTINCT FROM -v_expected_sale_quantity THEN
    RAISE EXCEPTION 'sale_inventory_movement_cost_scope_mismatch' USING ERRCODE='55000';
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
  IF NOT FOUND OR v_balance.quantity_on_hand<0 OR v_balance.unit_cost_minor<0 THEN
    RAISE EXCEPTION 'inventory_balance_invalid_for_cost_allocation' USING ERRCODE='55000';
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
    v_layer_cost := CASE WHEN v_method='weighted_average' THEN v_balance.unit_cost_minor ELSE v_layer.source_unit_cost_minor END;
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
      DETAIL='The sale cannot be posted until all issued stock has a verified cost layer.';
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

-- Run once at transaction end, after the mixed-unit wrapper has materialized
-- every final sale line. All allocations and any COGS correction roll back with
-- checkout if a layer or journal invariant fails.
CREATE OR REPLACE FUNCTION commercial_private.commercial_finalize_sale_inventory_costing(p_sale_id uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
  v_sale public.commercial_sales%ROWTYPE;
  v_offering uuid;
  v_location uuid;
  v_location_text text;
  v_movement_count integer;
  v_movement_delta numeric;
  v_expected_quantity numeric;
  v_item record;
  v_has_sale_movements boolean := false;
  v_cogs_account uuid;
  v_inventory_account uuid;
  v_original_cogs numeric := 0;
  v_target_cogs numeric := 0;
  v_delta numeric := 0;
  v_existing_adjustment uuid;
  v_existing_adjustment_cogs numeric;
  v_lines jsonb;
BEGIN
  IF p_sale_id IS NULL THEN RETURN; END IF;
  SELECT * INTO v_sale FROM public.commercial_sales WHERE id=p_sale_id FOR UPDATE;
  IF NOT FOUND OR v_sale.status<>'completed' THEN RETURN; END IF;
  IF auth.uid() IS NULL OR v_sale.user_id IS DISTINCT FROM auth.uid() THEN
    RAISE EXCEPTION 'sale_costing_actor_mismatch' USING ERRCODE='42501';
  END IF;

  FOR v_offering IN
    SELECT DISTINCT i.offering_id
    FROM public.commercial_sale_items i
    WHERE i.sale_id=p_sale_id
    ORDER BY i.offering_id
  LOOP
    SELECT count(*)::integer, min(sm.quantity_delta),
           min(substring(sm.source_ref from ':location:([0-9a-fA-F-]{36})
    FROM public.commercial_stock_movements sm
    WHERE sm.sale_id=p_sale_id AND sm.offering_id=v_offering
      AND sm.movement_type='sale'
      AND sm.source_ref LIKE 'sale:'||p_sale_id::text||':offering:'||v_offering::text||':location:%';
    IF v_movement_count=0 THEN
      -- Other legacy sale flows that do not post this stock-movement contract
      -- retain their existing valuation behavior.
      CONTINUE;
    END IF;
    v_has_sale_movements := true;
    IF v_movement_count<>1 OR v_location_text IS NULL THEN
      RAISE EXCEPTION 'sale_costing_stock_movement_ambiguous' USING ERRCODE='55000';
    END IF;
    v_location := v_location_text::uuid;
    SELECT coalesce(sum(coalesce(i.quantity_in_stock_units,i.quantity)::numeric),0)
      INTO v_expected_quantity
    FROM public.commercial_sale_items i
    WHERE i.sale_id=p_sale_id AND i.offering_id=v_offering;
    IF v_movement_delta IS DISTINCT FROM -v_expected_quantity THEN
      RAISE EXCEPTION 'sale_costing_stock_movement_quantity_mismatch' USING ERRCODE='55000';
    END IF;

    FOR v_item IN
      SELECT i.id
      FROM public.commercial_sale_items i
      WHERE i.sale_id=p_sale_id AND i.offering_id=v_offering
      ORDER BY i.created_at,i.id
    LOOP
      PERFORM commercial_private.commercial_allocate_sale_item_cost(v_item.id,v_location);
    END LOOP;
  END LOOP;

  IF NOT v_has_sale_movements THEN RETURN; END IF;

  SELECT coalesce(sum(i.cost_total_minor::numeric),0)
    INTO v_target_cogs
  FROM public.commercial_sale_items i
  WHERE i.sale_id=p_sale_id;

  SELECT max(m.ledger_account_id) FILTER (WHERE m.mapping_key='cost_of_goods_sold'),
         max(m.ledger_account_id) FILTER (WHERE m.mapping_key='inventory')
    INTO v_cogs_account,v_inventory_account
  FROM public.commercial_gl_account_mappings m
  WHERE m.organization_id=v_sale.organization_id;
  IF v_target_cogs>0 AND (v_cogs_account IS NULL OR v_inventory_account IS NULL) THEN
    RAISE EXCEPTION 'inventory_costing_gl_mappings_incomplete' USING ERRCODE='55000';
  END IF;

  SELECT coalesce(sum(l.debit_minor::numeric-l.credit_minor::numeric),0)
    INTO v_original_cogs
  FROM public.commercial_journal_entries e
  JOIN public.commercial_journal_lines l ON l.journal_entry_id=e.id
  WHERE e.organization_id=v_sale.organization_id
    AND e.source_type='sale' AND e.source_id=p_sale_id AND e.status='posted'
    AND l.ledger_account_id=v_cogs_account;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_journal_entries e
    WHERE e.organization_id=v_sale.organization_id
      AND e.source_type='sale' AND e.source_id=p_sale_id AND e.status='posted'
  ) THEN
    RAISE EXCEPTION 'sale_costing_original_journal_missing' USING ERRCODE='55000';
  END IF;

  v_delta := v_target_cogs-v_original_cogs;
  IF abs(v_delta)>9223372036854775807 THEN
    RAISE EXCEPTION 'inventory_costing_gl_adjustment_overflow' USING ERRCODE='22003';
  END IF;

  SELECT e.id INTO v_existing_adjustment
  FROM public.commercial_journal_entries e
  WHERE e.organization_id=v_sale.organization_id
    AND e.source_type='sale_cost_adjustment' AND e.source_id=p_sale_id
    AND e.status='posted'
  ORDER BY e.created_at DESC
  LIMIT 1;

  IF v_existing_adjustment IS NOT NULL THEN
    SELECT coalesce(sum(l.debit_minor::numeric-l.credit_minor::numeric),0)
      INTO v_existing_adjustment_cogs
    FROM public.commercial_journal_lines l
    WHERE l.journal_entry_id=v_existing_adjustment
      AND l.ledger_account_id=v_cogs_account;
    IF v_existing_adjustment_cogs IS DISTINCT FROM v_delta THEN
      RAISE EXCEPTION 'sale_costing_adjustment_reconciliation_mismatch' USING ERRCODE='55000';
    END IF;
    RETURN;
  END IF;

  IF v_delta<>0 THEN
    IF v_delta>0 THEN
      v_lines:=jsonb_build_array(
        jsonb_build_object('ledger_account_id',v_cogs_account,'debit_minor',v_delta::bigint,'credit_minor',0,'description','Inventory costing method adjustment - COGS'),
        jsonb_build_object('ledger_account_id',v_inventory_account,'debit_minor',0,'credit_minor',v_delta::bigint,'description','Inventory costing method adjustment - inventory')
      );
    ELSE
      v_lines:=jsonb_build_array(
        jsonb_build_object('ledger_account_id',v_inventory_account,'debit_minor',abs(v_delta)::bigint,'credit_minor',0,'description','Inventory costing method adjustment - inventory'),
        jsonb_build_object('ledger_account_id',v_cogs_account,'debit_minor',0,'credit_minor',abs(v_delta)::bigint,'description','Inventory costing method adjustment - COGS')
      );
    END IF;
    PERFORM commercial_private.commercial_gl_post_entry(
      v_sale.organization_id,
      'sale-cost-adjustment:'||p_sale_id::text,
      'sale_cost_adjustment',
      p_sale_id,
      'Inventory costing reconciliation for sale '||p_sale_id::text,
      v_sale.currency_code,
      v_lines,
      v_sale.user_id,
      NULL
    );
  END IF;
END;
$function$;

CREATE OR REPLACE FUNCTION commercial_private.commercial_finalize_sale_inventory_costing_trigger()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
BEGIN
  PERFORM commercial_private.commercial_finalize_sale_inventory_costing(NEW.sale_id);
  RETURN NULL;
END;
$function$;

REVOKE ALL ON FUNCTION commercial_private.commercial_allocate_sale_item_cost(uuid,uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION commercial_private.commercial_allocate_sale_item_cost(uuid,uuid) TO postgres,service_role;
REVOKE ALL ON FUNCTION commercial_private.commercial_finalize_sale_inventory_costing(uuid) FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION commercial_private.commercial_finalize_sale_inventory_costing_trigger() FROM PUBLIC,anon,authenticated;

DROP TRIGGER IF EXISTS commercial_sale_item_costing_finalize_trg ON public.commercial_sale_items;
CREATE CONSTRAINT TRIGGER commercial_sale_item_costing_finalize_trg
AFTER INSERT OR UPDATE ON public.commercial_sale_items
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION commercial_private.commercial_finalize_sale_inventory_costing_trigger();

COMMIT;
))
      INTO v_movement_count,v_movement_delta,v_location_text
    FROM public.commercial_stock_movements sm
    WHERE sm.sale_id=p_sale_id AND sm.offering_id=v_offering
      AND sm.movement_type='sale'
      AND sm.source_ref LIKE 'sale:'||p_sale_id::text||':offering:'||v_offering::text||':location:%';
    IF v_movement_count=0 THEN
      -- Other legacy sale flows that do not post this stock-movement contract
      -- retain their existing valuation behavior.
      CONTINUE;
    END IF;
    v_has_sale_movements := true;
    IF v_movement_count<>1 OR v_location IS NULL THEN
      RAISE EXCEPTION 'sale_costing_stock_movement_ambiguous' USING ERRCODE='55000';
    END IF;
    v_location := v_location::uuid;
    SELECT coalesce(sum(coalesce(i.quantity_in_stock_units,i.quantity)::numeric),0)
      INTO v_expected_quantity
    FROM public.commercial_sale_items i
    WHERE i.sale_id=p_sale_id AND i.offering_id=v_offering;
    IF v_movement_delta IS DISTINCT FROM -v_expected_quantity THEN
      RAISE EXCEPTION 'sale_costing_stock_movement_quantity_mismatch' USING ERRCODE='55000';
    END IF;

    FOR v_item IN
      SELECT i.id
      FROM public.commercial_sale_items i
      WHERE i.sale_id=p_sale_id AND i.offering_id=v_offering
      ORDER BY i.created_at,i.id
    LOOP
      PERFORM commercial_private.commercial_allocate_sale_item_cost(v_item.id,v_location);
    END LOOP;
  END LOOP;

  IF NOT v_has_sale_movements THEN RETURN; END IF;

  SELECT coalesce(sum(i.cost_total_minor::numeric),0)
    INTO v_target_cogs
  FROM public.commercial_sale_items i
  WHERE i.sale_id=p_sale_id;

  SELECT max(m.ledger_account_id) FILTER (WHERE m.mapping_key='cost_of_goods_sold'),
         max(m.ledger_account_id) FILTER (WHERE m.mapping_key='inventory')
    INTO v_cogs_account,v_inventory_account
  FROM public.commercial_gl_account_mappings m
  WHERE m.organization_id=v_sale.organization_id;
  IF v_target_cogs>0 AND (v_cogs_account IS NULL OR v_inventory_account IS NULL) THEN
    RAISE EXCEPTION 'inventory_costing_gl_mappings_incomplete' USING ERRCODE='55000';
  END IF;

  SELECT coalesce(sum(l.debit_minor::numeric-l.credit_minor::numeric),0)
    INTO v_original_cogs
  FROM public.commercial_journal_entries e
  JOIN public.commercial_journal_lines l ON l.journal_entry_id=e.id
  WHERE e.organization_id=v_sale.organization_id
    AND e.source_type='sale' AND e.source_id=p_sale_id AND e.status='posted'
    AND l.ledger_account_id=v_cogs_account;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_journal_entries e
    WHERE e.organization_id=v_sale.organization_id
      AND e.source_type='sale' AND e.source_id=p_sale_id AND e.status='posted'
  ) THEN
    RAISE EXCEPTION 'sale_costing_original_journal_missing' USING ERRCODE='55000';
  END IF;

  v_delta := v_target_cogs-v_original_cogs;
  IF abs(v_delta)>9223372036854775807 THEN
    RAISE EXCEPTION 'inventory_costing_gl_adjustment_overflow' USING ERRCODE='22003';
  END IF;

  SELECT e.id INTO v_existing_adjustment
  FROM public.commercial_journal_entries e
  WHERE e.organization_id=v_sale.organization_id
    AND e.source_type='sale_cost_adjustment' AND e.source_id=p_sale_id
    AND e.status='posted'
  ORDER BY e.created_at DESC
  LIMIT 1;

  IF v_existing_adjustment IS NOT NULL THEN
    SELECT coalesce(sum(l.debit_minor::numeric-l.credit_minor::numeric),0)
      INTO v_existing_adjustment_cogs
    FROM public.commercial_journal_lines l
    WHERE l.journal_entry_id=v_existing_adjustment
      AND l.ledger_account_id=v_cogs_account;
    IF v_existing_adjustment_cogs IS DISTINCT FROM v_delta THEN
      RAISE EXCEPTION 'sale_costing_adjustment_reconciliation_mismatch' USING ERRCODE='55000';
    END IF;
    RETURN;
  END IF;

  IF v_delta<>0 THEN
    IF v_delta>0 THEN
      v_lines:=jsonb_build_array(
        jsonb_build_object('ledger_account_id',v_cogs_account,'debit_minor',v_delta::bigint,'credit_minor',0,'description','Inventory costing method adjustment - COGS'),
        jsonb_build_object('ledger_account_id',v_inventory_account,'debit_minor',0,'credit_minor',v_delta::bigint,'description','Inventory costing method adjustment - inventory')
      );
    ELSE
      v_lines:=jsonb_build_array(
        jsonb_build_object('ledger_account_id',v_inventory_account,'debit_minor',abs(v_delta)::bigint,'credit_minor',0,'description','Inventory costing method adjustment - inventory'),
        jsonb_build_object('ledger_account_id',v_cogs_account,'debit_minor',0,'credit_minor',abs(v_delta)::bigint,'description','Inventory costing method adjustment - COGS')
      );
    END IF;
    PERFORM commercial_private.commercial_gl_post_entry(
      v_sale.organization_id,
      'sale-cost-adjustment:'||p_sale_id::text,
      'sale_cost_adjustment',
      p_sale_id,
      'Inventory costing reconciliation for sale '||p_sale_id::text,
      v_sale.currency_code,
      v_lines,
      v_sale.user_id,
      NULL
    );
  END IF;
END;
$function$;

CREATE OR REPLACE FUNCTION commercial_private.commercial_finalize_sale_inventory_costing_trigger()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
BEGIN
  PERFORM commercial_private.commercial_finalize_sale_inventory_costing(NEW.sale_id);
  RETURN NULL;
END;
$function$;

REVOKE ALL ON FUNCTION commercial_private.commercial_allocate_sale_item_cost(uuid,uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION commercial_private.commercial_allocate_sale_item_cost(uuid,uuid) TO postgres,service_role;
REVOKE ALL ON FUNCTION commercial_private.commercial_finalize_sale_inventory_costing(uuid) FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION commercial_private.commercial_finalize_sale_inventory_costing_trigger() FROM PUBLIC,anon,authenticated;

DROP TRIGGER IF EXISTS commercial_sale_item_costing_finalize_trg ON public.commercial_sale_items;
CREATE CONSTRAINT TRIGGER commercial_sale_item_costing_finalize_trg
AFTER INSERT OR UPDATE ON public.commercial_sale_items
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION commercial_private.commercial_finalize_sale_inventory_costing_trigger();

COMMIT;
