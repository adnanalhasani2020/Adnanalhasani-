-- Recipient-approved invoice hand-off creates a draft purchase order; stock changes only on receipt.
BEGIN;
ALTER TABLE public.commercial_suppliers ADD COLUMN IF NOT EXISTS source_organization_id uuid
 REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT;
CREATE UNIQUE INDEX IF NOT EXISTS ux_commercial_suppliers_source_organization
 ON public.commercial_suppliers(organization_id,source_organization_id) WHERE source_organization_id IS NOT NULL;
CREATE TABLE IF NOT EXISTS public.commercial_invoice_purchase_requests(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 source_organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
 destination_organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
 source_sale_id uuid NOT NULL REFERENCES public.commercial_sales(id) ON DELETE RESTRICT,
 destination_purchase_order_id uuid REFERENCES public.commercial_purchase_orders(id) ON DELETE RESTRICT,
 status text NOT NULL DEFAULT 'pending' CHECK(status IN('pending','approved','rejected','cancelled')),
 source_invoice_number text NOT NULL,currency_code text NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
 total_minor bigint NOT NULL CHECK(total_minor>=0),request_key text NOT NULL CHECK(length(btrim(request_key)) BETWEEN 8 AND 200),
 request_fingerprint text NOT NULL,requested_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
 reviewed_by uuid REFERENCES auth.users(id) ON DELETE RESTRICT,reviewed_at timestamptz,review_note text,
 created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(source_organization_id,request_key),
 CHECK(source_organization_id<>destination_organization_id)
);
CREATE TABLE IF NOT EXISTS public.commercial_invoice_purchase_request_items(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 request_id uuid NOT NULL REFERENCES public.commercial_invoice_purchase_requests(id) ON DELETE CASCADE,
 source_sale_item_id uuid NOT NULL REFERENCES public.commercial_sale_items(id) ON DELETE RESTRICT,
 global_item_id uuid REFERENCES public.commercial_global_catalog_items(id) ON DELETE RESTRICT,
 product_name_snapshot text NOT NULL,quantity numeric(18,6) NOT NULL CHECK(quantity>0),
 unit_price_minor bigint NOT NULL CHECK(unit_price_minor>=0),line_total_minor bigint NOT NULL CHECK(line_total_minor>=0),
 UNIQUE(request_id,source_sale_item_id)
);
ALTER TABLE public.commercial_invoice_purchase_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_invoice_purchase_request_items ENABLE ROW LEVEL SECURITY;
GRANT SELECT ON public.commercial_invoice_purchase_requests,public.commercial_invoice_purchase_request_items TO authenticated;
DROP POLICY IF EXISTS commercial_invoice_purchase_requests_member_read ON public.commercial_invoice_purchase_requests;
CREATE POLICY commercial_invoice_purchase_requests_member_read ON public.commercial_invoice_purchase_requests FOR SELECT TO authenticated
 USING(public.commercial_catalog_member_of(source_organization_id,ARRAY['owner','admin','manager','staff']::text[])
 OR public.commercial_catalog_member_of(destination_organization_id,ARRAY['owner','admin','manager','staff']::text[]));
DROP POLICY IF EXISTS commercial_invoice_purchase_request_items_member_read ON public.commercial_invoice_purchase_request_items;
CREATE POLICY commercial_invoice_purchase_request_items_member_read ON public.commercial_invoice_purchase_request_items FOR SELECT TO authenticated
 USING(EXISTS(SELECT 1 FROM public.commercial_invoice_purchase_requests r WHERE r.id=request_id AND
 (public.commercial_catalog_member_of(r.source_organization_id,ARRAY['owner','admin','manager','staff']::text[])
 OR public.commercial_catalog_member_of(r.destination_organization_id,ARRAY['owner','admin','manager','staff']::text[]))));

CREATE OR REPLACE FUNCTION public.commercial_request_invoice_as_purchase(
 p_source_organization_id uuid,p_destination_organization_id uuid,p_sale_id uuid,p_request_key text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE v_uid uuid:=auth.uid(); v_sale public.commercial_sales%ROWTYPE; v_request uuid; v_fp text;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_source_organization_id=p_destination_organization_id OR length(btrim(coalesce(p_request_key,''))) NOT BETWEEN 8 AND 200 THEN
  RAISE EXCEPTION 'invalid_invoice_transfer_request' USING ERRCODE='22023'; END IF;
 IF NOT public.commercial_catalog_member_of(p_source_organization_id,ARRAY['owner','admin','manager']::text[]) THEN
  RAISE EXCEPTION 'source_invoice_transfer_forbidden' USING ERRCODE='42501'; END IF;
 SELECT * INTO v_sale FROM public.commercial_sales WHERE id=p_sale_id AND organization_id=p_source_organization_id AND status='completed';
 IF NOT FOUND OR NOT EXISTS(SELECT 1 FROM public.commercial_sale_items WHERE sale_id=p_sale_id) THEN
  RAISE EXCEPTION 'completed_source_invoice_not_found_or_empty' USING ERRCODE='22023'; END IF;
 v_fp:=md5(concat_ws('|',p_source_organization_id,p_destination_organization_id,p_sale_id,v_sale.total_minor,v_sale.currency_code));
 SELECT id INTO v_request FROM public.commercial_invoice_purchase_requests WHERE source_organization_id=p_source_organization_id AND request_key=p_request_key;
 IF FOUND THEN
  IF (SELECT request_fingerprint FROM public.commercial_invoice_purchase_requests WHERE id=v_request)<>v_fp THEN
   RAISE EXCEPTION 'idempotency_key_payload_mismatch' USING ERRCODE='22023'; END IF;
  RETURN jsonb_build_object('request_id',v_request,'state',(SELECT status FROM public.commercial_invoice_purchase_requests WHERE id=v_request),'idempotent_replay',true);
 END IF;
 INSERT INTO public.commercial_invoice_purchase_requests(source_organization_id,destination_organization_id,source_sale_id,status,
  source_invoice_number,currency_code,total_minor,request_key,request_fingerprint,requested_by)
 VALUES(p_source_organization_id,p_destination_organization_id,p_sale_id,'pending',v_sale.receipt_number::text,
  v_sale.currency_code,v_sale.total_minor,p_request_key,v_fp,v_uid) RETURNING id INTO v_request;
 INSERT INTO public.commercial_invoice_purchase_request_items(request_id,source_sale_item_id,global_item_id,product_name_snapshot,
  quantity,unit_price_minor,line_total_minor)
 SELECT v_request,si.id,m.global_item_id,si.product_name_snapshot,si.quantity,si.unit_price_minor,si.line_total_minor
 FROM public.commercial_sale_items si LEFT JOIN public.commercial_catalog_item_offerings m
  ON m.offering_id=si.offering_id AND m.organization_id=p_source_organization_id WHERE si.sale_id=p_sale_id;
 RETURN jsonb_build_object('request_id',v_request,'state','pending',
  'item_count',(SELECT count(*) FROM public.commercial_invoice_purchase_request_items WHERE request_id=v_request),'idempotent_replay',false);
END; $$;

CREATE OR REPLACE FUNCTION public.commercial_review_invoice_purchase_request(
 p_request_id uuid,p_decision text,p_review_note text DEFAULT NULL
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE v_uid uuid:=auth.uid(); v_req public.commercial_invoice_purchase_requests%ROWTYPE;
 v_supplier uuid; v_po uuid; v_org_name text; v_item record; v_line integer:=0;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_decision NOT IN('approve','reject') THEN RAISE EXCEPTION 'invalid_decision' USING ERRCODE='22023'; END IF;
 SELECT * INTO v_req FROM public.commercial_invoice_purchase_requests WHERE id=p_request_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'invoice_purchase_request_not_found' USING ERRCODE='22023'; END IF;
 IF NOT public.commercial_catalog_member_of(v_req.destination_organization_id,ARRAY['owner','admin','manager']::text[]) THEN
  RAISE EXCEPTION 'recipient_approval_forbidden' USING ERRCODE='42501'; END IF;
 IF v_req.status<>'pending' THEN RETURN jsonb_build_object('request_id',v_req.id,'state',v_req.status,
  'purchase_order_id',v_req.destination_purchase_order_id,'idempotent_replay',true); END IF;
 IF p_decision='reject' THEN
  UPDATE public.commercial_invoice_purchase_requests SET status='rejected',reviewed_by=v_uid,reviewed_at=now(),
   review_note=left(p_review_note,1000) WHERE id=v_req.id;
  RETURN jsonb_build_object('request_id',v_req.id,'state','rejected','idempotent_replay',false);
 END IF;
 SELECT id INTO v_supplier FROM public.commercial_suppliers WHERE organization_id=v_req.destination_organization_id
  AND source_organization_id=v_req.source_organization_id;
 IF v_supplier IS NULL THEN
  SELECT name INTO v_org_name FROM public.commercial_organizations WHERE id=v_req.source_organization_id;
  INSERT INTO public.commercial_suppliers(organization_id,source_organization_id,display_name,supplier_code,state,created_by,notes)
  VALUES(v_req.destination_organization_id,v_req.source_organization_id,coalesce(nullif(btrim(v_org_name),''),'مؤسسة تجارية'),
   'ORG-'||upper(substr(replace(v_req.source_organization_id::text,'-',''),1,12)),'active',v_uid,
   'Created from an explicitly approved inter-organization invoice transfer.')
  ON CONFLICT(organization_id,source_organization_id) WHERE source_organization_id IS NOT NULL
  DO UPDATE SET updated_at=now() RETURNING id INTO v_supplier;
 END IF;
 INSERT INTO public.commercial_purchase_orders(organization_id,supplier_id,order_number,status,currency_code,notes,created_by,ordered_at)
 VALUES(v_req.destination_organization_id,v_supplier,'INV-XFER-'||upper(substr(replace(v_req.id::text,'-',''),1,12)),
  'draft',v_req.currency_code,'Approved seller invoice '||v_req.source_invoice_number||
  '. Stock remains unchanged until receiving.',v_uid,now()) RETURNING id INTO v_po;
 FOR v_item IN SELECT * FROM public.commercial_invoice_purchase_request_items WHERE request_id=v_req.id ORDER BY id LOOP
  v_line:=v_line+1;
  INSERT INTO public.commercial_purchase_order_items(organization_id,purchase_order_id,line_number,offering_id,item_description,
   quantity_ordered,quantity_received,unit_cost_minor)
  VALUES(v_req.destination_organization_id,v_po,v_line,
   (SELECT m.offering_id FROM public.commercial_catalog_item_offerings m WHERE m.organization_id=v_req.destination_organization_id
    AND m.global_item_id=v_item.global_item_id LIMIT 1),v_item.product_name_snapshot,v_item.quantity,0,v_item.unit_price_minor);
 END LOOP;
 UPDATE public.commercial_invoice_purchase_requests SET status='approved',destination_purchase_order_id=v_po,
  reviewed_by=v_uid,reviewed_at=now(),review_note=left(p_review_note,1000) WHERE id=v_req.id;
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(v_req.destination_organization_id,v_uid,'invoice_purchase_request.approved','purchase_order',v_po::text,
  jsonb_build_object('request_id',v_req.id,'source_organization_id',v_req.source_organization_id,
   'source_sale_id',v_req.source_sale_id,'line_count',v_line,'stock_received',false));
 RETURN jsonb_build_object('request_id',v_req.id,'state','approved','purchase_order_id',v_po,
  'line_count',v_line,'stock_received',false,'idempotent_replay',false);
END; $$;
REVOKE ALL ON FUNCTION public.commercial_request_invoice_as_purchase(uuid,uuid,uuid,text) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_review_invoice_purchase_request(uuid,text,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_request_invoice_as_purchase(uuid,uuid,uuid,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_review_invoice_purchase_request(uuid,text,text) TO authenticated;
COMMIT;
