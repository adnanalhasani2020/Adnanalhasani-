-- Preserve original invoice selling units and let the receiving organization select its branch.
-- Quantity fields used by existing purchasing/receiving logic are unchanged.
BEGIN;

ALTER TABLE public.commercial_invoice_purchase_request_items
  ADD COLUMN IF NOT EXISTS selling_unit_code text,
  ADD COLUMN IF NOT EXISTS selling_unit_name text,
  ADD COLUMN IF NOT EXISTS selling_quantity numeric(30,12),
  ADD COLUMN IF NOT EXISTS quantity_in_stock_units bigint;

ALTER TABLE public.commercial_purchase_order_items
  ADD COLUMN IF NOT EXISTS source_selling_unit_code text,
  ADD COLUMN IF NOT EXISTS source_selling_unit_name text,
  ADD COLUMN IF NOT EXISTS source_selling_quantity numeric(30,12),
  ADD COLUMN IF NOT EXISTS source_quantity_in_stock_units bigint;

UPDATE public.commercial_invoice_purchase_request_items ri
SET selling_unit_code=si.selling_unit_code,
    selling_unit_name=si.selling_unit_name,
    selling_quantity=si.selling_quantity,
    quantity_in_stock_units=si.quantity_in_stock_units
FROM public.commercial_sale_items si
WHERE si.id=ri.source_sale_item_id
  AND (ri.selling_unit_code IS NULL OR ri.selling_unit_name IS NULL
       OR ri.selling_quantity IS NULL OR ri.quantity_in_stock_units IS NULL);

UPDATE public.commercial_purchase_order_items poi
SET source_selling_unit_code=ri.selling_unit_code,
    source_selling_unit_name=ri.selling_unit_name,
    source_selling_quantity=ri.selling_quantity,
    source_quantity_in_stock_units=ri.quantity_in_stock_units
FROM public.commercial_invoice_purchase_requests r
JOIN public.commercial_invoice_purchase_request_items ri
  ON ri.request_id=r.id AND ri.line_number=poi.line_number
WHERE r.destination_purchase_order_id=poi.purchase_order_id
  AND r.destination_organization_id=poi.organization_id
  AND (poi.source_selling_unit_code IS NULL OR poi.source_selling_unit_name IS NULL
       OR poi.source_selling_quantity IS NULL OR poi.source_quantity_in_stock_units IS NULL);

CREATE OR REPLACE FUNCTION public.commercial_request_invoice_as_purchase(
 p_source_organization_id uuid,p_destination_organization_id uuid,p_sale_id uuid,p_request_key text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE
 v_uid uuid:=auth.uid(); v_sale public.commercial_sales%ROWTYPE; v_request uuid; v_fp text;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_source_organization_id=p_destination_organization_id OR length(btrim(coalesce(p_request_key,''))) NOT BETWEEN 8 AND 200 THEN
  RAISE EXCEPTION 'invalid_invoice_transfer_request' USING ERRCODE='22023'; END IF;
 IF NOT public.commercial_catalog_member_of(p_source_organization_id,ARRAY['owner','admin','manager']::text[]) THEN
  RAISE EXCEPTION 'source_invoice_transfer_forbidden' USING ERRCODE='42501'; END IF;
 SELECT * INTO v_sale FROM public.commercial_sales
  WHERE id=p_sale_id AND organization_id=p_source_organization_id AND status='completed';
 IF NOT FOUND OR NOT EXISTS(SELECT 1 FROM public.commercial_sale_items WHERE sale_id=p_sale_id) THEN
  RAISE EXCEPTION 'completed_source_invoice_not_found_or_empty' USING ERRCODE='22023'; END IF;
 v_fp:=md5(concat_ws('|',p_source_organization_id,p_destination_organization_id,p_sale_id,v_sale.total_minor,v_sale.currency_code));
 SELECT id INTO v_request FROM public.commercial_invoice_purchase_requests
  WHERE source_organization_id=p_source_organization_id AND request_key=p_request_key;
 IF FOUND THEN
  IF (SELECT request_fingerprint FROM public.commercial_invoice_purchase_requests WHERE id=v_request)<>v_fp THEN
   RAISE EXCEPTION 'idempotency_key_payload_mismatch' USING ERRCODE='22023'; END IF;
  RETURN jsonb_build_object('request_id',v_request,
   'state',(SELECT status FROM public.commercial_invoice_purchase_requests WHERE id=v_request),
   'idempotent_replay',true);
 END IF;
 INSERT INTO public.commercial_invoice_purchase_requests(source_organization_id,destination_organization_id,source_sale_id,status,
  source_invoice_number,currency_code,total_minor,request_key,request_fingerprint,requested_by)
 VALUES(p_source_organization_id,p_destination_organization_id,p_sale_id,'pending',v_sale.receipt_number::text,
  v_sale.currency_code,v_sale.total_minor,p_request_key,v_fp,v_uid) RETURNING id INTO v_request;
 INSERT INTO public.commercial_invoice_purchase_request_items(request_id,line_number,source_sale_item_id,global_item_id,
  product_name_snapshot,quantity,unit_price_minor,line_total_minor,selling_unit_code,selling_unit_name,
  selling_quantity,quantity_in_stock_units)
 SELECT v_request,row_number() OVER(ORDER BY si.created_at,si.id)::integer,si.id,m.global_item_id,
  si.product_name_snapshot,si.quantity,si.unit_price_minor,si.line_total_minor,si.selling_unit_code,
  si.selling_unit_name,si.selling_quantity,si.quantity_in_stock_units
 FROM public.commercial_sale_items si
 LEFT JOIN public.commercial_catalog_item_offerings m
  ON m.offering_id=si.offering_id AND m.organization_id=p_source_organization_id
 WHERE si.sale_id=p_sale_id;
 RETURN jsonb_build_object('request_id',v_request,'state','pending',
  'item_count',(SELECT count(*) FROM public.commercial_invoice_purchase_request_items WHERE request_id=v_request),
  'idempotent_replay',false);
END; $$;

-- Do not guess an offering during approval. Mapping is a separate, recipient-controlled action
-- scoped to the purchase order's receiving location.

DROP FUNCTION IF EXISTS public.commercial_review_invoice_purchase_request(uuid,text,text);
CREATE FUNCTION public.commercial_review_invoice_purchase_request(
 p_request_id uuid,p_decision text,p_review_note text DEFAULT NULL,p_receive_location_id uuid DEFAULT NULL
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE
 v_uid uuid:=auth.uid(); v_req public.commercial_invoice_purchase_requests%ROWTYPE;
 v_supplier uuid; v_po uuid; v_org_name text; v_item record; v_receive_location uuid;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_decision NOT IN('approve','reject') THEN RAISE EXCEPTION 'invalid_decision' USING ERRCODE='22023'; END IF;
 SELECT * INTO v_req FROM public.commercial_invoice_purchase_requests WHERE id=p_request_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'invoice_purchase_request_not_found' USING ERRCODE='22023'; END IF;
 IF NOT public.commercial_catalog_member_of(v_req.destination_organization_id,ARRAY['owner','admin','manager']::text[]) THEN
  RAISE EXCEPTION 'recipient_approval_forbidden' USING ERRCODE='42501'; END IF;
 IF v_req.status<>'pending' THEN
  RETURN jsonb_build_object('request_id',v_req.id,'state',v_req.status,
   'purchase_order_id',v_req.destination_purchase_order_id,'idempotent_replay',true);
 END IF;
 IF p_decision='reject' THEN
  UPDATE public.commercial_invoice_purchase_requests SET status='rejected',reviewed_by=v_uid,
   reviewed_at=now(),review_note=left(p_review_note,1000) WHERE id=v_req.id;
  RETURN jsonb_build_object('request_id',v_req.id,'state','rejected','idempotent_replay',false);
 END IF;
 IF p_receive_location_id IS NOT NULL THEN
  SELECT id INTO v_receive_location FROM public.commercial_organization_locations
   WHERE id=p_receive_location_id AND organization_id=v_req.destination_organization_id AND state='active';
  IF v_receive_location IS NULL THEN
   RAISE EXCEPTION 'recipient_receiving_location_not_in_organization' USING ERRCODE='22023';
  END IF;
 ELSE
  -- Backward compatibility for older clients: retain the previous deterministic default.
  SELECT id INTO v_receive_location FROM public.commercial_organization_locations
   WHERE organization_id=v_req.destination_organization_id AND state='active'
   ORDER BY CASE WHEN location_type='warehouse' THEN 0 WHEN location_type='branch' THEN 1 ELSE 2 END,created_at,id
   LIMIT 1;
  IF v_receive_location IS NULL THEN
   RAISE EXCEPTION 'recipient_active_receiving_location_required' USING ERRCODE='55000';
  END IF;
 END IF;
 SELECT id INTO v_supplier FROM public.commercial_suppliers
  WHERE organization_id=v_req.destination_organization_id AND source_organization_id=v_req.source_organization_id;
 IF v_supplier IS NULL THEN
  SELECT name INTO v_org_name FROM public.commercial_organizations WHERE id=v_req.source_organization_id;
  INSERT INTO public.commercial_suppliers(organization_id,source_organization_id,display_name,supplier_code,state,created_by,notes)
  VALUES(v_req.destination_organization_id,v_req.source_organization_id,
   coalesce(nullif(btrim(v_org_name),''),'مؤسسة تجارية'),
   'ORG-'||upper(substr(replace(v_req.source_organization_id::text,'-',''),1,12)),
   'active',v_uid,'Created from an explicitly approved inter-organization invoice transfer.')
  ON CONFLICT(organization_id,source_organization_id) WHERE source_organization_id IS NOT NULL
  DO UPDATE SET updated_at=now() RETURNING id INTO v_supplier;
 END IF;
 INSERT INTO public.commercial_purchase_orders(organization_id,supplier_id,order_number,status,currency_code,
  notes,created_by,ordered_at,receive_location_id)
 VALUES(v_req.destination_organization_id,v_supplier,
  'INV-XFER-'||upper(substr(replace(v_req.id::text,'-',''),1,12)),
  'draft',v_req.currency_code,'Approved seller invoice '||v_req.source_invoice_number||
  '. Stock remains unchanged until receiving.',v_uid,now(),v_receive_location)
 RETURNING id INTO v_po;
 FOR v_item IN
  SELECT * FROM public.commercial_invoice_purchase_request_items
  WHERE request_id=v_req.id ORDER BY line_number
 LOOP
  INSERT INTO public.commercial_purchase_order_items(organization_id,purchase_order_id,line_number,
   offering_id,item_description,quantity_ordered,quantity_received,unit_cost_minor,
   source_selling_unit_code,source_selling_unit_name,source_selling_quantity,source_quantity_in_stock_units)
  VALUES(v_req.destination_organization_id,v_po,v_item.line_number,NULL,
   v_item.product_name_snapshot,v_item.quantity,0,v_item.unit_price_minor,
   v_item.selling_unit_code,v_item.selling_unit_name,v_item.selling_quantity,v_item.quantity_in_stock_units);
 END LOOP;
 UPDATE public.commercial_invoice_purchase_requests SET status='approved',
  destination_purchase_order_id=v_po,reviewed_by=v_uid,reviewed_at=now(),
  review_note=left(p_review_note,1000) WHERE id=v_req.id;
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(v_req.destination_organization_id,v_uid,'invoice_purchase_request.approved','purchase_order',v_po::text,
  jsonb_build_object('request_id',v_req.id,'source_organization_id',v_req.source_organization_id,
   'source_sale_id',v_req.source_sale_id,'line_count',
   (SELECT count(*) FROM public.commercial_invoice_purchase_request_items WHERE request_id=v_req.id),
   'stock_received',false,'catalog_mapping_required',true));
 RETURN jsonb_build_object('request_id',v_req.id,'state','approved','purchase_order_id',v_po,
  'line_count',(SELECT count(*) FROM public.commercial_invoice_purchase_request_items WHERE request_id=v_req.id),
  'stock_received',false,'catalog_mapping_required',true,'idempotent_replay',false);
END; $$;

COMMIT;
