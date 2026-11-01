BEGIN;

-- Receipts are inbound stock events, not generic adjustments. Preserve the
-- existing balance and receipt records; correct only the ledger classification.
UPDATE public.commercial_stock_movements
SET movement_type='receipt'
WHERE movement_type='adjustment'
  AND source_ref LIKE 'receipt:%';

CREATE OR REPLACE FUNCTION public.commercial_receive_inventory_stock(
 p_organization_id uuid, p_location_id uuid, p_offering_id uuid, p_quantity numeric,
 p_unit_cost_minor bigint, p_source_ref text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
 b public.commercial_inventory_balances%ROWTYPE;
 v_new_qty numeric;
 v_new_cost numeric;
 v_fingerprint text;
 v_receipt public.commercial_inventory_receipts%ROWTYPE;
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_organization_id IS NULL OR p_location_id IS NULL OR p_offering_id IS NULL
    OR p_quantity IS NULL OR p_quantity<=0 OR p_quantity<>trunc(p_quantity)
    OR p_unit_cost_minor IS NULL OR p_unit_cost_minor<0
    OR p_source_ref IS NULL OR length(btrim(p_source_ref))<8 OR length(btrim(p_source_ref))>200 THEN
   RAISE EXCEPTION 'invalid_inventory_receipt_integer_quantity' USING ERRCODE='22023';
 END IF;
 IF NOT EXISTS(
   SELECT 1 FROM public.commercial_organization_members m
   WHERE m.organization_id=p_organization_id AND m.user_id=auth.uid()
     AND m.state='active' AND m.role IN ('owner','admin','manager')
 ) THEN RAISE EXCEPTION 'inventory_management_permission_required' USING ERRCODE='42501'; END IF;
 v_fingerprint:=md5(concat_ws('|',p_location_id::text,p_offering_id::text,p_quantity::numeric(20,6)::text,p_unit_cost_minor::text));
 PERFORM pg_advisory_xact_lock(hashtext(p_organization_id::text),hashtext('inventory-receipt:'||p_source_ref));
 SELECT * INTO v_receipt FROM public.commercial_inventory_receipts
 WHERE organization_id=p_organization_id AND source_ref=p_source_ref FOR UPDATE;
 IF FOUND THEN
   IF v_receipt.request_fingerprint IS DISTINCT FROM v_fingerprint THEN
     RAISE EXCEPTION 'inventory_receipt_key_reused_with_different_request' USING ERRCODE='23505';
   END IF;
   SELECT * INTO b FROM public.commercial_inventory_balances
   WHERE organization_id=p_organization_id AND location_id=p_location_id AND offering_id=p_offering_id;
   RETURN jsonb_build_object('idempotent_replay',true,'receipt_id',v_receipt.id,
     'quantity_on_hand',b.quantity_on_hand,'unit_cost_minor',b.unit_cost_minor);
 END IF;
 SELECT * INTO b FROM public.commercial_inventory_balances
 WHERE organization_id=p_organization_id AND location_id=p_location_id AND offering_id=p_offering_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'inventory_balance_missing' USING ERRCODE='P0002'; END IF;
 IF b.quantity_on_hand<0 OR b.quantity_reserved<0 OR b.quantity_reserved>b.quantity_on_hand THEN
   RAISE EXCEPTION 'inventory_balance_invalid' USING ERRCODE='55000';
 END IF;
 IF b.unit_cost_minor IS NULL AND b.quantity_on_hand>0 THEN
   RAISE EXCEPTION 'existing_stock_cost_unknown_adjust_stock_through_audited_inventory_process' USING ERRCODE='55000';
 END IF;
 v_new_qty:=b.quantity_on_hand+p_quantity;
 IF v_new_qty>99999999999999.999999 THEN RAISE EXCEPTION 'inventory_quantity_overflow' USING ERRCODE='22003'; END IF;
 v_new_cost:=CASE WHEN b.quantity_on_hand=0 THEN p_unit_cost_minor
   ELSE round((b.quantity_on_hand::numeric*b.unit_cost_minor::numeric+p_quantity::numeric*p_unit_cost_minor::numeric)/v_new_qty) END;
 IF v_new_cost>9223372036854775807 THEN RAISE EXCEPTION 'inventory_cost_overflow' USING ERRCODE='22003'; END IF;
 INSERT INTO public.commercial_inventory_receipts(
   organization_id,location_id,offering_id,source_ref,request_fingerprint,quantity,unit_cost_minor,created_by
 ) VALUES(p_organization_id,p_location_id,p_offering_id,p_source_ref,v_fingerprint,p_quantity,p_unit_cost_minor,auth.uid())
 RETURNING * INTO v_receipt;
 UPDATE public.commercial_inventory_balances SET quantity_on_hand=v_new_qty,
   unit_cost_minor=v_new_cost::bigint,updated_at=now() WHERE id=b.id;
 INSERT INTO public.commercial_stock_movements(offering_id,movement_type,quantity_delta,source_ref,created_by)
 VALUES(p_offering_id,'receipt',p_quantity,'receipt:'||p_organization_id::text||':'||p_source_ref,auth.uid());
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(p_organization_id,auth.uid(),'inventory.received','inventory_balance',b.id::text,
   jsonb_build_object('receipt_id',v_receipt.id,'location_id',p_location_id,'offering_id',p_offering_id,
     'quantity',p_quantity,'unit_cost_minor',p_unit_cost_minor,'new_average_cost_minor',v_new_cost::bigint,'source_ref',p_source_ref));
 RETURN jsonb_build_object('idempotent_replay',false,'receipt_id',v_receipt.id,
   'quantity_on_hand',v_new_qty,'unit_cost_minor',v_new_cost::bigint);
END $function$;
REVOKE ALL ON FUNCTION public.commercial_receive_inventory_stock(uuid,uuid,uuid,numeric,bigint,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_receive_inventory_stock(uuid,uuid,uuid,numeric,bigint,text) TO authenticated;

COMMENT ON FUNCTION public.commercial_receive_inventory_stock(uuid,uuid,uuid,numeric,bigint,text) IS
'Posts an idempotent inventory receipt, updates the branch balance and weighted average cost, and records an explicit receipt movement in the stock ledger.';

COMMIT;
