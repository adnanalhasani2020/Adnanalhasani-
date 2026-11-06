BEGIN;

-- Physical lot metadata lives on the existing cost layer: no parallel stock ledger.
ALTER TABLE public.commercial_inventory_cost_layers
  ADD COLUMN IF NOT EXISTS batch_number text,
  ADD COLUMN IF NOT EXISTS expires_on date,
  ADD COLUMN IF NOT EXISTS stock_condition text NOT NULL DEFAULT 'sellable'
    CHECK (stock_condition IN ('sellable','quarantine','damaged','expired'));
ALTER TABLE public.commercial_purchase_receipt_items
  ADD COLUMN IF NOT EXISTS batch_number text,
  ADD COLUMN IF NOT EXISTS expires_on date;

CREATE INDEX IF NOT EXISTS commercial_inventory_cost_layers_fefo_idx
  ON public.commercial_inventory_cost_layers(organization_id,location_id,offering_id,expires_on,received_at,id)
  WHERE quantity_remaining>0 AND stock_condition='sellable';

ALTER TABLE public.commercial_inventory_cost_layers
  ADD CONSTRAINT commercial_inventory_cost_layers_batch_number_check
  CHECK (batch_number IS NULL OR length(btrim(batch_number)) BETWEEN 1 AND 100);
ALTER TABLE public.commercial_purchase_receipt_items
  ADD CONSTRAINT commercial_purchase_receipt_items_batch_number_check
  CHECK (batch_number IS NULL OR length(btrim(batch_number)) BETWEEN 1 AND 100);

CREATE TABLE IF NOT EXISTS public.commercial_inventory_rotation_policies (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  location_id uuid REFERENCES public.commercial_organization_locations(id) ON DELETE RESTRICT,
  rotation_method text NOT NULL DEFAULT 'fefo' CHECK (rotation_method IN ('fifo','fefo')),
  created_by uuid REFERENCES auth.users(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS commercial_inventory_rotation_policy_org_location_uq
  ON public.commercial_inventory_rotation_policies(organization_id,location_id)
  WHERE location_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS commercial_inventory_rotation_policy_org_default_uq
  ON public.commercial_inventory_rotation_policies(organization_id)
  WHERE location_id IS NULL;

CREATE OR REPLACE FUNCTION commercial_private.commercial_validate_inventory_rotation_policy_scope()
RETURNS trigger LANGUAGE plpgsql SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
BEGIN
  IF NEW.location_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_locations l
    WHERE l.id=NEW.location_id AND l.organization_id=NEW.organization_id
  ) THEN RAISE EXCEPTION 'inventory_rotation_policy_location_scope_mismatch' USING ERRCODE='23514'; END IF;
  RETURN NEW;
END;
$function$;
DROP TRIGGER IF EXISTS commercial_inventory_rotation_policy_scope_trg ON public.commercial_inventory_rotation_policies;
CREATE TRIGGER commercial_inventory_rotation_policy_scope_trg
BEFORE INSERT OR UPDATE OF organization_id,location_id
ON public.commercial_inventory_rotation_policies
FOR EACH ROW EXECUTE FUNCTION commercial_private.commercial_validate_inventory_rotation_policy_scope();

ALTER TABLE public.commercial_inventory_rotation_policies ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS commercial_inventory_rotation_policy_member_read ON public.commercial_inventory_rotation_policies;
CREATE POLICY commercial_inventory_rotation_policy_member_read
ON public.commercial_inventory_rotation_policies FOR SELECT TO authenticated
USING (EXISTS (
  SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=commercial_inventory_rotation_policies.organization_id
    AND m.user_id=(SELECT auth.uid()) AND m.state='active'
));
REVOKE ALL ON public.commercial_inventory_rotation_policies FROM anon,authenticated;
GRANT SELECT ON public.commercial_inventory_rotation_policies TO authenticated;
-- Policy writes are performed only through the manager-gated RPC below.
CREATE OR REPLACE FUNCTION public.commercial_set_inventory_rotation_policy(
  p_organization_id uuid,p_location_id uuid,p_rotation_method text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_row public.commercial_inventory_rotation_policies%ROWTYPE;
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR p_rotation_method NOT IN ('fifo','fefo') THEN
    RAISE EXCEPTION 'invalid_inventory_rotation_policy' USING ERRCODE='22023';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=p_organization_id AND m.user_id=auth.uid()
      AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN
    RAISE EXCEPTION 'inventory_management_permission_required' USING ERRCODE='42501';
  END IF;
  IF p_location_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_locations l
    WHERE l.id=p_location_id AND l.organization_id=p_organization_id AND l.state='active'
  ) THEN RAISE EXCEPTION 'inventory_rotation_location_invalid' USING ERRCODE='22023'; END IF;
  INSERT INTO public.commercial_inventory_rotation_policies(organization_id,location_id,rotation_method,created_by,updated_at)
  VALUES(p_organization_id,p_location_id,p_rotation_method,auth.uid(),now())
  ON CONFLICT DO NOTHING;
  UPDATE public.commercial_inventory_rotation_policies
  SET rotation_method=p_rotation_method,updated_at=now()
  WHERE organization_id=p_organization_id AND location_id IS NOT DISTINCT FROM p_location_id
  RETURNING * INTO v_row;
  RETURN jsonb_build_object('id',v_row.id,'rotation_method',v_row.rotation_method,'location_id',v_row.location_id);
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_set_inventory_rotation_policy(uuid,uuid,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_set_inventory_rotation_policy(uuid,uuid,text) TO authenticated;

-- Receipt line metadata is copied to the matching cost layer inside the same
-- purchase-receiving transaction. Existing callers that omit metadata remain valid.
CREATE OR REPLACE FUNCTION commercial_private.commercial_sync_receipt_batch_metadata()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
BEGIN
  UPDATE public.commercial_inventory_cost_layers
  SET batch_number=NEW.batch_number,expires_on=NEW.expires_on,
      stock_condition=CASE WHEN NEW.expires_on IS NOT NULL AND NEW.expires_on<current_date THEN 'expired' ELSE 'sellable' END
  WHERE inventory_receipt_id=NEW.inventory_receipt_id
    AND organization_id=NEW.organization_id
    AND location_id=(SELECT r.location_id FROM public.commercial_purchase_receipts r WHERE r.id=NEW.receipt_id)
    AND offering_id=NEW.offering_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'receipt_cost_layer_missing_for_batch_metadata' USING ERRCODE='55000'; END IF;
  RETURN NEW;
END;
$function$;
DROP TRIGGER IF EXISTS commercial_purchase_receipt_batch_metadata_trg ON public.commercial_purchase_receipt_items;
CREATE TRIGGER commercial_purchase_receipt_batch_metadata_trg
AFTER INSERT ON public.commercial_purchase_receipt_items
FOR EACH ROW EXECUTE FUNCTION commercial_private.commercial_sync_receipt_batch_metadata();

-- Replace the active integer-quantity receiving function, preserving its
-- integer boundary, authorization, idempotency, and atomic posting semantics.
CREATE OR REPLACE FUNCTION commercial_private.commercial_receive_purchase_order(
  p_organization_id uuid,p_purchase_order_id uuid,p_location_id uuid,p_lines jsonb,p_idempotency_key text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
 v_uid uuid:=auth.uid(); v_status text; v_default_location uuid; v_receipt_id uuid; v_existing_fingerprint text;
 v_fingerprint text; v_line jsonb; v_item_id uuid; v_qty numeric; v_ordered numeric; v_received numeric;
 v_offering uuid; v_cost bigint; v_item_count integer:=0; v_new_status text; v_inventory jsonb; v_inv_receipt uuid;
 v_batch_number text; v_expires_on date;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_organization_id IS NULL OR p_purchase_order_id IS NULL OR p_location_id IS NULL OR p_lines IS NULL
   OR jsonb_typeof(p_lines)<>'array' OR jsonb_array_length(p_lines)=0 OR p_idempotency_key IS NULL
   OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN RAISE EXCEPTION 'invalid_purchase_receipt_request' USING ERRCODE='22023'; END IF;
 IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN
   RAISE EXCEPTION 'purchase_management_permission_required' USING ERRCODE='42501';
 END IF;
 PERFORM pg_advisory_xact_lock(hashtext(p_organization_id::text),hashtext('purchase-receive:'||btrim(p_idempotency_key)));
 v_fingerprint:=md5(jsonb_build_object('purchase_order_id',p_purchase_order_id,'location_id',p_location_id,'lines',p_lines)::text);
 SELECT id,request_fingerprint INTO v_receipt_id,v_existing_fingerprint FROM public.commercial_purchase_receipts
 WHERE organization_id=p_organization_id AND idempotency_key=btrim(p_idempotency_key);
 IF FOUND THEN
   IF v_existing_fingerprint IS DISTINCT FROM v_fingerprint THEN RAISE EXCEPTION 'purchase_receipt_key_reused_with_different_request' USING ERRCODE='23505'; END IF;
   RETURN jsonb_build_object('receipt_id',v_receipt_id,'idempotent_replay',true);
 END IF;
 SELECT status,receive_location_id INTO v_status,v_default_location FROM public.commercial_purchase_orders
 WHERE id=p_purchase_order_id AND organization_id=p_organization_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'purchase_order_not_found' USING ERRCODE='P0002'; END IF;
 IF v_status NOT IN ('approved','partially_received') THEN RAISE EXCEPTION 'purchase_order_not_receivable' USING ERRCODE='55000'; END IF;
 IF p_location_id IS DISTINCT FROM v_default_location OR NOT EXISTS (
   SELECT 1 FROM public.commercial_organization_locations WHERE id=p_location_id AND organization_id=p_organization_id AND state='active'
 ) THEN RAISE EXCEPTION 'purchase_receipt_location_mismatch' USING ERRCODE='22023'; END IF;
 INSERT INTO public.commercial_purchase_receipts(organization_id,purchase_order_id,location_id,idempotency_key,request_fingerprint,received_by)
 VALUES(p_organization_id,p_purchase_order_id,p_location_id,btrim(p_idempotency_key),v_fingerprint,v_uid)
 RETURNING id INTO v_receipt_id;
 FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines) LOOP
   BEGIN
     v_item_id:=(v_line->>'purchase_order_item_id')::uuid;
     v_qty:=(v_line->>'quantity')::numeric;
     v_batch_number:=nullif(btrim(v_line->>'batch_number'),'');
     v_expires_on:=nullif(btrim(v_line->>'expires_on'),'')::date;
   EXCEPTION WHEN others THEN RAISE EXCEPTION 'invalid_purchase_receipt_line' USING ERRCODE='22023'; END;
   IF v_item_id IS NULL OR v_qty IS NULL OR v_qty<=0 OR v_qty<>trunc(v_qty) OR v_qty>999999999999
     OR (v_batch_number IS NOT NULL AND length(v_batch_number)>100) THEN
     RAISE EXCEPTION 'invalid_purchase_receipt_line' USING ERRCODE='22023';
   END IF;
   IF EXISTS (SELECT 1 FROM public.commercial_purchase_receipt_items WHERE receipt_id=v_receipt_id AND purchase_order_item_id=v_item_id) THEN
     RAISE EXCEPTION 'duplicate_purchase_receipt_line' USING ERRCODE='22023';
   END IF;
   SELECT quantity_ordered,quantity_received,offering_id,unit_cost_minor INTO v_ordered,v_received,v_offering,v_cost
   FROM public.commercial_purchase_order_items
   WHERE id=v_item_id AND organization_id=p_organization_id AND purchase_order_id=p_purchase_order_id FOR UPDATE;
   IF NOT FOUND THEN RAISE EXCEPTION 'purchase_receipt_line_not_in_order' USING ERRCODE='22023'; END IF;
   IF v_offering IS NULL THEN RAISE EXCEPTION 'purchase_line_missing_inventory_offering' USING ERRCODE='55000'; END IF;
   IF v_received+v_qty>v_ordered THEN RAISE EXCEPTION 'purchase_receipt_exceeds_remaining_quantity' USING ERRCODE='22023'; END IF;
   INSERT INTO public.commercial_inventory_balances(organization_id,location_id,offering_id,quantity_on_hand,quantity_reserved,reorder_point,unit_cost_minor)
   VALUES(p_organization_id,p_location_id,v_offering,0,0,0,NULL) ON CONFLICT (organization_id,location_id,offering_id) DO NOTHING;
   v_inventory:=public.commercial_receive_inventory_stock(p_organization_id,p_location_id,v_offering,v_qty::numeric,v_cost,
     'purchase-receipt:'||v_receipt_id::text||':'||v_item_id::text);
   v_inv_receipt:=(v_inventory->>'receipt_id')::uuid;
   UPDATE public.commercial_purchase_order_items SET quantity_received=quantity_received+v_qty,updated_at=now()
   WHERE id=v_item_id AND purchase_order_id=p_purchase_order_id AND organization_id=p_organization_id;
   INSERT INTO public.commercial_purchase_receipt_items(
     organization_id,receipt_id,purchase_order_id,purchase_order_item_id,offering_id,quantity,unit_cost_minor,inventory_receipt_id,batch_number,expires_on
   ) VALUES(p_organization_id,v_receipt_id,p_purchase_order_id,v_item_id,v_offering,v_qty,v_cost,v_inv_receipt,v_batch_number,v_expires_on);
   v_item_count:=v_item_count+1;
 END LOOP;
 SELECT CASE WHEN bool_and(quantity_received=quantity_ordered) THEN 'received' ELSE 'partially_received' END INTO v_new_status
 FROM public.commercial_purchase_order_items WHERE purchase_order_id=p_purchase_order_id AND organization_id=p_organization_id;
 UPDATE public.commercial_purchase_orders SET status=v_new_status,updated_at=now()
 WHERE id=p_purchase_order_id AND organization_id=p_organization_id;
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(p_organization_id,v_uid,'purchase_order.received','purchase_receipt',v_receipt_id::text,
   jsonb_build_object('purchase_order_id',p_purchase_order_id,'location_id',p_location_id,'line_count',v_item_count,'new_order_status',v_new_status));
 RETURN jsonb_build_object('receipt_id',v_receipt_id,'purchase_order_id',p_purchase_order_id,'status',v_new_status,'line_count',v_item_count,'idempotent_replay',false);
END;
$function$;


-- Member-safe batch projection. It intentionally omits acquisition costs so
-- cashiers can inspect lot availability without granting direct cost-layer reads.
CREATE OR REPLACE FUNCTION public.commercial_list_inventory_batches(
  p_organization_id uuid,p_location_id uuid,p_activity_id uuid
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_result jsonb;
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR p_location_id IS NULL OR p_activity_id IS NULL THEN
    RAISE EXCEPTION 'inventory_batch_scope_required' USING ERRCODE='22023';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=p_organization_id AND m.user_id=auth.uid() AND m.state='active'
  ) THEN RAISE EXCEPTION 'organization_access_denied' USING ERRCODE='42501'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_locations l
    WHERE l.id=p_location_id AND l.organization_id=p_organization_id AND l.state='active'
  ) OR NOT EXISTS (
    SELECT 1 FROM public.commercial_activities a
    WHERE a.id=p_activity_id AND a.organization_id=p_organization_id AND a.deleted_at IS NULL
  ) THEN RAISE EXCEPTION 'inventory_batch_scope_mismatch' USING ERRCODE='42501'; END IF;
  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'id',l.id,'offering_id',l.offering_id,'product_name',p.name,
    'batch_number',l.batch_number,'received_at',l.received_at,'expires_on',l.expires_on,
    'quantity_remaining',l.quantity_remaining,'stock_condition',
      CASE WHEN l.expires_on IS NOT NULL AND l.expires_on<current_date THEN 'expired' ELSE l.stock_condition END
  ) ORDER BY l.expires_on ASC NULLS LAST,l.received_at ASC,l.id ASC),'[]'::jsonb)
  INTO v_result
  FROM public.commercial_inventory_cost_layers l
  JOIN public.commercial_offerings o ON o.id=l.offering_id AND o.activity_id=p_activity_id
  JOIN public.commercial_products p ON p.id=o.product_id
  WHERE l.organization_id=p_organization_id AND l.location_id=p_location_id
    AND l.quantity_remaining>0;
  RETURN v_result;
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_list_inventory_batches(uuid,uuid,uuid) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_list_inventory_batches(uuid,uuid,uuid) TO authenticated;

COMMIT;
