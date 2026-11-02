BEGIN;

CREATE TABLE IF NOT EXISTS public.commercial_invoice_purchase_item_creation_requests (
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  idempotency_key text NOT NULL CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 200),
  request_fingerprint text NOT NULL,
  purchase_order_id uuid NOT NULL REFERENCES public.commercial_purchase_orders(id) ON DELETE RESTRICT,
  purchase_order_item_id uuid NOT NULL REFERENCES public.commercial_purchase_order_items(id) ON DELETE RESTRICT,
  global_item_id uuid NOT NULL REFERENCES public.commercial_global_catalog_items(id) ON DELETE RESTRICT,
  offering_id uuid NOT NULL REFERENCES public.commercial_offerings(id) ON DELETE RESTRICT,
  response jsonb NOT NULL CHECK (jsonb_typeof(response) = 'object'),
  created_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (organization_id, idempotency_key),
  UNIQUE (organization_id, purchase_order_item_id)
);
ALTER TABLE public.commercial_invoice_purchase_item_creation_requests ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.commercial_invoice_purchase_item_creation_requests FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.commercial_create_and_link_invoice_purchase_item(
  p_organization_id uuid,
  p_purchase_order_id uuid,
  p_purchase_order_item_id uuid,
  p_activity_id uuid,
  p_activity_node_code text,
  p_warehouse_id uuid,
  p_name_ar text,
  p_name_en text,
  p_category_code text,
  p_base_unit_code text,
  p_sale_unit_code text,
  p_item_type text,
  p_local_category_id uuid,
  p_idempotency_key text
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, commercial_private
AS $$
DECLARE
  v_uid uuid := auth.uid();
  v_order public.commercial_purchase_orders%ROWTYPE;
  v_line public.commercial_purchase_order_items%ROWTYPE;
  v_node uuid;
  v_activity_node uuid;
  v_currency text;
  v_base_dimension text;
  v_sale_factor numeric(30,12);
  v_unit_name text;
  v_global_id uuid;
  v_offering_id uuid;
  v_product_result jsonb;
  v_offering_result jsonb;
  v_link_result jsonb;
  v_units jsonb;
  v_product jsonb;
  v_fp text;
  v_saved public.commercial_invoice_purchase_item_creation_requests%ROWTYPE;
  v_existing_ids uuid[];
  v_existing_count integer;
  v_response jsonb;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE = '42501';
  END IF;
  IF p_organization_id IS NULL OR p_purchase_order_id IS NULL
     OR p_purchase_order_item_id IS NULL OR p_activity_id IS NULL
     OR p_warehouse_id IS NULL OR nullif(btrim(p_activity_node_code), '') IS NULL
     OR nullif(btrim(p_name_ar), '') IS NULL OR length(btrim(p_name_ar)) > 180
     OR nullif(btrim(p_name_en), '') IS NULL OR length(btrim(p_name_en)) > 180
     OR nullif(btrim(p_category_code), '') IS NULL
     OR nullif(btrim(p_base_unit_code), '') IS NULL
     OR nullif(btrim(p_sale_unit_code), '') IS NULL
     OR p_item_type IS NULL OR p_item_type NOT IN ('product', 'material', 'asset')
     OR length(btrim(coalesce(p_idempotency_key, ''))) NOT BETWEEN 8 AND 200 THEN
    RAISE EXCEPTION 'invalid_invoice_purchase_item_creation_request' USING ERRCODE = '22023';
  END IF;
  IF NOT public.commercial_catalog_member_of(p_organization_id, ARRAY['owner','admin']::text[]) THEN
    RAISE EXCEPTION 'catalog_product_create_forbidden' USING ERRCODE = '42501';
  END IF;

  v_fp := md5(jsonb_build_object(
    'purchase_order_id', p_purchase_order_id,
    'purchase_order_item_id', p_purchase_order_item_id,
    'activity_id', p_activity_id,
    'activity_node_code', upper(btrim(p_activity_node_code)),
    'warehouse_id', p_warehouse_id,
    'name_ar', btrim(p_name_ar),
    'name_en', btrim(p_name_en),
    'category_code', upper(btrim(p_category_code)),
    'base_unit_code', upper(btrim(p_base_unit_code)),
    'sale_unit_code', upper(btrim(p_sale_unit_code)),
    'item_type', p_item_type,
    'local_category_id', p_local_category_id
  )::text);

  PERFORM pg_advisory_xact_lock(hashtext(p_organization_id::text),
    hashtext('invoice-item-create:' || btrim(p_idempotency_key)));

  SELECT * INTO v_saved
  FROM public.commercial_invoice_purchase_item_creation_requests
  WHERE organization_id = p_organization_id AND idempotency_key = btrim(p_idempotency_key);
  IF FOUND THEN
    IF v_saved.request_fingerprint IS DISTINCT FROM v_fp THEN
      RAISE EXCEPTION 'invoice_item_creation_key_reused_with_different_request' USING ERRCODE = '23505';
    END IF;
    RETURN v_saved.response || jsonb_build_object('idempotent_replay', true);
  END IF;

  SELECT * INTO v_order
  FROM public.commercial_purchase_orders
  WHERE id = p_purchase_order_id AND organization_id = p_organization_id
  FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'purchase_order_not_found' USING ERRCODE = 'P0002'; END IF;
  IF v_order.status NOT IN ('draft', 'submitted', 'approved') THEN
    RAISE EXCEPTION 'purchase_order_item_creation_state_forbidden' USING ERRCODE = '55000';
  END IF;
  IF v_order.currency_code IS NULL OR upper(v_order.currency_code) !~ '^[A-Z]{3}  IF v_order.receive_location_id IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_locations l
    WHERE l.id = v_order.receive_location_id AND l.organization_id = p_organization_id AND l.state = 'active'
  ) THEN RAISE EXCEPTION 'purchase_order_receiving_location_invalid' USING ERRCODE = '22023'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_warehouses w
    WHERE w.id = p_warehouse_id AND w.organization_id = p_organization_id
      AND w.branch_location_id = v_order.receive_location_id AND w.state = 'active'
  ) THEN RAISE EXCEPTION 'warehouse_not_in_purchase_receiving_branch' USING ERRCODE = '22023'; END IF;

  SELECT * INTO v_line
  FROM public.commercial_purchase_order_items
  WHERE id = p_purchase_order_item_id AND organization_id = p_organization_id
    AND purchase_order_id = p_purchase_order_id
  FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'purchase_order_item_not_found' USING ERRCODE = 'P0002'; END IF;
  IF v_line.offering_id IS NOT NULL THEN RAISE EXCEPTION 'purchase_line_offering_already_linked' USING ERRCODE = '55000'; END IF;
  IF coalesce(v_line.quantity_received, 0) > 0
     OR EXISTS (SELECT 1 FROM public.commercial_supplier_invoices
       WHERE organization_id = p_organization_id AND purchase_order_id = p_purchase_order_id)
     OR EXISTS (SELECT 1 FROM public.commercial_purchase_receipts
       WHERE organization_id = p_organization_id AND purchase_order_id = p_purchase_order_id) THEN
    RAISE EXCEPTION 'purchase_line_item_creation_after_receipt_or_invoice_forbidden' USING ERRCODE = '55000';
  END IF;
  IF v_line.source_selling_unit_code IS NOT NULL
     AND upper(v_line.source_selling_unit_code) <> upper(btrim(p_sale_unit_code)) THEN
    RAISE EXCEPTION 'purchase_line_unit_mismatch_requires_unit_review' USING ERRCODE = '22023';
  END IF;

  SELECT id INTO v_node
  FROM public.commercial_global_business_activity_nodes
  WHERE code = upper(btrim(p_activity_node_code)) AND status = 'active' AND is_selectable;
  IF v_node IS NULL THEN RAISE EXCEPTION 'active_activity_not_found' USING ERRCODE = '22023'; END IF;

  SELECT business_activity_node_id INTO v_activity_node
  FROM public.commercial_activities
  WHERE id = p_activity_id AND organization_id = p_organization_id
    AND state = 'active' AND deleted_at IS NULL;
  IF NOT FOUND OR v_activity_node IS DISTINCT FROM v_node THEN
    RAISE EXCEPTION 'activity_node_does_not_match_selected_activity' USING ERRCODE = '22023';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_global_catalog_categories c
    WHERE c.code = upper(btrim(p_category_code)) AND c.is_active
      AND (c.owner_organization_id IS NULL OR c.owner_organization_id = p_organization_id)
  ) THEN RAISE EXCEPTION 'active_global_catalog_category_not_found' USING ERRCODE = '22023'; END IF;

  SELECT dimension INTO v_base_dimension
  FROM public.commercial_global_units
  WHERE code = upper(btrim(p_base_unit_code)) AND is_active
    AND (owner_organization_id IS NULL OR owner_organization_id = p_organization_id)
    AND (base_unit_code IS NULL OR btrim(base_unit_code) = '');
  IF v_base_dimension IS NULL THEN RAISE EXCEPTION 'active_base_unit_not_found' USING ERRCODE = '22023'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_unit_of_measure
    WHERE code = lower(btrim(p_base_unit_code)) AND dimension = v_base_dimension AND is_active
  ) THEN RAISE EXCEPTION 'active_unit_of_measure_not_found' USING ERRCODE = '22023'; END IF;

  IF upper(btrim(p_base_unit_code)) = upper(btrim(p_sale_unit_code)) THEN
    v_sale_factor := 1;
  ELSE
    SELECT factor_to_base INTO v_sale_factor
    FROM public.commercial_global_units
    WHERE code = upper(btrim(p_sale_unit_code))
      AND base_unit_code = upper(btrim(p_base_unit_code))
      AND dimension = v_base_dimension
      AND is_active
      AND (owner_organization_id IS NULL OR owner_organization_id = p_organization_id);
    IF v_sale_factor IS NULL OR v_sale_factor <= 0 THEN
      RAISE EXCEPTION 'sale_unit_not_compatible_with_base_unit' USING ERRCODE = '22023';
    END IF;
  END IF;

  IF v_line.source_selling_quantity IS NOT NULL
     AND v_line.source_quantity_in_stock_units IS NOT NULL
     AND abs(v_line.source_quantity_in_stock_units
       - (v_line.source_selling_quantity * v_sale_factor)) > 0.000001 THEN
    RAISE EXCEPTION 'purchase_line_unit_conversion_mismatch_requires_review' USING ERRCODE = '22023';
  END IF;

  IF p_local_category_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM public.commercial_product_categories c
    WHERE c.id = p_local_category_id AND c.organization_id = p_organization_id AND c.state = 'active'
  ) THEN RAISE EXCEPTION 'active_category_not_found_in_organization' USING ERRCODE = '22023'; END IF;

  SELECT display_name INTO v_unit_name
  FROM public.commercial_unit_of_measure
  WHERE code = lower(btrim(p_base_unit_code)) AND dimension = v_base_dimension AND is_active;
  v_currency := upper(v_order.currency_code);

  PERFORM pg_advisory_xact_lock(
    hashtext(p_organization_id::text),
    hashtext('catalog-item:' || lower(btrim(p_name_ar)) || '|' ||
      lower(btrim(p_name_en)) || '|' || upper(btrim(p_category_code)) || '|' ||
      upper(btrim(p_base_unit_code)))
  );

  SELECT array_agg(id), count(*)::integer INTO v_existing_ids, v_existing_count
  FROM public.commercial_global_catalog_items
  WHERE owner_organization_id = p_organization_id AND is_active
    AND lower(btrim(name_ar)) = lower(btrim(p_name_ar))
    AND lower(btrim(name_en)) = lower(btrim(p_name_en))
    AND upper(category_code) = upper(btrim(p_category_code))
    AND upper(unit_code) = upper(btrim(p_base_unit_code))
    AND item_type = p_item_type;
  IF v_existing_count > 1 THEN
    RAISE EXCEPTION 'multiple_exact_catalog_items_require_manual_resolution' USING ERRCODE = '23505';
  ELSIF v_existing_count = 1 THEN
    v_global_id := v_existing_ids[1];
    IF NOT EXISTS (
      SELECT 1 FROM public.commercial_catalog_product_units
      WHERE product_id = v_global_id AND unit_code = upper(btrim(p_base_unit_code))
        AND unit_role = 'base' AND quantity_in_base = 1 AND is_active
    ) THEN RAISE EXCEPTION 'existing_catalog_item_base_unit_mismatch' USING ERRCODE = '22023'; END IF;
    IF upper(btrim(p_base_unit_code)) <> upper(btrim(p_sale_unit_code))
       AND NOT EXISTS (
         SELECT 1 FROM public.commercial_catalog_product_units
         WHERE product_id = v_global_id AND unit_code = upper(btrim(p_sale_unit_code))
           AND unit_role = 'sale' AND quantity_in_base = v_sale_factor AND is_active
       ) THEN RAISE EXCEPTION 'existing_catalog_item_sale_unit_mismatch' USING ERRCODE = '22023'; END IF;
  ELSE
    v_product := jsonb_build_object(
      'name_ar', btrim(p_name_ar), 'name_en', btrim(p_name_en),
      'category_code', upper(btrim(p_category_code)),
      'unit_code', upper(btrim(p_base_unit_code)),
      'item_type', p_item_type, 'currency_code', v_currency,
      'catalog_code', '', 'description_ar', '', 'description_en', ''
    );
    v_units := jsonb_build_array(jsonb_build_object(
      'unit_code', upper(btrim(p_base_unit_code)), 'unit_role', 'base',
      'quantity_in_base', 1, 'is_default', true
    ));
    IF upper(btrim(p_base_unit_code)) <> upper(btrim(p_sale_unit_code)) THEN
      v_units := v_units || jsonb_build_array(jsonb_build_object(
        'unit_code', upper(btrim(p_sale_unit_code)), 'unit_role', 'sale',
        'quantity_in_base', v_sale_factor, 'is_default', false
      ));
    END IF;
    v_product_result := public.commercial_catalog_create_product(
      p_organization_id, v_product, v_units, ARRAY[]::text[],
      '[]'::jsonb, '[]'::jsonb, '[]'::jsonb
    );
    v_global_id := (v_product_result->>'product_id')::uuid;
  END IF;

  PERFORM public.commercial_catalog_configure_items(
    p_organization_id, upper(btrim(p_activity_node_code)),
    v_order.receive_location_id, p_warehouse_id,
    jsonb_build_array(jsonb_build_object(
      'global_item_id', v_global_id,
      'base_unit_code', upper(btrim(p_base_unit_code)),
      'sale_unit_code', upper(btrim(p_sale_unit_code))
    ))
  );

  v_offering_result := public.commercial_create_inventory_item_with_category_and_unit(
    p_organization_id, p_activity_id, v_order.receive_location_id,
    btrim(p_name_ar), v_unit_name, 0, v_currency, 0, 0,
    p_local_category_id, lower(btrim(p_base_unit_code))
  );
  v_offering_id := (v_offering_result->>'offering_id')::uuid;

  PERFORM public.commercial_catalog_bind_existing_offering(
    p_organization_id, v_global_id, v_offering_id, v_order.receive_location_id
  );

  UPDATE public.commercial_organization_catalog_items
  SET is_available = false, price_minor = 0, currency_code = v_currency,
      pricing_status = 'unpriced',
      metadata = coalesce(metadata, '{}'::jsonb)
        || jsonb_build_object('linked_without_pricing', true, 'pricing_required', true, 'source', 'invoice_purchase')
  WHERE organization_id = p_organization_id AND global_item_id = v_global_id;

  v_link_result := public.commercial_link_purchase_order_item_offering(
    p_organization_id, p_purchase_order_id, p_purchase_order_item_id, v_offering_id
  );

  v_response := jsonb_build_object(
    'organization_id', p_organization_id, 'purchase_order_id', p_purchase_order_id,
    'purchase_order_item_id', p_purchase_order_item_id, 'global_item_id', v_global_id,
    'offering_id', v_offering_id, 'location_id', v_order.receive_location_id,
    'warehouse_id', p_warehouse_id, 'currency_code', v_currency,
    'status', v_link_result->>'status',
    'reapproval_required', (v_order.status = 'approved'),
    'pricing_required', true, 'price_minor', 0, 'initial_quantity', 0, 'stock_created', false
  );

  INSERT INTO public.commercial_invoice_purchase_item_creation_requests(
    organization_id, idempotency_key, request_fingerprint, purchase_order_id,
    purchase_order_item_id, global_item_id, offering_id, response, created_by
  ) VALUES (
    p_organization_id, btrim(p_idempotency_key), v_fp, p_purchase_order_id,
    p_purchase_order_item_id, v_global_id, v_offering_id, v_response, v_uid
  );

  INSERT INTO public.commercial_audit_events(
    organization_id, actor_user_id, action, entity_type, entity_id, metadata
  ) VALUES (
    p_organization_id, v_uid, 'purchase_order_item.catalog_item_created_and_linked',
    'purchase_order_item', p_purchase_order_item_id::text,
    jsonb_build_object('purchase_order_id', p_purchase_order_id,
      'global_item_id', v_global_id, 'offering_id', v_offering_id,
      'location_id', v_order.receive_location_id, 'warehouse_id', p_warehouse_id,
      'currency_code', v_currency, 'price_minor', 0, 'initial_quantity', 0, 'stock_created', false)
  );

  RETURN v_response;
END;
$$;

-- Return the approval transition explicitly so clients do not infer it from
-- the final status (which can already have been 'submitted' before linking).
CREATE OR REPLACE FUNCTION commercial_private.commercial_link_purchase_order_item_offering(
  p_organization_id uuid,
  p_purchase_order_id uuid,
  p_purchase_order_item_id uuid,
  p_offering_id uuid
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, commercial_private
AS $
DECLARE
  v_uid uuid := auth.uid();
  v_status text;
  v_currency text;
  v_existing_offering uuid;
  v_received numeric;
  v_reapproval_required boolean := false;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR p_purchase_order_id IS NULL
     OR p_purchase_order_item_id IS NULL OR p_offering_id IS NULL THEN
    RAISE EXCEPTION 'invalid_purchase_line_offering_link' USING ERRCODE='22023';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=p_organization_id AND m.user_id=v_uid
      AND m.state='active' AND m.role IN ('owner','admin','manager')
  ) THEN RAISE EXCEPTION 'purchase_management_permission_required' USING ERRCODE='42501'; END IF;

  SELECT status,currency_code INTO v_status,v_currency
  FROM public.commercial_purchase_orders
  WHERE id=p_purchase_order_id AND organization_id=p_organization_id
  FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'purchase_order_not_found' USING ERRCODE='P0002'; END IF;
  IF v_status NOT IN ('draft','submitted','approved') THEN
    RAISE EXCEPTION 'purchase_line_offering_link_state_forbidden' USING ERRCODE='55000';
  END IF;

  SELECT offering_id,quantity_received INTO v_existing_offering,v_received
  FROM public.commercial_purchase_order_items
  WHERE id=p_purchase_order_item_id AND organization_id=p_organization_id
    AND purchase_order_id=p_purchase_order_id
  FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'purchase_order_item_not_found' USING ERRCODE='P0002'; END IF;
  IF v_existing_offering IS NOT NULL THEN
    IF v_existing_offering=p_offering_id THEN
      RETURN jsonb_build_object(
        'purchase_order_item_id',p_purchase_order_item_id,
        'offering_id',p_offering_id,'idempotent_replay',true,
        'status',v_status,'reapproval_required',false
      );
    END IF;
    RAISE EXCEPTION 'purchase_line_offering_already_linked' USING ERRCODE='55000';
  END IF;
  IF coalesce(v_received,0)>0 OR EXISTS (
    SELECT 1 FROM public.commercial_supplier_invoices
    WHERE organization_id=p_organization_id AND purchase_order_id=p_purchase_order_id
  ) THEN
    RAISE EXCEPTION 'purchase_line_offering_link_after_receipt_or_invoice_forbidden' USING ERRCODE='55000';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_offerings o
    JOIN public.commercial_activities a ON a.id=o.activity_id
    JOIN public.commercial_products p ON p.id=o.product_id
    WHERE o.id=p_offering_id AND a.organization_id=p_organization_id
      AND a.state='active' AND o.state='active' AND p.state='active'
      AND upper(o.currency_code)=v_currency
  ) THEN RAISE EXCEPTION 'purchase_offering_currency_or_scope_mismatch' USING ERRCODE='22023'; END IF;

  UPDATE public.commercial_purchase_order_items
  SET offering_id=p_offering_id,updated_at=now()
  WHERE id=p_purchase_order_item_id AND organization_id=p_organization_id
    AND purchase_order_id=p_purchase_order_id;

  IF v_status='approved' THEN
    v_reapproval_required:=true;
    UPDATE public.commercial_purchase_orders
    SET status='submitted',approved_by=NULL,approved_at=NULL,updated_at=now()
    WHERE id=p_purchase_order_id AND organization_id=p_organization_id;
    v_status:='submitted';
  END IF;

  INSERT INTO public.commercial_audit_events(
    organization_id,actor_user_id,action,entity_type,entity_id,metadata
  ) VALUES (
    p_organization_id,v_uid,'purchase_order_item.offering_linked',
    'purchase_order_item',p_purchase_order_item_id::text,
    jsonb_build_object('purchase_order_id',p_purchase_order_id,
      'offering_id',p_offering_id,'reapproval_required',v_reapproval_required)
  );

  RETURN jsonb_build_object(
    'purchase_order_item_id',p_purchase_order_item_id,
    'offering_id',p_offering_id,'idempotent_replay',false,
    'status',v_status,'reapproval_required',v_reapproval_required
  );
END;
$;

REVOKE ALL ON FUNCTION public.commercial_create_and_link_invoice_purchase_item(
  uuid, uuid, uuid, uuid, text, uuid, text, text, text, text, text, text, uuid, text
) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_create_and_link_invoice_purchase_item(
  uuid, uuid, uuid, uuid, text, uuid, text, text, text, text, text, text, uuid, text
) TO authenticated;

COMMENT ON FUNCTION public.commercial_create_and_link_invoice_purchase_item(
  uuid, uuid, uuid, uuid, text, uuid, text, text, text, text, text, text, uuid, text
) IS
'Atomically creates or reuses an organization-owned catalog item, configures it for the purchase receiving warehouse, creates an unpriced zero-stock offering, and links an unreceived invoice purchase-order line. Does not receive stock or invent prices.';

COMMIT;
 THEN
    RAISE EXCEPTION 'purchase_order_currency_invalid' USING ERRCODE = '22023';
  END IF;
  IF v_order.receive_location_id IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_locations l
    WHERE l.id = v_order.receive_location_id AND l.organization_id = p_organization_id AND l.state = 'active'
  ) THEN RAISE EXCEPTION 'purchase_order_receiving_location_invalid' USING ERRCODE = '22023'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_warehouses w
    WHERE w.id = p_warehouse_id AND w.organization_id = p_organization_id
      AND w.branch_location_id = v_order.receive_location_id AND w.state = 'active'
  ) THEN RAISE EXCEPTION 'warehouse_not_in_purchase_receiving_branch' USING ERRCODE = '22023'; END IF;

  SELECT * INTO v_line
  FROM public.commercial_purchase_order_items
  WHERE id = p_purchase_order_item_id AND organization_id = p_organization_id
    AND purchase_order_id = p_purchase_order_id
  FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'purchase_order_item_not_found' USING ERRCODE = 'P0002'; END IF;
  IF v_line.offering_id IS NOT NULL THEN RAISE EXCEPTION 'purchase_line_offering_already_linked' USING ERRCODE = '55000'; END IF;
  IF coalesce(v_line.quantity_received, 0) > 0
     OR EXISTS (SELECT 1 FROM public.commercial_supplier_invoices
       WHERE organization_id = p_organization_id AND purchase_order_id = p_purchase_order_id)
     OR EXISTS (SELECT 1 FROM public.commercial_purchase_receipts
       WHERE organization_id = p_organization_id AND purchase_order_id = p_purchase_order_id) THEN
    RAISE EXCEPTION 'purchase_line_item_creation_after_receipt_or_invoice_forbidden' USING ERRCODE = '55000';
  END IF;
  IF v_line.source_selling_unit_code IS NOT NULL
     AND upper(v_line.source_selling_unit_code) <> upper(btrim(p_sale_unit_code)) THEN
    RAISE EXCEPTION 'purchase_line_unit_mismatch_requires_unit_review' USING ERRCODE = '22023';
  END IF;

  SELECT id INTO v_node
  FROM public.commercial_global_business_activity_nodes
  WHERE code = upper(btrim(p_activity_node_code)) AND status = 'active' AND is_selectable;
  IF v_node IS NULL THEN RAISE EXCEPTION 'active_activity_not_found' USING ERRCODE = '22023'; END IF;

  SELECT business_activity_node_id INTO v_activity_node
  FROM public.commercial_activities
  WHERE id = p_activity_id AND organization_id = p_organization_id
    AND state = 'active' AND deleted_at IS NULL;
  IF NOT FOUND OR v_activity_node IS DISTINCT FROM v_node THEN
    RAISE EXCEPTION 'activity_node_does_not_match_selected_activity' USING ERRCODE = '22023';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_global_catalog_categories c
    WHERE c.code = upper(btrim(p_category_code)) AND c.is_active
      AND (c.owner_organization_id IS NULL OR c.owner_organization_id = p_organization_id)
  ) THEN RAISE EXCEPTION 'active_global_catalog_category_not_found' USING ERRCODE = '22023'; END IF;

  SELECT dimension INTO v_base_dimension
  FROM public.commercial_global_units
  WHERE code = upper(btrim(p_base_unit_code)) AND is_active
    AND (owner_organization_id IS NULL OR owner_organization_id = p_organization_id)
    AND (base_unit_code IS NULL OR btrim(base_unit_code) = '');
  IF v_base_dimension IS NULL THEN RAISE EXCEPTION 'active_base_unit_not_found' USING ERRCODE = '22023'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_unit_of_measure
    WHERE code = upper(btrim(p_base_unit_code)) AND is_active
  ) THEN RAISE EXCEPTION 'active_unit_of_measure_not_found' USING ERRCODE = '22023'; END IF;

  IF upper(btrim(p_base_unit_code)) = upper(btrim(p_sale_unit_code)) THEN
    v_sale_factor := 1;
  ELSE
    SELECT factor_to_base INTO v_sale_factor
    FROM public.commercial_global_units
    WHERE code = upper(btrim(p_sale_unit_code))
      AND base_unit_code = upper(btrim(p_base_unit_code))
      AND dimension = v_base_dimension
      AND is_active
      AND (owner_organization_id IS NULL OR owner_organization_id = p_organization_id);
    IF v_sale_factor IS NULL OR v_sale_factor <= 0 THEN
      RAISE EXCEPTION 'sale_unit_not_compatible_with_base_unit' USING ERRCODE = '22023';
    END IF;
  END IF;

  IF v_line.source_selling_quantity IS NOT NULL
     AND v_line.source_quantity_in_stock_units IS NOT NULL
     AND abs(v_line.source_quantity_in_stock_units
       - (v_line.source_selling_quantity * v_sale_factor)) > 0.000001 THEN
    RAISE EXCEPTION 'purchase_line_unit_conversion_mismatch_requires_review' USING ERRCODE = '22023';
  END IF;

  IF p_local_category_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM public.commercial_product_categories c
    WHERE c.id = p_local_category_id AND c.organization_id = p_organization_id AND c.state = 'active'
  ) THEN RAISE EXCEPTION 'active_category_not_found_in_organization' USING ERRCODE = '22023'; END IF;

  SELECT display_name INTO v_unit_name
  FROM public.commercial_unit_of_measure
  WHERE code = upper(btrim(p_base_unit_code)) AND is_active;
  v_currency := upper(v_order.currency_code);

  SELECT array_agg(id), count(*)::integer INTO v_existing_ids, v_existing_count
  FROM public.commercial_global_catalog_items
  WHERE owner_organization_id = p_organization_id AND is_active
    AND lower(btrim(name_ar)) = lower(btrim(p_name_ar))
    AND lower(btrim(name_en)) = lower(btrim(p_name_en))
    AND upper(category_code) = upper(btrim(p_category_code))
    AND upper(unit_code) = upper(btrim(p_base_unit_code))
    AND item_type = p_item_type;
  IF v_existing_count > 1 THEN
    RAISE EXCEPTION 'multiple_exact_catalog_items_require_manual_resolution' USING ERRCODE = '23505';
  ELSIF v_existing_count = 1 THEN
    v_global_id := v_existing_ids[1];
    IF NOT EXISTS (
      SELECT 1 FROM public.commercial_catalog_product_units
      WHERE product_id = v_global_id AND unit_code = upper(btrim(p_base_unit_code))
        AND unit_role = 'base' AND quantity_in_base = 1 AND is_active
    ) THEN RAISE EXCEPTION 'existing_catalog_item_base_unit_mismatch' USING ERRCODE = '22023'; END IF;
    IF upper(btrim(p_base_unit_code)) <> upper(btrim(p_sale_unit_code))
       AND NOT EXISTS (
         SELECT 1 FROM public.commercial_catalog_product_units
         WHERE product_id = v_global_id AND unit_code = upper(btrim(p_sale_unit_code))
           AND unit_role = 'sale' AND quantity_in_base = v_sale_factor AND is_active
       ) THEN RAISE EXCEPTION 'existing_catalog_item_sale_unit_mismatch' USING ERRCODE = '22023'; END IF;
  ELSE
    v_product := jsonb_build_object(
      'name_ar', btrim(p_name_ar), 'name_en', btrim(p_name_en),
      'category_code', upper(btrim(p_category_code)),
      'unit_code', upper(btrim(p_base_unit_code)),
      'item_type', p_item_type, 'currency_code', v_currency,
      'catalog_code', '', 'description_ar', '', 'description_en', ''
    );
    v_units := jsonb_build_array(jsonb_build_object(
      'unit_code', upper(btrim(p_base_unit_code)), 'unit_role', 'base',
      'quantity_in_base', 1, 'is_default', true
    ));
    IF upper(btrim(p_base_unit_code)) <> upper(btrim(p_sale_unit_code)) THEN
      v_units := v_units || jsonb_build_array(jsonb_build_object(
        'unit_code', upper(btrim(p_sale_unit_code)), 'unit_role', 'sale',
        'quantity_in_base', v_sale_factor, 'is_default', false
      ));
    END IF;
    v_product_result := public.commercial_catalog_create_product(
      p_organization_id, v_product, v_units, ARRAY[]::text[],
      '[]'::jsonb, '[]'::jsonb, '[]'::jsonb
    );
    v_global_id := (v_product_result->>'product_id')::uuid;
  END IF;

  PERFORM public.commercial_catalog_configure_items(
    p_organization_id, upper(btrim(p_activity_node_code)),
    v_order.receive_location_id, p_warehouse_id,
    jsonb_build_array(jsonb_build_object(
      'global_item_id', v_global_id,
      'base_unit_code', upper(btrim(p_base_unit_code)),
      'sale_unit_code', upper(btrim(p_sale_unit_code))
    ))
  );

  v_offering_result := public.commercial_create_inventory_item_with_category_and_unit(
    p_organization_id, p_activity_id, v_order.receive_location_id,
    btrim(p_name_ar), v_unit_name, 0, v_currency, 0, 0,
    p_local_category_id, upper(btrim(p_base_unit_code))
  );
  v_offering_id := (v_offering_result->>'offering_id')::uuid;

  PERFORM public.commercial_catalog_bind_existing_offering(
    p_organization_id, v_global_id, v_offering_id, v_order.receive_location_id
  );

  UPDATE public.commercial_organization_catalog_items
  SET is_available = false, price_minor = 0, currency_code = v_currency,
      pricing_status = 'unpriced',
      metadata = coalesce(metadata, '{}'::jsonb)
        || jsonb_build_object('linked_without_pricing', true, 'pricing_required', true, 'source', 'invoice_purchase')
  WHERE organization_id = p_organization_id AND global_item_id = v_global_id;

  v_link_result := public.commercial_link_purchase_order_item_offering(
    p_organization_id, p_purchase_order_id, p_purchase_order_item_id, v_offering_id
  );

  v_response := jsonb_build_object(
    'organization_id', p_organization_id, 'purchase_order_id', p_purchase_order_id,
    'purchase_order_item_id', p_purchase_order_item_id, 'global_item_id', v_global_id,
    'offering_id', v_offering_id, 'location_id', v_order.receive_location_id,
    'warehouse_id', p_warehouse_id, 'currency_code', v_currency,
    'status', v_link_result->>'status',
    'reapproval_required', coalesce((v_link_result->>'status') = 'submitted', false),
    'pricing_required', true, 'price_minor', 0, 'initial_quantity', 0, 'stock_created', false
  );

  INSERT INTO public.commercial_invoice_purchase_item_creation_requests(
    organization_id, idempotency_key, request_fingerprint, purchase_order_id,
    purchase_order_item_id, global_item_id, offering_id, response, created_by
  ) VALUES (
    p_organization_id, btrim(p_idempotency_key), v_fp, p_purchase_order_id,
    p_purchase_order_item_id, v_global_id, v_offering_id, v_response, v_uid
  );

  INSERT INTO public.commercial_audit_events(
    organization_id, actor_user_id, action, entity_type, entity_id, metadata
  ) VALUES (
    p_organization_id, v_uid, 'purchase_order_item.catalog_item_created_and_linked',
    'purchase_order_item', p_purchase_order_item_id::text,
    jsonb_build_object('purchase_order_id', p_purchase_order_id,
      'global_item_id', v_global_id, 'offering_id', v_offering_id,
      'location_id', v_order.receive_location_id, 'warehouse_id', p_warehouse_id,
      'currency_code', v_currency, 'price_minor', 0, 'initial_quantity', 0, 'stock_created', false)
  );

  RETURN v_response;
END;
$$;

REVOKE ALL ON FUNCTION public.commercial_create_and_link_invoice_purchase_item(
  uuid, uuid, uuid, uuid, text, uuid, text, text, text, text, text, text, uuid, text
) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_create_and_link_invoice_purchase_item(
  uuid, uuid, uuid, uuid, text, uuid, text, text, text, text, text, text, uuid, text
) TO authenticated;

COMMENT ON FUNCTION public.commercial_create_and_link_invoice_purchase_item(
  uuid, uuid, uuid, uuid, text, uuid, text, text, text, text, text, text, uuid, text
) IS
'Atomically creates or reuses an organization-owned catalog item, configures it for the purchase receiving warehouse, creates an unpriced zero-stock offering, and links an unreceived invoice purchase-order line. Does not receive stock or invent prices.';

COMMIT;
