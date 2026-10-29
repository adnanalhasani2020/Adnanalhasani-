-- Map approved invoice-transfer lines to the recipient's existing trusted catalog offering.
-- This only fills an unambiguous offering link; it never creates prices, offerings, or stock.
BEGIN;
CREATE OR REPLACE FUNCTION public.commercial_invoice_purchase_map_known_items(p_request_id uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE
 v_uid uuid:=auth.uid(); v_req public.commercial_invoice_purchase_requests%ROWTYPE; v_po public.commercial_purchase_orders%ROWTYPE;
 v_mapped integer:=0; v_unmapped integer:=0;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 SELECT * INTO v_req FROM public.commercial_invoice_purchase_requests WHERE id=p_request_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'invoice_purchase_request_not_found' USING ERRCODE='22023'; END IF;
 IF NOT public.commercial_catalog_member_of(v_req.destination_organization_id,ARRAY['owner','admin','manager']::text[]) THEN
  RAISE EXCEPTION 'recipient_catalog_mapping_forbidden' USING ERRCODE='42501'; END IF;
 IF v_req.status<>'approved' OR v_req.destination_purchase_order_id IS NULL THEN
  RAISE EXCEPTION 'approved_purchase_order_required' USING ERRCODE='55000'; END IF;
 SELECT * INTO v_po FROM public.commercial_purchase_orders WHERE id=v_req.destination_purchase_order_id AND organization_id=v_req.destination_organization_id FOR UPDATE;
 IF NOT FOUND OR v_po.status NOT IN ('draft','ordered','partially_received') THEN RAISE EXCEPTION 'purchase_order_not_mappable' USING ERRCODE='55000'; END IF;
 WITH request_lines AS (
  SELECT ri.id,ri.global_item_id,row_number() OVER(ORDER BY ri.id)::integer AS line_number
  FROM public.commercial_invoice_purchase_request_items ri WHERE ri.request_id=v_req.id
 ), candidates AS (
  SELECT rl.id,rl.line_number,CASE WHEN count(DISTINCT o.id)=1 THEN min(o.id::text)::uuid ELSE NULL END AS offering_id
  FROM request_lines rl
  LEFT JOIN public.commercial_catalog_item_offerings m ON m.organization_id=v_req.destination_organization_id AND m.global_item_id=rl.global_item_id
  LEFT JOIN public.commercial_offerings o ON o.id=m.offering_id AND o.state='active' AND o.location_ref=v_po.receive_location_id::text
  GROUP BY rl.id,rl.line_number
 )
 UPDATE public.commercial_purchase_order_items poi SET offering_id=c.offering_id,updated_at=now()
 FROM candidates c WHERE poi.purchase_order_id=v_po.id AND poi.organization_id=v_req.destination_organization_id
  AND poi.line_number=c.line_number AND poi.offering_id IS NULL AND c.offering_id IS NOT NULL AND poi.quantity_received=0;
 GET DIAGNOSTICS v_mapped=ROW_COUNT;
 SELECT count(*)::integer INTO v_unmapped FROM public.commercial_invoice_purchase_request_items ri
 JOIN LATERAL (SELECT x.id,row_number() OVER(ORDER BY x.id)::integer AS line_number FROM public.commercial_invoice_purchase_request_items x WHERE x.request_id=v_req.id) rl ON rl.id=ri.id
 JOIN public.commercial_purchase_order_items poi ON poi.purchase_order_id=v_po.id AND poi.organization_id=v_req.destination_organization_id AND poi.line_number=rl.line_number
 WHERE ri.request_id=v_req.id AND poi.offering_id IS NULL;
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(v_req.destination_organization_id,v_uid,'invoice_purchase_request.catalog_mapping_attempted','purchase_order',v_po.id::text,
  jsonb_build_object('request_id',v_req.id,'mapped_lines',v_mapped,'unmapped_lines',v_unmapped));
 RETURN jsonb_build_object('request_id',v_req.id,'purchase_order_id',v_po.id,'mapped_lines',v_mapped,'unmapped_lines',v_unmapped,'stock_changed',false);
END; $$;
REVOKE ALL ON FUNCTION public.commercial_invoice_purchase_map_known_items(uuid) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_invoice_purchase_map_known_items(uuid) TO authenticated;
COMMIT;
