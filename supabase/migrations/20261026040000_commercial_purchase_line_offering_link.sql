-- Allow a manager to explicitly map legacy purchase lines to real inventory offerings before receiving.
CREATE OR REPLACE FUNCTION commercial_private.commercial_link_purchase_order_item_offering(
  p_organization_id uuid, p_purchase_order_id uuid, p_purchase_order_item_id uuid, p_offering_id uuid
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_status text; v_currency text; v_existing_offering uuid; v_received numeric; v_item_org uuid;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR p_purchase_order_id IS NULL OR p_purchase_order_item_id IS NULL OR p_offering_id IS NULL THEN
    RAISE EXCEPTION 'invalid_purchase_line_offering_link' USING ERRCODE='22023';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN
    RAISE EXCEPTION 'purchase_management_permission_required' USING ERRCODE='42501';
  END IF;
  SELECT status,currency_code INTO v_status,v_currency
    FROM public.commercial_purchase_orders WHERE id=p_purchase_order_id AND organization_id=p_organization_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'purchase_order_not_found' USING ERRCODE='P0002'; END IF;
  IF v_status NOT IN ('draft','submitted','approved') THEN RAISE EXCEPTION 'purchase_line_offering_link_state_forbidden' USING ERRCODE='55000'; END IF;
  SELECT offering_id,quantity_received INTO v_existing_offering,v_received
    FROM public.commercial_purchase_order_items
    WHERE id=p_purchase_order_item_id AND organization_id=p_organization_id AND purchase_order_id=p_purchase_order_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'purchase_order_item_not_found' USING ERRCODE='P0002'; END IF;
  IF v_existing_offering IS NOT NULL THEN
    IF v_existing_offering=p_offering_id THEN RETURN jsonb_build_object('purchase_order_item_id',p_purchase_order_item_id,'offering_id',p_offering_id,'idempotent_replay',true,'status',v_status); END IF;
    RAISE EXCEPTION 'purchase_line_offering_already_linked' USING ERRCODE='55000';
  END IF;
  IF coalesce(v_received,0)>0 OR EXISTS (SELECT 1 FROM public.commercial_supplier_invoices
    WHERE organization_id=p_organization_id AND purchase_order_id=p_purchase_order_id) THEN
    RAISE EXCEPTION 'purchase_line_offering_link_after_receipt_or_invoice_forbidden' USING ERRCODE='55000';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_offerings o
    JOIN public.commercial_activities a ON a.id=o.activity_id
    JOIN public.commercial_products p ON p.id=o.product_id
    WHERE o.id=p_offering_id AND a.organization_id=p_organization_id AND a.state='active'
      AND o.state='active' AND p.state='active' AND upper(o.currency_code)=v_currency
  ) THEN RAISE EXCEPTION 'purchase_offering_currency_or_scope_mismatch' USING ERRCODE='22023'; END IF;
  UPDATE public.commercial_purchase_order_items SET offering_id=p_offering_id,updated_at=now()
    WHERE id=p_purchase_order_item_id AND organization_id=p_organization_id AND purchase_order_id=p_purchase_order_id;
  IF v_status='approved' THEN
    UPDATE public.commercial_purchase_orders SET status='submitted',approved_by=NULL,approved_at=NULL,updated_at=now()
      WHERE id=p_purchase_order_id AND organization_id=p_organization_id;
    v_status:='submitted';
  END IF;
  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
  VALUES(p_organization_id,v_uid,'purchase_order_item.offering_linked','purchase_order_item',p_purchase_order_item_id::text,
    jsonb_build_object('purchase_order_id',p_purchase_order_id,'offering_id',p_offering_id,'reapproval_required',v_status='submitted'));
  RETURN jsonb_build_object('purchase_order_item_id',p_purchase_order_item_id,'offering_id',p_offering_id,'idempotent_replay',false,'status',v_status);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_link_purchase_order_item_offering(
  p_organization_id uuid,p_purchase_order_id uuid,p_purchase_order_item_id uuid,p_offering_id uuid
) RETURNS jsonb LANGUAGE sql SECURITY DEFINER SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$ SELECT commercial_private.commercial_link_purchase_order_item_offering($1,$2,$3,$4) $function$;

REVOKE ALL ON FUNCTION commercial_private.commercial_link_purchase_order_item_offering(uuid,uuid,uuid,uuid) FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION public.commercial_link_purchase_order_item_offering(uuid,uuid,uuid,uuid) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_link_purchase_order_item_offering(uuid,uuid,uuid,uuid) TO authenticated;
