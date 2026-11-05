BEGIN;

ALTER TABLE public.commercial_customer_supply_requests
 ADD COLUMN customer_order_id uuid REFERENCES public.commercial_customer_orders(id) ON DELETE RESTRICT,
 ADD COLUMN sale_id uuid REFERENCES public.commercial_sales(id) ON DELETE RESTRICT,
 ADD COLUMN direct_sale_status text NOT NULL DEFAULT 'not_sold'
  CHECK(direct_sale_status IN('not_sold','sold'));
CREATE UNIQUE INDEX commercial_customer_supply_requests_customer_order_uidx
 ON public.commercial_customer_supply_requests(customer_order_id) WHERE customer_order_id IS NOT NULL;
CREATE UNIQUE INDEX commercial_customer_supply_requests_sale_uidx
 ON public.commercial_customer_supply_requests(sale_id) WHERE sale_id IS NOT NULL;

ALTER TABLE public.commercial_customer_supply_request_events
 DROP CONSTRAINT commercial_customer_supply_request_events_event_type_check;
ALTER TABLE public.commercial_customer_supply_request_events
 ADD CONSTRAINT commercial_customer_supply_request_events_event_type_check
 CHECK(event_type IN('submitted','accepted','rejected','change_requested','ignored','deferred','resubmitted','converted','sold'));

-- Direct sale is permitted only after the purchase order has been fully received and
-- a supplier invoice has been recorded. Purchase receipt and sale checkout run in one
-- transaction; any failure rolls both back. The temporary price override is protected
-- by the offering row lock and restored before commit.
CREATE OR REPLACE FUNCTION public.commercial_customer_supply_request_sell_direct(
 p_organization_id uuid,p_request_id uuid,p_sale_unit_price_minor bigint,
 p_discount_minor bigint DEFAULT 0,p_tax_minor bigint DEFAULT 0
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE
 v_uid uuid:=auth.uid(); v_req public.commercial_customer_supply_requests%ROWTYPE;
 v_po public.commercial_purchase_orders%ROWTYPE; v_line public.commercial_purchase_order_items%ROWTYPE;
 v_offer public.commercial_offerings%ROWTYPE; v_order_id uuid; v_sale_id uuid;
 v_subtotal numeric; v_total numeric; v_checkout jsonb; v_key text; v_sale_price bigint; v_invoice_price bigint;
 v_unit record;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF (p_sale_unit_price_minor IS NOT NULL AND p_sale_unit_price_minor<0)
  OR p_discount_minor IS NULL OR p_discount_minor<0 OR p_tax_minor IS NULL OR p_tax_minor<0 THEN
  RAISE EXCEPTION 'invalid_direct_sale_pricing' USING ERRCODE='22023';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active'
   AND m.role IN('owner','admin','manager')) THEN
  RAISE EXCEPTION 'customer_supply_request_management_forbidden' USING ERRCODE='42501';
 END IF;
 SELECT * INTO v_req FROM public.commercial_customer_supply_requests
 WHERE id=p_request_id AND organization_id=p_organization_id FOR UPDATE;
 IF NOT FOUND OR v_req.status<>'converted' OR v_req.purchase_order_id IS NULL THEN
  RAISE EXCEPTION 'converted_customer_supply_request_required' USING ERRCODE='55000';
 END IF;
 IF v_req.direct_sale_status='sold' THEN
  RETURN jsonb_build_object('request_id',v_req.id,'customer_order_id',v_req.customer_order_id,
   'sale_id',v_req.sale_id,'status','sold','replayed',true);
 END IF;
 SELECT * INTO v_po FROM public.commercial_purchase_orders
 WHERE id=v_req.purchase_order_id AND organization_id=p_organization_id FOR UPDATE;
 IF NOT FOUND OR v_po.status NOT IN('received','closed') THEN
  RAISE EXCEPTION 'supply_purchase_must_be_fully_received_before_direct_sale' USING ERRCODE='55000';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_supplier_invoices i
  WHERE i.organization_id=p_organization_id AND i.purchase_order_id=v_po.id
   AND i.supplier_id=v_po.supplier_id AND i.state='recorded') THEN
  RAISE EXCEPTION 'supplier_invoice_required_before_direct_sale' USING ERRCODE='55000';
 END IF;
 SELECT * INTO v_line FROM public.commercial_purchase_order_items
 WHERE organization_id=p_organization_id AND purchase_order_id=v_po.id
  AND item_description=v_req.item_name AND quantity_ordered=v_req.requested_quantity
  AND source_selling_unit_code=v_req.unit_code
 FOR UPDATE;
 IF NOT FOUND OR v_line.offering_id IS NULL OR v_line.quantity_received<>v_line.quantity_ordered
  OR v_line.quantity_ordered<>v_req.requested_quantity THEN
  RAISE EXCEPTION 'supply_purchase_line_not_fully_mapped_and_received' USING ERRCODE='55000';
 END IF;
 SELECT si.unit_price_minor INTO v_invoice_price
 FROM public.commercial_supplier_invoice_items si
 JOIN public.commercial_supplier_invoices sh ON sh.id=si.supplier_invoice_id
 WHERE si.organization_id=p_organization_id AND si.purchase_order_item_id=v_line.id
  AND sh.purchase_order_id=v_po.id AND sh.state='recorded'
 ORDER BY sh.recorded_at DESC,si.created_at DESC LIMIT 1;
 IF v_invoice_price IS NULL THEN RAISE EXCEPTION 'supplier_invoice_line_price_required_for_direct_sale' USING ERRCODE='55000'; END IF;
 v_sale_price:=coalesce(p_sale_unit_price_minor,v_invoice_price);
 SELECT * INTO v_offer FROM public.commercial_offerings
 WHERE id=v_line.offering_id FOR UPDATE;
 IF NOT FOUND OR v_offer.location_ref IS DISTINCT FROM v_po.receive_location_id::text
  OR v_offer.currency_code IS DISTINCT FROM v_req.currency_code THEN
  RAISE EXCEPTION 'supply_purchase_offering_scope_or_currency_mismatch' USING ERRCODE='22023';
 END IF;
 SELECT unit_code,quantity_in_stock,is_base,is_active INTO v_unit
 FROM public.commercial_offering_sell_units
 WHERE offering_id=v_line.offering_id AND unit_code=v_req.unit_code AND is_active=true;
 IF NOT FOUND OR NOT v_unit.is_base OR v_unit.quantity_in_stock<>1 THEN
  RAISE EXCEPTION 'direct_sale_requires_matching_base_unit' USING ERRCODE='22023';
 END IF;
 IF v_req.buyer_account_id IS NULL OR NOT EXISTS(
  SELECT 1 FROM public.commercial_customer_party_accounts cpa
  JOIN public.commercial_customers c ON c.id=cpa.customer_id AND c.organization_id=cpa.organization_id
   AND c.state='active' AND c.id=v_req.customer_id
  JOIN public.commercial_party_accounts a ON a.id=cpa.party_account_id
   AND a.organization_id=cpa.organization_id AND a.status='active'
  WHERE cpa.organization_id=p_organization_id AND cpa.customer_id=v_req.customer_id
   AND cpa.party_account_id=v_req.buyer_account_id) THEN
  RAISE EXCEPTION 'supply_request_customer_account_inactive' USING ERRCODE='42501';
 END IF;
 v_subtotal:=v_sale_price::numeric*v_req.requested_quantity::numeric;
 IF v_subtotal>9223372036854775807 OR p_discount_minor>v_subtotal
  OR v_subtotal-p_discount_minor+p_tax_minor>9223372036854775807 THEN
  RAISE EXCEPTION 'direct_sale_total_overflow_or_invalid_discount' USING ERRCODE='22003';
 END IF;
 v_total:=v_subtotal-p_discount_minor+p_tax_minor;
 v_key:='supply-direct:'||v_req.id::text;
 -- A fixed idempotency key makes retries resolve to the same customer order and sale.
 SELECT id INTO v_order_id FROM public.commercial_customer_orders
 WHERE organization_id=p_organization_id AND buyer_account_id=v_req.buyer_account_id AND idempotency_key=v_key;
 IF v_order_id IS NULL THEN
  INSERT INTO public.commercial_customer_orders(
   organization_id,buyer_account_id,created_by,order_type,status,payment_status,currency_code,
   subtotal_minor,discount_minor,tax_minor,total_minor,customer_note,idempotency_key
  ) VALUES(p_organization_id,v_req.buyer_account_id,v_uid,'purchase','pending',
   CASE WHEN v_req.payment_mode='credit' THEN 'credit' ELSE 'unpaid' END,v_req.currency_code,
   v_subtotal::bigint,p_discount_minor,p_tax_minor,v_total::bigint,
   'بيع مباشر بعد طلب التوفير رقم '||v_req.id::text,v_key)
  RETURNING id INTO v_order_id;
  INSERT INTO public.commercial_customer_order_items(
   order_id,offering_id,item_type,item_name_snapshot,quantity,unit_price_minor,line_total_minor
  ) VALUES(v_order_id,v_line.offering_id,'product',v_req.item_name,v_req.requested_quantity,
   v_sale_price,(v_sale_price::numeric*v_req.requested_quantity)::bigint);
 END IF;
 -- The existing sale engine owns stock, receivables, payment and GL posting.
 -- Locking the offering row prevents another checkout from observing this temporary price.
 UPDATE public.commercial_offerings SET price_minor=v_sale_price
 WHERE id=v_line.offering_id AND activity_id=v_offer.activity_id;
 v_checkout:=public.commercial_customer_portal_fulfill_order(
  p_organization_id,v_order_id,v_po.receive_location_id,v_key
 );
 v_sale_id:=nullif(v_checkout->>'sale_id','')::uuid;
 IF v_sale_id IS NULL OR v_checkout->>'status' IS DISTINCT FROM 'fulfilled'
  OR (v_checkout->>'total_minor')::bigint<>v_total::bigint THEN
  RAISE EXCEPTION 'direct_customer_sale_checkout_mismatch' USING ERRCODE='55000';
 END IF;
 UPDATE public.commercial_offerings SET price_minor=v_offer.price_minor
 WHERE id=v_offer.id;
 UPDATE public.commercial_customer_supply_requests SET customer_order_id=v_order_id,
  sale_id=v_sale_id,direct_sale_status='sold',updated_at=now(),version=version+1
 WHERE id=v_req.id;
 INSERT INTO public.commercial_customer_supply_request_events(request_id,organization_id,actor_user_id,event_type,note)
 VALUES(v_req.id,p_organization_id,v_uid,'sold','sale_id='||v_sale_id::text);
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(p_organization_id,v_uid,'customer_supply_request.sold','sale',v_sale_id::text,
  jsonb_build_object('request_id',v_req.id,'purchase_order_id',v_po.id,'customer_order_id',v_order_id,
   'sale_price_minor',v_sale_price,'discount_minor',p_discount_minor,'tax_minor',p_tax_minor,
   'total_minor',v_total::bigint,'inventory_received_before_sale',true));
 RETURN jsonb_build_object('request_id',v_req.id,'customer_order_id',v_order_id,'sale_id',v_sale_id,
  'status','sold','sale_price_minor',v_sale_price,'default_sale_price_minor',v_invoice_price,'total_minor',v_total::bigint,'currency_code',v_req.currency_code,'replayed',false);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_notify_customer_supply_direct_sale()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_uid uuid;
BEGIN
 IF OLD.direct_sale_status IS DISTINCT FROM NEW.direct_sale_status AND NEW.direct_sale_status='sold' THEN
  SELECT auth_user_id INTO v_uid FROM public.commercial_customers
  WHERE id=NEW.customer_id AND organization_id=NEW.organization_id;
  IF v_uid IS NOT NULL THEN
   INSERT INTO public.commercial_user_notifications(user_id,organization_id,notification_type,title,body,entity_type,entity_id,priority,metadata)
   VALUES(v_uid,NEW.organization_id,'approval','تم شراء الصنف وبيعه على حسابك',
    'اكتملت عملية شراء '||NEW.item_name||' وبيعها وتسجيلها على حسابك. رقم البيع: '||coalesce(NEW.sale_id::text,''),
    'customer_supply_request',NEW.id,'normal',jsonb_build_object('request_id',NEW.id,'sale_id',NEW.sale_id,'status','sold'));
  END IF;
 END IF;
 RETURN NEW;
END;
$function$;
DROP TRIGGER IF EXISTS commercial_customer_supply_direct_sale_notification ON public.commercial_customer_supply_requests;
CREATE TRIGGER commercial_customer_supply_direct_sale_notification
AFTER UPDATE OF direct_sale_status ON public.commercial_customer_supply_requests
FOR EACH ROW EXECUTE FUNCTION public.commercial_notify_customer_supply_direct_sale();

REVOKE ALL ON FUNCTION public.commercial_customer_supply_request_sell_direct(uuid,uuid,bigint,bigint,bigint) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_notify_customer_supply_direct_sale() FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_customer_supply_request_sell_direct(uuid,uuid,bigint,bigint,bigint) TO authenticated;

COMMIT;
