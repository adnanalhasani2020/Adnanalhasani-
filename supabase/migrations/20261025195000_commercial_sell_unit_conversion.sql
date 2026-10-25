-- Per-offering sell units. Inventory quantities remain in the offering's base stock unit.
CREATE TABLE IF NOT EXISTS public.commercial_offering_sell_units (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  offering_id uuid NOT NULL REFERENCES public.commercial_offerings(id) ON DELETE CASCADE,
  unit_code text NOT NULL CHECK (length(btrim(unit_code)) BETWEEN 1 AND 32),
  display_name text NOT NULL CHECK (length(btrim(display_name)) BETWEEN 1 AND 80),
  quantity_in_stock bigint NOT NULL CHECK (quantity_in_stock > 0),
  is_base boolean NOT NULL DEFAULT false,
  is_active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (offering_id, unit_code),
  CHECK (NOT is_base OR quantity_in_stock = 1)
);
CREATE UNIQUE INDEX IF NOT EXISTS commercial_offering_sell_units_one_base
  ON public.commercial_offering_sell_units(offering_id) WHERE is_base;
CREATE INDEX IF NOT EXISTS commercial_offering_sell_units_active_idx
  ON public.commercial_offering_sell_units(offering_id, is_active);
ALTER TABLE public.commercial_offering_sell_units ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.commercial_offering_sell_units FROM PUBLIC, anon, authenticated;

ALTER TABLE public.commercial_sale_items
  ADD COLUMN IF NOT EXISTS selling_unit_code text,
  ADD COLUMN IF NOT EXISTS selling_unit_name text,
  ADD COLUMN IF NOT EXISTS selling_quantity numeric(18,3),
  ADD COLUMN IF NOT EXISTS quantity_in_stock_units bigint;

UPDATE public.commercial_sale_items
SET selling_unit_code = coalesce(selling_unit_code, 'each'),
    selling_unit_name = coalesce(selling_unit_name, 'حبة'),
    selling_quantity = coalesce(selling_quantity, quantity::numeric),
    quantity_in_stock_units = coalesce(quantity_in_stock_units, quantity::bigint)
WHERE selling_unit_code IS NULL OR selling_unit_name IS NULL
   OR selling_quantity IS NULL OR quantity_in_stock_units IS NULL;

ALTER TABLE public.commercial_sale_items
  ALTER COLUMN selling_unit_code SET NOT NULL,
  ALTER COLUMN selling_unit_name SET NOT NULL,
  ALTER COLUMN selling_quantity SET NOT NULL,
  ALTER COLUMN quantity_in_stock_units SET NOT NULL;
ALTER TABLE public.commercial_sale_items
  ADD CONSTRAINT commercial_sale_items_selling_quantity_positive CHECK (selling_quantity > 0),
  ADD CONSTRAINT commercial_sale_items_stock_quantity_matches CHECK (quantity_in_stock_units = quantity);

-- Existing offerings get a 1:1 base unit. No legacy stock quantity is reinterpreted.
INSERT INTO public.commercial_offering_sell_units(offering_id, unit_code, display_name, quantity_in_stock, is_base)
SELECT o.id,
       coalesce(nullif(p.unit_code,''), 'each'),
       coalesce(nullif(p.unit_name,''), 'حبة'),
       1,
       true
FROM public.commercial_offerings o
JOIN public.commercial_products p ON p.id=o.product_id
ON CONFLICT (offering_id, unit_code) DO NOTHING;

CREATE OR REPLACE FUNCTION public.commercial_configure_offering_sell_unit(
  p_offering_id uuid,
  p_unit_code text,
  p_display_name text,
  p_quantity_in_stock bigint,
  p_is_base boolean DEFAULT false
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public'
AS $function$
DECLARE v_uid uuid := auth.uid(); v_org uuid; v_result public.commercial_offering_sell_units%ROWTYPE;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_offering_id IS NULL OR p_unit_code IS NULL OR length(btrim(p_unit_code)) NOT BETWEEN 1 AND 32
     OR p_display_name IS NULL OR length(btrim(p_display_name)) NOT BETWEEN 1 AND 80
     OR p_quantity_in_stock IS NULL OR p_quantity_in_stock < 1 OR p_quantity_in_stock > 1000000 THEN
    RAISE EXCEPTION 'invalid_sell_unit_configuration' USING ERRCODE='22023';
  END IF;
  IF coalesce(p_is_base,false) AND p_quantity_in_stock <> 1 THEN
    RAISE EXCEPTION 'base_unit_factor_must_be_one' USING ERRCODE='22023';
  END IF;
  SELECT a.organization_id INTO v_org
  FROM public.commercial_offerings o JOIN public.commercial_activities a ON a.id=o.activity_id
  WHERE o.id=p_offering_id AND o.state='active';
  IF NOT FOUND OR NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=v_org AND m.user_id=v_uid AND m.state='active'
      AND m.role IN ('owner','admin','manager')
  ) THEN RAISE EXCEPTION 'sell_unit_configuration_permission_required' USING ERRCODE='42501'; END IF;

  IF coalesce(p_is_base,false) THEN
    UPDATE public.commercial_offering_sell_units SET is_base=false,updated_at=now()
    WHERE offering_id=p_offering_id AND is_base=true AND unit_code<>btrim(p_unit_code);
  END IF;
  INSERT INTO public.commercial_offering_sell_units(offering_id,unit_code,display_name,quantity_in_stock,is_base,is_active,updated_at)
  VALUES(p_offering_id,btrim(p_unit_code),btrim(p_display_name),p_quantity_in_stock,coalesce(p_is_base,false),true,now())
  ON CONFLICT(offering_id,unit_code) DO UPDATE
    SET display_name=excluded.display_name,quantity_in_stock=excluded.quantity_in_stock,
        is_base=excluded.is_base,is_active=true,updated_at=now()
  RETURNING * INTO v_result;
  RETURN jsonb_build_object('id',v_result.id,'offering_id',v_result.offering_id,
    'unit_code',v_result.unit_code,'display_name',v_result.display_name,
    'quantity_in_stock',v_result.quantity_in_stock,'is_base',v_result.is_base,'is_active',v_result.is_active);
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_configure_offering_sell_unit(uuid,text,text,bigint,boolean) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_configure_offering_sell_unit(uuid,text,text,bigint,boolean) TO authenticated;

-- Keep the original audited checkout implementation as the base-unit executor.
ALTER FUNCTION public.commercial_checkout_sale_multi(jsonb,uuid,text,uuid,text,bigint,bigint,bigint)
  RENAME TO commercial_checkout_sale_multi_base_units;

CREATE OR REPLACE FUNCTION public.commercial_checkout_sale_multi(
  p_lines jsonb, p_location_id uuid, p_idempotency_key text, p_customer_id uuid,
  p_payment_mode text, p_initial_payment_minor bigint, p_discount_minor bigint, p_tax_minor bigint
) RETURNS jsonb
LANGUAGE plpgsql SECURITY INVOKER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
  v_line jsonb; v_lines jsonb := '[]'::jsonb; v_base_qty bigint;
  v_factor bigint; v_unit record; v_sale jsonb; v_sale_id uuid;
  v_effective_key text; v_original_lines jsonb;
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_lines IS NULL OR jsonb_typeof(p_lines)<>'array' OR jsonb_array_length(p_lines)<1 OR jsonb_array_length(p_lines)>200 THEN
    RAISE EXCEPTION 'invalid_invoice_lines_or_location' USING ERRCODE='22023';
  END IF;
  v_original_lines := (SELECT jsonb_agg(jsonb_build_object(
      'offering_id',e->>'offering_id','quantity',e->>'quantity',
      'unit_code',coalesce(e->>'unit_code','<base>')) ORDER BY e->>'offering_id')
    FROM jsonb_array_elements(p_lines) e);
  v_effective_key := btrim(p_idempotency_key)||'-u-'||substr(md5(v_original_lines::text),1,12);
  IF length(v_effective_key)>200 THEN v_effective_key:=substr(v_effective_key,1,187)||'-u-'||substr(md5(v_original_lines::text),1,12); END IF;

  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines) LOOP
    IF coalesce(v_line->>'offering_id','') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
       OR coalesce(v_line->>'quantity','') !~ '^[1-9][0-9]{0,5}$' THEN
      RAISE EXCEPTION 'invalid_invoice_line' USING ERRCODE='22023';
    END IF;
    SELECT su.quantity_in_stock, su.unit_code, su.display_name
      INTO v_unit
    FROM public.commercial_offering_sell_units su
    WHERE su.offering_id=(v_line->>'offering_id')::uuid
      AND ((nullif(v_line->>'unit_code','') IS NOT NULL AND su.unit_code=v_line->>'unit_code')
           OR (nullif(v_line->>'unit_code','') IS NULL AND su.is_base=true))
      AND su.is_active=true;
    IF NOT FOUND THEN RAISE EXCEPTION 'sell_unit_not_configured_for_offering' USING ERRCODE='22023'; END IF;
    v_base_qty := (v_line->>'quantity')::bigint * v_unit.quantity_in_stock;
    IF v_base_qty < 1 OR v_base_qty > 100000 THEN RAISE EXCEPTION 'converted_quantity_out_of_range' USING ERRCODE='22023'; END IF;
    v_lines := v_lines || jsonb_build_array(jsonb_build_object(
      'offering_id',v_line->>'offering_id','quantity',v_base_qty,
      'unit_code',v_unit.unit_code,'selling_quantity',(v_line->>'quantity')::numeric,
      'unit_name',v_unit.display_name,'factor',v_unit.quantity_in_stock));
  END LOOP;

  SELECT public.commercial_checkout_sale_multi_base_units(
    (SELECT jsonb_agg(jsonb_build_object('offering_id',e->>'offering_id','quantity',(e->>'quantity')::bigint)
      ORDER BY e->>'offering_id') FROM jsonb_array_elements(v_lines) e),
    p_location_id,v_effective_key,p_customer_id,p_payment_mode,p_initial_payment_minor,p_discount_minor,p_tax_minor
  ) INTO v_sale;
  v_sale_id := (v_sale->>'sale_id')::uuid;
  UPDATE public.commercial_sale_items i
  SET selling_unit_code=u->>'unit_code',
      selling_unit_name=u->>'unit_name',
      selling_quantity=(u->>'selling_quantity')::numeric,
      quantity_in_stock_units=(u->>'quantity')::bigint
  FROM jsonb_array_elements(v_lines) u
  WHERE i.sale_id=v_sale_id AND i.offering_id=(u->>'offering_id')::uuid;
  RETURN v_sale || jsonb_build_object('sell_units_normalized',true);
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_checkout_sale_multi(jsonb,uuid,text,uuid,text,bigint,bigint,bigint) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_checkout_sale_multi(jsonb,uuid,text,uuid,text,bigint,bigint,bigint) TO authenticated;

-- Return requests accept quantities in the original sale unit when unit_code is supplied.
ALTER FUNCTION public.commercial_request_sale_return(uuid,text,text,jsonb,text)
  RENAME TO commercial_request_sale_return_base_units;

CREATE OR REPLACE FUNCTION public.commercial_request_sale_return(
  p_sale_id uuid, p_idempotency_key text, p_reason_code text, p_items jsonb, p_reason_note text DEFAULT NULL
) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public'
AS $function$
DECLARE v_item jsonb; v_items jsonb := '[]'::jsonb; v_sale_item public.commercial_sale_items%ROWTYPE;
        v_unit_code text; v_factor bigint; v_qty bigint; v_prior_key text;
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_items IS NULL OR jsonb_typeof(p_items)<>'array' OR jsonb_array_length(p_items)<1 OR jsonb_array_length(p_items)>100 THEN
    RAISE EXCEPTION 'items_must_be_nonempty_array' USING ERRCODE='22023';
  END IF;
  FOR v_item IN SELECT value FROM jsonb_array_elements(p_items) LOOP
    IF coalesce(v_item->>'sale_item_id','') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
       OR coalesce(v_item->>'quantity','') !~ '^[1-9][0-9]{0,5}$' THEN
      RAISE EXCEPTION 'invalid_return_item' USING ERRCODE='22023';
    END IF;
    SELECT * INTO v_sale_item FROM public.commercial_sale_items
    WHERE id=(v_item->>'sale_item_id')::uuid AND sale_id=p_sale_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'sale_item_not_found_for_sale' USING ERRCODE='22023'; END IF;
    IF v_item ? 'unit_code' THEN
      v_unit_code := v_item->>'unit_code';
      SELECT quantity_in_stock INTO v_factor
      FROM public.commercial_offering_sell_units
      WHERE offering_id=v_sale_item.offering_id AND unit_code=v_unit_code AND is_active=true;
      IF NOT FOUND THEN RAISE EXCEPTION 'return_sell_unit_not_configured' USING ERRCODE='22023'; END IF;
      v_qty := (v_item->>'quantity')::bigint * v_factor;
    ELSE
      v_qty := (v_item->>'quantity')::bigint;
    END IF;
    IF v_qty<1 OR v_qty>v_sale_item.quantity THEN RAISE EXCEPTION 'invalid_return_quantity' USING ERRCODE='22023'; END IF;
    v_items := v_items || jsonb_build_array(
      jsonb_build_object('sale_item_id',v_sale_item.id,'quantity',v_qty,
        'restock',coalesce((v_item->>'restock')::boolean,true),
        'condition_code',coalesce(v_item->>'condition_code','uninspected')));
  END LOOP;
  RETURN public.commercial_request_sale_return_base_units(p_sale_id,p_idempotency_key,p_reason_code,v_items,p_reason_note);
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_request_sale_return(uuid,text,text,jsonb,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_request_sale_return(uuid,text,text,jsonb,text) TO authenticated;

COMMENT ON TABLE public.commercial_offering_sell_units IS
'Per-offering sale-unit conversion. quantity_in_stock is an exact integer multiplier to the base stock unit; all inventory, COGS and return quantities remain in base units.';
