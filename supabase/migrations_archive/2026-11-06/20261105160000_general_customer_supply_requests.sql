BEGIN;

-- General customer requests for products that are not currently offered in the merchant catalogue.
-- These are procurement requests, not sales, purchases, inventory receipts, or accounting entries.
CREATE TABLE public.commercial_customer_supply_requests (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  customer_id uuid NOT NULL REFERENCES public.commercial_customers(id) ON DELETE RESTRICT,
  buyer_account_id uuid NOT NULL REFERENCES public.commercial_party_accounts(id) ON DELETE RESTRICT,
  submitted_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
  item_name text NOT NULL CHECK (length(btrim(item_name)) BETWEEN 1 AND 200),
  specifications text NOT NULL DEFAULT '' CHECK (length(specifications) <= 2000),
  requested_quantity bigint NOT NULL CHECK (requested_quantity > 0 AND requested_quantity <= 1000000),
  unit_code text NOT NULL CHECK (length(btrim(unit_code)) BETWEEN 1 AND 32),
  unit_name text NOT NULL CHECK (length(btrim(unit_name)) BETWEEN 1 AND 80),
  requested_price_minor bigint NOT NULL CHECK (requested_price_minor >= 0),
  currency_code text NOT NULL CHECK (currency_code ~ '^[A-Z]{3}$'),
  payment_mode text NOT NULL CHECK (payment_mode IN ('cash','credit')),
  customer_note text NOT NULL DEFAULT '' CHECK (length(customer_note) <= 1000),
  status text NOT NULL DEFAULT 'pending'
    CHECK (status IN ('pending','accepted','rejected','change_requested','ignored','deferred','converted')),
  merchant_note text NOT NULL DEFAULT '' CHECK (length(merchant_note) <= 1000),
  purchase_order_id uuid REFERENCES public.commercial_purchase_orders(id) ON DELETE RESTRICT,
  idempotency_key text NOT NULL CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 200),
  request_fingerprint text NOT NULL CHECK (length(request_fingerprint)=32),
  version integer NOT NULL DEFAULT 1 CHECK (version > 0),
  reviewed_by uuid REFERENCES auth.users(id) ON DELETE RESTRICT,
  reviewed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(submitted_by,idempotency_key),
  CHECK ((status='converted') = (purchase_order_id IS NOT NULL))
);
CREATE INDEX commercial_customer_supply_requests_org_status_created_idx
 ON public.commercial_customer_supply_requests(organization_id,status,created_at DESC);
CREATE INDEX commercial_customer_supply_requests_customer_created_idx
 ON public.commercial_customer_supply_requests(customer_id,created_at DESC);
ALTER TABLE public.commercial_customer_supply_requests ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.commercial_customer_supply_requests FROM PUBLIC,anon,authenticated;

CREATE TABLE public.commercial_customer_supply_request_events (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 request_id uuid NOT NULL REFERENCES public.commercial_customer_supply_requests(id) ON DELETE RESTRICT,
 organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
 actor_user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
 event_type text NOT NULL CHECK(event_type IN('submitted','accepted','rejected','change_requested','ignored','deferred','resubmitted','converted')),
 note text NOT NULL DEFAULT '' CHECK(length(note)<=1000),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX commercial_customer_supply_request_events_request_idx
 ON public.commercial_customer_supply_request_events(request_id,created_at,id);
ALTER TABLE public.commercial_customer_supply_request_events ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.commercial_customer_supply_request_events FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_submit_supply_request(
 p_organization_id uuid,p_item_name text,p_quantity bigint,p_unit_code text,p_unit_name text,
 p_requested_price_minor bigint,p_payment_mode text,p_specifications text DEFAULT '',
 p_customer_note text DEFAULT '',p_idempotency_key text DEFAULT NULL
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE
 v_uid uuid:=auth.uid(); v_customer_id uuid; v_account_id uuid; v_currency text; v_allow_credit boolean;
 v_fp text; v_existing public.commercial_customer_supply_requests%ROWTYPE; v_id uuid;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 PERFORM public.commercial_customer_portal_link_verified_email();
 IF p_organization_id IS NULL OR length(btrim(coalesce(p_item_name,''))) NOT BETWEEN 1 AND 200
  OR p_quantity IS NULL OR p_quantity<=0 OR p_quantity>1000000
  OR length(btrim(coalesce(p_unit_code,''))) NOT BETWEEN 1 AND 32
  OR length(btrim(coalesce(p_unit_name,''))) NOT BETWEEN 1 AND 80
  OR p_requested_price_minor IS NULL OR p_requested_price_minor<0
  OR p_payment_mode NOT IN('cash','credit')
  OR length(coalesce(p_specifications,''))>2000 OR length(coalesce(p_customer_note,''))>1000
  OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
  RAISE EXCEPTION 'invalid_customer_supply_request' USING ERRCODE='22023';
 END IF;
 SELECT c.id,a.id,a.currency_code,a.allow_credit INTO v_customer_id,v_account_id,v_currency,v_allow_credit
 FROM public.commercial_customers c
 JOIN public.commercial_customer_party_accounts cpa ON cpa.customer_id=c.id AND cpa.organization_id=c.organization_id
 JOIN public.commercial_party_accounts a ON a.id=cpa.party_account_id AND a.organization_id=cpa.organization_id
  AND a.account_type IN('business','organization') AND a.status='active'
 JOIN public.commercial_organizations o ON o.id=c.organization_id AND o.state='active' AND o.deleted_at IS NULL
 WHERE c.organization_id=p_organization_id AND c.auth_user_id=v_uid AND c.state='active'
 LIMIT 1;
 IF v_customer_id IS NULL THEN RAISE EXCEPTION 'customer_not_linked_to_organization' USING ERRCODE='42501'; END IF;
 IF p_payment_mode='credit' AND NOT coalesce(v_allow_credit,false) THEN
  RAISE EXCEPTION 'customer_credit_not_authorized' USING ERRCODE='42501';
 END IF;
 v_fp:=md5(jsonb_build_object('organization_id',p_organization_id,'item_name',btrim(p_item_name),
  'quantity',p_quantity,'unit_code',upper(btrim(p_unit_code)),'unit_name',btrim(p_unit_name),
  'price',p_requested_price_minor,'payment_mode',p_payment_mode,'specifications',coalesce(p_specifications,''),
  'note',coalesce(p_customer_note,''))::text);
 PERFORM pg_advisory_xact_lock(hashtextextended(v_uid::text||':'||btrim(p_idempotency_key),0));
 SELECT * INTO v_existing FROM public.commercial_customer_supply_requests
 WHERE submitted_by=v_uid AND idempotency_key=btrim(p_idempotency_key) FOR UPDATE;
 IF FOUND THEN
  IF v_existing.request_fingerprint IS DISTINCT FROM v_fp THEN
   RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='23505';
  END IF;
  RETURN jsonb_build_object('request_id',v_existing.id,'status',v_existing.status,'replayed',true);
 END IF;
 INSERT INTO public.commercial_customer_supply_requests(
  organization_id,customer_id,buyer_account_id,submitted_by,item_name,specifications,requested_quantity,
  unit_code,unit_name,requested_price_minor,currency_code,payment_mode,customer_note,idempotency_key,request_fingerprint
 ) VALUES(p_organization_id,v_customer_id,v_account_id,v_uid,btrim(p_item_name),coalesce(p_specifications,''),
  p_quantity,upper(btrim(p_unit_code)),btrim(p_unit_name),p_requested_price_minor,v_currency,p_payment_mode,
  coalesce(p_customer_note,''),btrim(p_idempotency_key),v_fp) RETURNING id INTO v_id;
 INSERT INTO public.commercial_customer_supply_request_events(request_id,organization_id,actor_user_id,event_type,note)
 VALUES(v_id,p_organization_id,v_uid,'submitted',coalesce(p_customer_note,''));
 RETURN jsonb_build_object('request_id',v_id,'status','pending','replayed',false);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_list_my_supply_requests(p_organization_id uuid DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_uid uuid:=auth.uid(); v_result jsonb;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 PERFORM public.commercial_customer_portal_link_verified_email();
 SELECT coalesce(jsonb_agg(jsonb_build_object(
  'id',r.id,'organization_id',r.organization_id,'organization_name',o.name,'item_name',r.item_name,
  'specifications',r.specifications,'quantity',r.requested_quantity,'unit_code',r.unit_code,'unit_name',r.unit_name,
  'requested_price_minor',r.requested_price_minor,'currency_code',r.currency_code,'payment_mode',r.payment_mode,
  'customer_note',r.customer_note,'status',r.status,'merchant_note',r.merchant_note,
  'purchase_order_id',r.purchase_order_id,'version',r.version,'created_at',r.created_at,'updated_at',r.updated_at
 ) ORDER BY r.created_at DESC,r.id),'[]'::jsonb) INTO v_result
 FROM public.commercial_customer_supply_requests r
 JOIN public.commercial_customers c ON c.id=r.customer_id AND c.organization_id=r.organization_id
  AND c.auth_user_id=v_uid AND c.state='active'
 JOIN public.commercial_organizations o ON o.id=r.organization_id
 WHERE p_organization_id IS NULL OR r.organization_id=p_organization_id;
 RETURN v_result;
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_list_organization_supply_requests(
 p_organization_id uuid,p_limit integer DEFAULT 100
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_uid uuid:=auth.uid(); v_result jsonb;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 200 OR NOT EXISTS(
  SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id
   AND m.user_id=v_uid AND m.state='active' AND m.role IN('owner','admin','manager')
 ) THEN RAISE EXCEPTION 'customer_supply_request_management_forbidden' USING ERRCODE='42501'; END IF;
 SELECT coalesce(jsonb_agg(jsonb_build_object(
  'id',r.id,'customer_name',c.display_name,'item_name',r.item_name,'specifications',r.specifications,
  'quantity',r.requested_quantity,'unit_code',r.unit_code,'unit_name',r.unit_name,
  'requested_price_minor',r.requested_price_minor,'currency_code',r.currency_code,
  'payment_mode',r.payment_mode,'customer_note',r.customer_note,'status',r.status,
  'merchant_note',r.merchant_note,'purchase_order_id',r.purchase_order_id,
  'version',r.version,'created_at',r.created_at,'updated_at',r.updated_at
 ) ORDER BY r.created_at DESC,r.id),'[]'::jsonb) INTO v_result
 FROM public.commercial_customer_supply_requests r
 JOIN public.commercial_customers c ON c.id=r.customer_id AND c.organization_id=r.organization_id
 WHERE r.organization_id=p_organization_id AND r.status<>'converted'
 LIMIT p_limit;
 RETURN v_result;
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_review_supply_request(
 p_organization_id uuid,p_request_id uuid,p_decision text,p_note text DEFAULT ''
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_uid uuid:=auth.uid(); v_req public.commercial_customer_supply_requests%ROWTYPE;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_decision NOT IN('accept','reject','request_changes','ignore','defer')
  OR length(coalesce(p_note,''))>1000
  OR (p_decision IN('reject','request_changes') AND length(btrim(coalesce(p_note,'')))=0) THEN
  RAISE EXCEPTION 'invalid_customer_supply_request_decision' USING ERRCODE='22023';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id
  AND m.user_id=v_uid AND m.state='active' AND m.role IN('owner','admin','manager')) THEN
  RAISE EXCEPTION 'customer_supply_request_management_forbidden' USING ERRCODE='42501';
 END IF;
 SELECT * INTO v_req FROM public.commercial_customer_supply_requests
 WHERE id=p_request_id AND organization_id=p_organization_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'customer_supply_request_not_found' USING ERRCODE='P0002'; END IF;
 IF v_req.status NOT IN('pending','change_requested','deferred') THEN
  RETURN jsonb_build_object('request_id',v_req.id,'status',v_req.status,'replayed',true);
 END IF;
 UPDATE public.commercial_customer_supply_requests SET
  status=CASE p_decision WHEN 'accept' THEN 'accepted' WHEN 'reject' THEN 'rejected'
   WHEN 'request_changes' THEN 'change_requested' WHEN 'ignore' THEN 'ignored' ELSE 'deferred' END,
  merchant_note=coalesce(p_note,''),reviewed_by=v_uid,reviewed_at=now(),version=version+1,updated_at=now()
 WHERE id=v_req.id;
 INSERT INTO public.commercial_customer_supply_request_events(request_id,organization_id,actor_user_id,event_type,note)
 VALUES(v_req.id,p_organization_id,v_uid,CASE p_decision WHEN 'request_changes' THEN 'change_requested'
  ELSE CASE p_decision WHEN 'accept' THEN 'accepted' WHEN 'reject' THEN 'rejected'
   WHEN 'ignore' THEN 'ignored' ELSE 'deferred' END END,coalesce(p_note,''));
 RETURN jsonb_build_object('request_id',v_req.id,'status',CASE p_decision WHEN 'accept' THEN 'accepted'
  WHEN 'reject' THEN 'rejected' WHEN 'request_changes' THEN 'change_requested' WHEN 'ignore' THEN 'ignored' ELSE 'deferred' END);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_resubmit_supply_request(
 p_request_id uuid,p_item_name text,p_quantity bigint,p_unit_code text,p_unit_name text,
 p_requested_price_minor bigint,p_payment_mode text,p_specifications text DEFAULT '',p_customer_note text DEFAULT ''
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_uid uuid:=auth.uid(); v_req public.commercial_customer_supply_requests%ROWTYPE; v_allow_credit boolean;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF length(btrim(coalesce(p_item_name,''))) NOT BETWEEN 1 AND 200 OR p_quantity NOT BETWEEN 1 AND 1000000
  OR length(btrim(coalesce(p_unit_code,''))) NOT BETWEEN 1 AND 32 OR length(btrim(coalesce(p_unit_name,''))) NOT BETWEEN 1 AND 80
  OR p_requested_price_minor<0 OR p_payment_mode NOT IN('cash','credit')
  OR length(coalesce(p_specifications,''))>2000 OR length(coalesce(p_customer_note,''))>1000 THEN
  RAISE EXCEPTION 'invalid_customer_supply_request' USING ERRCODE='22023';
 END IF;
 SELECT r.* INTO v_req FROM public.commercial_customer_supply_requests r
 JOIN public.commercial_customers c ON c.id=r.customer_id AND c.organization_id=r.organization_id
 WHERE r.id=p_request_id AND c.auth_user_id=v_uid AND c.state='active' FOR UPDATE OF r;
 IF NOT FOUND OR v_req.status<>'change_requested' THEN RAISE EXCEPTION 'customer_supply_request_not_editable' USING ERRCODE='42501'; END IF;
 SELECT a.allow_credit INTO v_allow_credit FROM public.commercial_party_accounts a
 WHERE a.id=v_req.buyer_account_id AND a.organization_id=v_req.organization_id AND a.status='active';
 IF p_payment_mode='credit' AND NOT coalesce(v_allow_credit,false) THEN RAISE EXCEPTION 'customer_credit_not_authorized' USING ERRCODE='42501'; END IF;
 UPDATE public.commercial_customer_supply_requests SET item_name=btrim(p_item_name),requested_quantity=p_quantity,
  unit_code=upper(btrim(p_unit_code)),unit_name=btrim(p_unit_name),requested_price_minor=p_requested_price_minor,
  payment_mode=p_payment_mode,specifications=coalesce(p_specifications,''),customer_note=coalesce(p_customer_note,''),
  status='pending',merchant_note='',reviewed_by=NULL,reviewed_at=NULL,version=version+1,updated_at=now()
 WHERE id=v_req.id;
 INSERT INTO public.commercial_customer_supply_request_events(request_id,organization_id,actor_user_id,event_type,note)
 VALUES(v_req.id,v_req.organization_id,v_uid,'resubmitted',coalesce(p_customer_note,''));
 RETURN jsonb_build_object('request_id',v_req.id,'status','pending');
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_convert_supply_request_to_purchase(
 p_organization_id uuid,p_request_id uuid,p_supplier_id uuid,p_receive_location_id uuid,
 p_order_number text,p_unit_cost_minor bigint,p_notes text DEFAULT ''
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_uid uuid:=auth.uid(); v_req public.commercial_customer_supply_requests%ROWTYPE;
 v_po uuid; v_item uuid; v_supplier_name text;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id
  AND m.user_id=v_uid AND m.state='active' AND m.role IN('owner','admin','manager')) THEN
  RAISE EXCEPTION 'purchase_management_permission_required' USING ERRCODE='42501';
 END IF;
 IF p_order_number IS NULL OR length(btrim(p_order_number)) NOT BETWEEN 1 AND 80
  OR p_unit_cost_minor IS NULL OR p_unit_cost_minor<0 THEN RAISE EXCEPTION 'invalid_supply_purchase_conversion' USING ERRCODE='22023'; END IF;
 SELECT * INTO v_req FROM public.commercial_customer_supply_requests
 WHERE id=p_request_id AND organization_id=p_organization_id FOR UPDATE;
 IF NOT FOUND OR v_req.status NOT IN('accepted','converted') THEN RAISE EXCEPTION 'customer_supply_request_not_accepted' USING ERRCODE='22023'; END IF;
 IF v_req.status='converted' THEN
  RETURN jsonb_build_object('request_id',v_req.id,'purchase_order_id',v_req.purchase_order_id,'replayed',true);
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_suppliers s WHERE s.id=p_supplier_id AND s.organization_id=p_organization_id AND s.state='active')
  OR NOT EXISTS(SELECT 1 FROM public.commercial_organization_locations l WHERE l.id=p_receive_location_id AND l.organization_id=p_organization_id AND l.state='active') THEN
  RAISE EXCEPTION 'supply_purchase_supplier_or_location_invalid' USING ERRCODE='22023';
 END IF;
 INSERT INTO public.commercial_purchase_orders(organization_id,supplier_id,order_number,status,currency_code,
  receive_location_id,notes,created_by,create_idempotency_key,create_request_fingerprint)
 VALUES(p_organization_id,p_supplier_id,btrim(p_order_number),'draft',v_req.currency_code,p_receive_location_id,
  left(concat_ws(E'\n',nullif(btrim(p_notes),''),'طلب توفير عميل: '||v_req.item_name||' — '||v_req.specifications||
   ' — الكمية: '||v_req.requested_quantity||' '||v_req.unit_name),2000),v_uid,
  'customer-supply:'||v_req.id::text,md5(concat_ws('|',v_req.id,p_supplier_id,p_receive_location_id,p_unit_cost_minor)))
 RETURNING id INTO v_po;
 INSERT INTO public.commercial_purchase_order_items(organization_id,purchase_order_id,line_number,item_description,
  quantity_ordered,quantity_received,unit_cost_minor,offering_id,source_selling_unit_code,source_selling_unit_name,
  source_selling_quantity,source_quantity_in_stock_units)
 VALUES(p_organization_id,v_po,1,v_req.item_name,v_req.requested_quantity,0,p_unit_cost_minor,NULL,
  v_req.unit_code,v_req.unit_name,v_req.requested_quantity,v_req.requested_quantity)
 RETURNING id INTO v_item;
 UPDATE public.commercial_customer_supply_requests SET status='converted',purchase_order_id=v_po,
  reviewed_by=v_uid,reviewed_at=now(),updated_at=now(),version=version+1 WHERE id=v_req.id;
 INSERT INTO public.commercial_customer_supply_request_events(request_id,organization_id,actor_user_id,event_type,note)
 VALUES(v_req.id,p_organization_id,v_uid,'converted','purchase_order_id='||v_po::text);
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(p_organization_id,v_uid,'customer_supply_request.converted','purchase_order',v_po::text,
  jsonb_build_object('request_id',v_req.id,'customer_id',v_req.customer_id,'stock_received',false,'accounting_posted',false));
 RETURN jsonb_build_object('request_id',v_req.id,'purchase_order_id',v_po,'purchase_order_item_id',v_item,'status','converted','stock_received',false,'accounting_posted',false);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_notify_customer_supply_request_event()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_title text; v_body text; v_target_org uuid; v_customer_uid uuid;
BEGIN
 IF TG_OP='INSERT' THEN
  v_title:='طلب توفير صنف جديد'; v_body:='طلب العميل '||NEW.item_name||' بكمية '||NEW.requested_quantity||' '||NEW.unit_name||'. راجع الطلب.';
  INSERT INTO public.commercial_user_notifications(user_id,organization_id,notification_type,title,body,entity_type,entity_id,priority,metadata)
  SELECT DISTINCT m.user_id,NEW.organization_id,'approval',v_title,left(v_body,4000),'customer_supply_request',NEW.id,'high',
   jsonb_build_object('request_id',NEW.id,'status',NEW.status)
  FROM public.commercial_organization_members m WHERE m.organization_id=NEW.organization_id AND m.state='active'
   AND m.role IN('owner','admin','manager');
 ELSIF OLD.status IS DISTINCT FROM NEW.status THEN
  SELECT auth_user_id INTO v_customer_uid FROM public.commercial_customers WHERE id=NEW.customer_id AND organization_id=NEW.organization_id;
  IF v_customer_uid IS NULL THEN RETURN NEW; END IF;
  v_title:=CASE NEW.status WHEN 'accepted' THEN 'تم قبول طلب التوفير' WHEN 'rejected' THEN 'تم رفض طلب التوفير'
   WHEN 'change_requested' THEN 'طلب التاجر تعديل الطلب' WHEN 'ignored' THEN 'تم تجاهل طلب التوفير'
   WHEN 'deferred' THEN 'تم تأجيل طلب التوفير' WHEN 'converted' THEN 'تم تحويل طلبك إلى أمر شراء' ELSE 'تحديث طلب التوفير' END;
  v_body:=CASE NEW.status WHEN 'accepted' THEN 'قبل التاجر طلب توفير '||NEW.item_name||'.'
   WHEN 'rejected' THEN 'رفض التاجر طلب توفير '||NEW.item_name||'. السبب: '||NEW.merchant_note
   WHEN 'change_requested' THEN 'طلب التاجر تعديل طلب '||NEW.item_name||': '||NEW.merchant_note
   WHEN 'ignored' THEN 'تم تجاهل طلب توفير '||NEW.item_name||'.'
   WHEN 'deferred' THEN 'تم تأجيل طلب توفير '||NEW.item_name||'.'
   WHEN 'converted' THEN 'حوّل التاجر طلب '||NEW.item_name||' إلى أمر شراء للمتابعة.'
   ELSE 'تم تحديث حالة طلب التوفير.' END;
  INSERT INTO public.commercial_user_notifications(user_id,organization_id,notification_type,title,body,entity_type,entity_id,priority,metadata)
  VALUES(v_customer_uid,NEW.organization_id,'approval',v_title,left(v_body,4000),'customer_supply_request',NEW.id,'normal',
   jsonb_build_object('request_id',NEW.id,'status',NEW.status,'merchant_note',NEW.merchant_note));
 END IF;
 RETURN NEW;
END;
$function$;
CREATE TRIGGER commercial_customer_supply_request_notification
AFTER INSERT OR UPDATE OF status ON public.commercial_customer_supply_requests
FOR EACH ROW EXECUTE FUNCTION public.commercial_notify_customer_supply_request_event();

REVOKE ALL ON FUNCTION public.commercial_customer_portal_submit_supply_request(uuid,text,bigint,text,text,bigint,text,text,text,text) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_list_my_supply_requests(uuid) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_list_organization_supply_requests(uuid,integer) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_review_supply_request(uuid,uuid,text,text) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_resubmit_supply_request(uuid,text,bigint,text,text,bigint,text,text,text) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_convert_supply_request_to_purchase(uuid,uuid,uuid,uuid,text,bigint,text) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_notify_customer_supply_request_event() FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_submit_supply_request(uuid,text,bigint,text,text,bigint,text,text,text,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_list_my_supply_requests(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_list_organization_supply_requests(uuid,integer) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_review_supply_request(uuid,uuid,text,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_resubmit_supply_request(uuid,text,bigint,text,text,bigint,text,text,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_convert_supply_request_to_purchase(uuid,uuid,uuid,uuid,text,bigint,text) TO authenticated;

COMMIT;
