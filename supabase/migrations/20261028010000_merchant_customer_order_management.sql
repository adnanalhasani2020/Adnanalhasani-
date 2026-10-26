-- Merchant-side lifecycle for customer portal orders and bookings.
-- All state transitions are organization-scoped, permission-checked, audited and transactional.

ALTER TABLE public.commercial_customer_orders
  ADD COLUMN IF NOT EXISTS merchant_hidden_at timestamptz,
  ADD COLUMN IF NOT EXISTS merchant_hidden_by uuid REFERENCES auth.users(id) ON DELETE RESTRICT,
  ADD COLUMN IF NOT EXISTS merchant_reviewed_at timestamptz,
  ADD COLUMN IF NOT EXISTS merchant_reviewed_by uuid REFERENCES auth.users(id) ON DELETE RESTRICT,
  ADD COLUMN IF NOT EXISTS merchant_decision_reason text;

ALTER TABLE public.commercial_bookings
  ADD COLUMN IF NOT EXISTS merchant_hidden_at timestamptz,
  ADD COLUMN IF NOT EXISTS merchant_hidden_by uuid REFERENCES auth.users(id) ON DELETE RESTRICT,
  ADD COLUMN IF NOT EXISTS merchant_reviewed_at timestamptz,
  ADD COLUMN IF NOT EXISTS merchant_reviewed_by uuid REFERENCES auth.users(id) ON DELETE RESTRICT,
  ADD COLUMN IF NOT EXISTS merchant_decision_reason text;

CREATE INDEX IF NOT EXISTS commercial_customer_orders_merchant_queue_idx
  ON public.commercial_customer_orders(organization_id, merchant_hidden_at, created_at DESC);
CREATE INDEX IF NOT EXISTS commercial_bookings_merchant_queue_idx
  ON public.commercial_bookings(organization_id, merchant_hidden_at, starts_at);

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_list_organization_orders_v2(
  p_organization_id uuid, p_limit integer DEFAULT 200, p_include_hidden boolean DEFAULT false
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active'
      AND m.role IN ('owner','admin','manager')
  ) THEN RAISE EXCEPTION 'customer_order_management_permission_required' USING ERRCODE='42501'; END IF;
  IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 200 THEN RAISE EXCEPTION 'invalid_customer_order_list_limit' USING ERRCODE='22023'; END IF;

  SELECT coalesce(jsonb_agg(row_data ORDER BY created_at DESC, id DESC),'[]'::jsonb)
  INTO v_result
  FROM (
    SELECT o.id,o.created_at,jsonb_build_object(
      'id',o.id,'organization_id',o.organization_id,'order_number',o.order_number,
      'created_at',o.created_at,'updated_at',o.updated_at,'status',o.status,
      'payment_status',o.payment_status,'order_type',o.order_type,'currency_code',o.currency_code,
      'total_minor',o.total_minor,'customer_note',coalesce(o.customer_note,''),
      'customer_name',coalesce(c.display_name,''),'merchant_hidden',o.merchant_hidden_at IS NOT NULL,
      'merchant_hidden_at',o.merchant_hidden_at,'merchant_reviewed_at',o.merchant_reviewed_at,
      'merchant_decision_reason',coalesce(o.merchant_decision_reason,''),
      'fulfillment',(
        SELECT jsonb_build_object('sale_id',f.sale_id,'receipt_number',f.result->'receipt_number',
          'fulfilled_at',f.fulfilled_at,'fulfilled_by',f.fulfilled_by,'location_id',f.location_id,
          'paid_minor',f.result->'paid_minor','outstanding_minor',f.result->'outstanding_minor',
          'journal_entry_id',f.result->'journal_entry_id')
        FROM public.commercial_customer_order_fulfillments f WHERE f.order_id=o.id
      ),
      'fulfillment_supported',(
        o.order_type='purchase' AND o.payment_status IN ('unpaid','credit')
        AND NOT EXISTS(SELECT 1 FROM public.commercial_customer_order_items x WHERE x.order_id=o.id
          AND (x.item_type<>'product' OR x.quantity<=0 OR x.quantity<>trunc(x.quantity) OR x.quantity>100000))
        AND EXISTS(SELECT 1 FROM public.commercial_customer_order_items x WHERE x.order_id=o.id)
        AND (SELECT count(DISTINCT a.id) FROM public.commercial_customer_order_items x
          JOIN public.commercial_offerings ofr ON ofr.id=x.offering_id
          JOIN public.commercial_activities a ON a.id=ofr.activity_id
          WHERE x.order_id=o.id AND a.organization_id=o.organization_id)=1
      ),
      'items',coalesce((SELECT jsonb_agg(jsonb_build_object(
        'offering_id',oi.offering_id,'name',oi.item_name_snapshot,'item_type',oi.item_type,
        'quantity',oi.quantity,'unit_price_minor',oi.unit_price_minor,'line_total_minor',oi.line_total_minor
      ) ORDER BY oi.created_at,oi.id) FROM public.commercial_customer_order_items oi WHERE oi.order_id=o.id),'[]'::jsonb)
    ) row_data
    FROM public.commercial_customer_orders o
    LEFT JOIN public.commercial_customer_party_accounts cpa
      ON cpa.organization_id=o.organization_id AND cpa.party_account_id=o.buyer_account_id
    LEFT JOIN public.commercial_customers c ON c.id=cpa.customer_id AND c.organization_id=cpa.organization_id
    WHERE o.organization_id=p_organization_id
      AND (p_include_hidden OR o.merchant_hidden_at IS NULL)
    ORDER BY o.created_at DESC,o.id DESC LIMIT p_limit
  ) q;
  RETURN v_result;
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_list_organization_orders_v2(uuid,integer,boolean) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_list_organization_orders_v2(uuid,integer,boolean) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_list_organization_bookings(
  p_organization_id uuid, p_limit integer DEFAULT 200, p_include_hidden boolean DEFAULT false
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id
      AND m.user_id=v_uid AND m.state='active' AND m.role IN ('owner','admin','manager')
  ) THEN RAISE EXCEPTION 'customer_order_management_permission_required' USING ERRCODE='42501'; END IF;
  IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 200 THEN RAISE EXCEPTION 'invalid_customer_order_list_limit' USING ERRCODE='22023'; END IF;
  SELECT coalesce(jsonb_agg(row_data ORDER BY starts_at DESC,id DESC),'[]'::jsonb) INTO v_result
  FROM (
    SELECT b.id,b.starts_at,jsonb_build_object(
      'id',b.id,'organization_id',b.organization_id,'booking_number',b.booking_number,
      'order_id',b.order_id,'order_number',o.order_number,'created_at',b.created_at,
      'starts_at',b.starts_at,'ends_at',b.ends_at,'status',b.status,'currency_code',b.currency_code,
      'price_minor',b.price_minor,'notes',coalesce(b.notes,''),'customer_name',coalesce(c.display_name,''),
      'resource_name',coalesce(r.name,''),'timezone',coalesce(r.timezone,'UTC'),'order_status',coalesce(o.status,'missing'),
      'payment_status',coalesce(o.payment_status,'unknown'),'merchant_hidden',b.merchant_hidden_at IS NOT NULL,
      'merchant_decision_reason',coalesce(b.merchant_decision_reason,''),
      'items',coalesce((SELECT jsonb_agg(jsonb_build_object(
        'name',oi.item_name_snapshot,'quantity',oi.quantity,'unit_price_minor',oi.unit_price_minor,
        'line_total_minor',oi.line_total_minor,'item_type',oi.item_type
      ) ORDER BY oi.created_at,oi.id) FROM public.commercial_customer_order_items oi WHERE oi.order_id=b.order_id),'[]'::jsonb)
    ) row_data
    FROM public.commercial_bookings b
    LEFT JOIN public.commercial_booking_resources r ON r.id=b.resource_id AND r.organization_id=b.organization_id
    LEFT JOIN public.commercial_customer_orders o ON o.id=b.order_id AND o.organization_id=b.organization_id
    LEFT JOIN public.commercial_customer_party_accounts cpa ON cpa.organization_id=b.organization_id AND cpa.party_account_id=b.customer_account_id
    LEFT JOIN public.commercial_customers c ON c.id=cpa.customer_id AND c.organization_id=cpa.organization_id
    WHERE b.organization_id=p_organization_id AND (p_include_hidden OR b.merchant_hidden_at IS NULL)
    ORDER BY b.starts_at DESC,b.id DESC LIMIT p_limit
  ) q;
  RETURN v_result;
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_list_organization_bookings(uuid,integer,boolean) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_list_organization_bookings(uuid,integer,boolean) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_decide_order(
  p_organization_id uuid,p_order_id uuid,p_decision text,p_reason text DEFAULT NULL
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_order public.commercial_customer_orders%ROWTYPE; v_next text; v_reason text:=nullif(btrim(p_reason),'');
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_decision IS NULL OR p_decision NOT IN ('accept','reject') OR p_order_id IS NULL OR p_organization_id IS NULL
     OR (v_reason IS NOT NULL AND length(v_reason)>500) THEN RAISE EXCEPTION 'invalid_customer_order_decision' USING ERRCODE='22023'; END IF;
  IF p_decision='reject' AND v_reason IS NULL THEN RAISE EXCEPTION 'rejection_reason_required' USING ERRCODE='22023'; END IF;
  IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id
    AND m.user_id=v_uid AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN
    RAISE EXCEPTION 'customer_order_management_permission_required' USING ERRCODE='42501';
  END IF;
  SELECT * INTO v_order FROM public.commercial_customer_orders o
    WHERE o.id=p_order_id AND o.organization_id=p_organization_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'customer_order_not_found' USING ERRCODE='P0002'; END IF;
  IF v_order.status<>'pending' THEN RAISE EXCEPTION 'customer_order_decision_not_allowed_for_status' USING ERRCODE='22023'; END IF;
  IF v_order.payment_status NOT IN ('unpaid','credit') THEN RAISE EXCEPTION 'customer_order_decision_not_allowed_after_payment' USING ERRCODE='22023'; END IF;
  v_next:=CASE WHEN p_decision='accept' THEN 'confirmed' ELSE 'rejected' END;
  UPDATE public.commercial_customer_orders SET status=v_next,merchant_reviewed_at=now(),
    merchant_reviewed_by=v_uid,merchant_decision_reason=v_reason,updated_at=now()
  WHERE id=v_order.id AND organization_id=p_organization_id AND status='pending';
  IF NOT FOUND THEN RAISE EXCEPTION 'customer_order_decision_race_retry' USING ERRCODE='40001'; END IF;
  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
  VALUES(p_organization_id,v_uid,'customer_order.'||p_decision,'customer_order',v_order.id::text,
    jsonb_build_object('previous_status',v_order.status,'new_status',v_next,'reason',v_reason,'total_minor',v_order.total_minor));
  RETURN jsonb_build_object('order_id',v_order.id,'order_number',v_order.order_number,'status',v_next,'reason',v_reason);
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_decide_order(uuid,uuid,text,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_decide_order(uuid,uuid,text,text) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_set_order_hidden(
  p_organization_id uuid,p_order_id uuid,p_hidden boolean
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_order public.commercial_customer_orders%ROWTYPE;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR p_order_id IS NULL OR p_hidden IS NULL THEN RAISE EXCEPTION 'invalid_order_visibility_request' USING ERRCODE='22023'; END IF;
  IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id
    AND m.user_id=v_uid AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN
    RAISE EXCEPTION 'customer_order_management_permission_required' USING ERRCODE='42501';
  END IF;
  SELECT * INTO v_order FROM public.commercial_customer_orders o WHERE o.id=p_order_id AND o.organization_id=p_organization_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'customer_order_not_found' USING ERRCODE='P0002'; END IF;
  UPDATE public.commercial_customer_orders SET merchant_hidden_at=CASE WHEN p_hidden THEN now() ELSE NULL END,
    merchant_hidden_by=CASE WHEN p_hidden THEN v_uid ELSE NULL END,updated_at=now()
  WHERE id=v_order.id AND organization_id=p_organization_id;
  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
  VALUES(p_organization_id,v_uid,CASE WHEN p_hidden THEN 'customer_order.hidden' ELSE 'customer_order.restored' END,
    'customer_order',v_order.id::text,jsonb_build_object('previous_hidden',v_order.merchant_hidden_at IS NOT NULL,'hidden',p_hidden,'status_unchanged',true));
  RETURN jsonb_build_object('order_id',v_order.id,'hidden',p_hidden,'status',v_order.status);
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_set_order_hidden(uuid,uuid,boolean) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_set_order_hidden(uuid,uuid,boolean) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_edit_order_lines(
  p_organization_id uuid,p_order_id uuid,p_lines jsonb,p_reason text DEFAULT NULL
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
  v_uid uuid:=auth.uid(); v_order public.commercial_customer_orders%ROWTYPE;
  v_line jsonb; v_offering public.commercial_offerings%ROWTYPE; v_qty numeric;
  v_total numeric:=0; v_count integer:=0; v_currency text; v_before jsonb; v_after jsonb;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR p_order_id IS NULL OR p_lines IS NULL OR jsonb_typeof(p_lines)<>'array'
    OR jsonb_array_length(p_lines)<1 OR jsonb_array_length(p_lines)>100
    OR (p_reason IS NOT NULL AND length(p_reason)>500) THEN RAISE EXCEPTION 'invalid_customer_order_lines' USING ERRCODE='22023'; END IF;
  IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id
    AND m.user_id=v_uid AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN
    RAISE EXCEPTION 'customer_order_management_permission_required' USING ERRCODE='42501';
  END IF;
  SELECT * INTO v_order FROM public.commercial_customer_orders o WHERE o.id=p_order_id AND o.organization_id=p_organization_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'customer_order_not_found' USING ERRCODE='P0002'; END IF;
  IF v_order.status<>'pending' THEN RAISE EXCEPTION 'customer_order_lines_locked_after_review' USING ERRCODE='22023'; END IF;
  IF v_order.order_type IS DISTINCT FROM 'purchase' THEN RAISE EXCEPTION 'customer_order_type_not_editable' USING ERRCODE='22023'; END IF;
  IF EXISTS(SELECT 1 FROM public.commercial_customer_order_fulfillments f WHERE f.order_id=v_order.id) THEN
    RAISE EXCEPTION 'customer_order_lines_locked_after_fulfillment' USING ERRCODE='22023';
  END IF;
  SELECT coalesce(jsonb_agg(jsonb_build_object('offering_id',oi.offering_id,'quantity',oi.quantity,
    'unit_price_minor',oi.unit_price_minor,'line_total_minor',oi.line_total_minor,'name',oi.item_name_snapshot)
    ORDER BY oi.created_at,oi.id),'[]'::jsonb) INTO v_before
  FROM public.commercial_customer_order_items oi WHERE oi.order_id=v_order.id;
  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines) LOOP
    IF jsonb_typeof(v_line)<>'object' OR coalesce(v_line->>'offering_id','') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
      RAISE EXCEPTION 'invalid_customer_order_line' USING ERRCODE='22023'; END IF;
    BEGIN v_qty:=(v_line->>'quantity')::numeric;
    EXCEPTION WHEN OTHERS THEN RAISE EXCEPTION 'invalid_customer_order_quantity' USING ERRCODE='22023'; END;
    IF v_qty<=0 OR v_qty>100000 OR v_qty<>trunc(v_qty) THEN RAISE EXCEPTION 'invalid_customer_order_quantity' USING ERRCODE='22023'; END IF;
    SELECT f.* INTO v_offering FROM public.commercial_offerings f
      JOIN public.commercial_activities a ON a.id=f.activity_id
      JOIN public.commercial_products p ON p.id=f.product_id
      WHERE f.id=(v_line->>'offering_id')::uuid AND a.organization_id=p_organization_id
        AND a.state='active' AND a.deleted_at IS NULL AND a.activity_type='product'
        AND f.state='active' AND f.valid_from<=now() AND (f.valid_to IS NULL OR f.valid_to>now())
        AND p.state='active' AND upper(f.currency_code)=upper(v_order.currency_code)
      FOR SHARE OF f,a,p;
    IF NOT FOUND THEN RAISE EXCEPTION 'customer_order_offering_not_available' USING ERRCODE='22023'; END IF;
    IF v_currency IS NULL THEN v_currency:=v_offering.currency_code;
    ELSIF upper(v_currency)<>upper(v_offering.currency_code) THEN RAISE EXCEPTION 'customer_order_currency_mismatch' USING ERRCODE='22023'; END IF;
    v_total:=v_total+(v_qty*v_offering.price_minor);
    v_count:=v_count+1;
  END LOOP;
  IF (SELECT count(DISTINCT value->>'offering_id') FROM jsonb_array_elements(p_lines))<>v_count THEN
    RAISE EXCEPTION 'duplicate_customer_order_offering' USING ERRCODE='22023'; END IF;
  DELETE FROM public.commercial_customer_order_items WHERE order_id=v_order.id;
  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines) LOOP
    SELECT f.* INTO v_offering FROM public.commercial_offerings f
      JOIN public.commercial_activities a ON a.id=f.activity_id
      JOIN public.commercial_products p ON p.id=f.product_id
      WHERE f.id=(v_line->>'offering_id')::uuid AND a.organization_id=p_organization_id
        AND a.state='active' AND a.deleted_at IS NULL AND a.activity_type='product'
        AND f.state='active' AND f.valid_from<=now() AND (f.valid_to IS NULL OR f.valid_to>now())
        AND p.state='active' AND upper(f.currency_code)=upper(v_order.currency_code);
    v_qty:=(v_line->>'quantity')::numeric;
    INSERT INTO public.commercial_customer_order_items(order_id,offering_id,item_type,item_name_snapshot,quantity,unit_price_minor,line_total_minor)
    SELECT v_order.id,f.id,'product',p.name,v_qty,f.price_minor,(v_qty*f.price_minor)::bigint
    FROM public.commercial_offerings f JOIN public.commercial_products p ON p.id=f.product_id WHERE f.id=v_offering.id;
  END LOOP;
  UPDATE public.commercial_customer_orders SET total_minor=v_total::bigint,updated_at=now()
    WHERE id=v_order.id AND organization_id=p_organization_id AND status='pending';
  IF NOT FOUND THEN RAISE EXCEPTION 'customer_order_edit_race_retry' USING ERRCODE='40001'; END IF;
  SELECT coalesce(jsonb_agg(jsonb_build_object('offering_id',oi.offering_id,'quantity',oi.quantity,
    'unit_price_minor',oi.unit_price_minor,'line_total_minor',oi.line_total_minor,'name',oi.item_name_snapshot)
    ORDER BY oi.created_at,oi.id),'[]'::jsonb) INTO v_after
  FROM public.commercial_customer_order_items oi WHERE oi.order_id=v_order.id;
  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
  VALUES(p_organization_id,v_uid,'customer_order.lines_edited','customer_order',v_order.id::text,
    jsonb_build_object('previous_total_minor',v_order.total_minor,'new_total_minor',v_total::bigint,'reason',nullif(btrim(p_reason),''),'before',v_before,'after',v_after));
  RETURN jsonb_build_object('order_id',v_order.id,'status','pending','previous_total_minor',v_order.total_minor,
    'total_minor',v_total::bigint,'currency_code',v_order.currency_code,'items',v_after);
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_edit_order_lines(uuid,uuid,jsonb,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_edit_order_lines(uuid,uuid,jsonb,text) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_list_editable_offerings(
  p_organization_id uuid
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN
    RAISE EXCEPTION 'customer_order_management_permission_required' USING ERRCODE='42501'; END IF;
  SELECT coalesce(jsonb_agg(jsonb_build_object('id',f.id,'name',p.name,'price_minor',f.price_minor,
    'currency_code',f.currency_code,'unit_code',coalesce(p.unit_name,'EA')) ORDER BY p.name),'[]'::jsonb)
  INTO v_result FROM public.commercial_offerings f
  JOIN public.commercial_activities a ON a.id=f.activity_id
  JOIN public.commercial_products p ON p.id=f.product_id
  WHERE a.organization_id=p_organization_id AND a.state='active' AND a.deleted_at IS NULL
    AND a.activity_type='product' AND f.state='active' AND f.valid_from<=now()
    AND (f.valid_to IS NULL OR f.valid_to>now()) AND p.state='active';
  RETURN v_result;
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_list_editable_offerings(uuid) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_list_editable_offerings(uuid) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_set_booking_hidden(
 p_organization_id uuid,p_booking_id uuid,p_hidden boolean
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_booking public.commercial_bookings%ROWTYPE;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_organization_id IS NULL OR p_booking_id IS NULL OR p_hidden IS NULL THEN RAISE EXCEPTION 'invalid_booking_visibility_request' USING ERRCODE='22023'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id AND m.user_id=v_uid
   AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN RAISE EXCEPTION 'customer_order_management_permission_required' USING ERRCODE='42501'; END IF;
 SELECT * INTO v_booking FROM public.commercial_bookings b WHERE b.id=p_booking_id AND b.organization_id=p_organization_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'customer_booking_not_found' USING ERRCODE='P0002'; END IF;
 UPDATE public.commercial_bookings SET merchant_hidden_at=CASE WHEN p_hidden THEN now() ELSE NULL END,
   merchant_hidden_by=CASE WHEN p_hidden THEN v_uid ELSE NULL END,updated_at=now()
 WHERE id=v_booking.id AND organization_id=p_organization_id;
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(p_organization_id,v_uid,CASE WHEN p_hidden THEN 'customer_booking.hidden' ELSE 'customer_booking.restored' END,
   'customer_booking',v_booking.id::text,jsonb_build_object('previous_hidden',v_booking.merchant_hidden_at IS NOT NULL,'hidden',p_hidden,'status_unchanged',true));
 RETURN jsonb_build_object('booking_id',v_booking.id,'hidden',p_hidden,'status',v_booking.status);
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_set_booking_hidden(uuid,uuid,boolean) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_set_booking_hidden(uuid,uuid,boolean) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_decide_booking(
 p_organization_id uuid,p_booking_id uuid,p_decision text,p_reason text DEFAULT NULL
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_booking public.commercial_bookings%ROWTYPE; v_order public.commercial_customer_orders%ROWTYPE;
 v_next text; v_reason text:=nullif(btrim(p_reason),''); v_resource public.commercial_booking_resources%ROWTYPE;
 v_requested numeric:=0; v_used numeric:=0;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_organization_id IS NULL OR p_booking_id IS NULL OR p_decision IS NULL OR p_decision NOT IN ('accept','reject')
   OR (v_reason IS NOT NULL AND length(v_reason)>500) THEN RAISE EXCEPTION 'invalid_customer_booking_decision' USING ERRCODE='22023'; END IF;
 IF p_decision='reject' AND v_reason IS NULL THEN RAISE EXCEPTION 'rejection_reason_required' USING ERRCODE='22023'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id AND m.user_id=v_uid
   AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN RAISE EXCEPTION 'customer_order_management_permission_required' USING ERRCODE='42501'; END IF;
 SELECT * INTO v_booking FROM public.commercial_bookings b WHERE b.id=p_booking_id AND b.organization_id=p_organization_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'customer_booking_not_found' USING ERRCODE='P0002'; END IF;
 IF v_booking.status<>'requested' THEN RAISE EXCEPTION 'customer_booking_decision_not_allowed_for_status' USING ERRCODE='22023'; END IF;
 IF v_booking.order_id IS NULL THEN RAISE EXCEPTION 'customer_booking_order_missing' USING ERRCODE='22023'; END IF;
 SELECT * INTO v_order FROM public.commercial_customer_orders o WHERE o.id=v_booking.order_id
   AND o.organization_id=p_organization_id AND o.buyer_account_id=v_booking.customer_account_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'customer_booking_order_not_found' USING ERRCODE='22023'; END IF;
 IF v_order.status<>'pending' OR v_order.payment_status NOT IN ('unpaid','credit') THEN
   RAISE EXCEPTION 'customer_booking_order_state_not_decidable' USING ERRCODE='22023'; END IF;
 v_next:=CASE WHEN p_decision='accept' THEN 'confirmed' ELSE 'rejected' END;
 IF p_decision='accept' THEN
   SELECT * INTO v_resource FROM public.commercial_booking_resources r WHERE r.id=v_booking.resource_id
     AND r.organization_id=p_organization_id AND r.status='active' FOR UPDATE;
   IF NOT FOUND THEN RAISE EXCEPTION 'booking_resource_not_available' USING ERRCODE='22023'; END IF;
   SELECT coalesce(sum(oi.quantity),0) INTO v_requested FROM public.commercial_customer_order_items oi
     WHERE oi.order_id=v_order.id AND oi.item_type='service';
   IF v_requested<=0 THEN v_requested:=1; END IF;
   SELECT coalesce(sum(x.units),0) INTO v_used FROM (
     SELECT b.id,CASE WHEN count(oi.id) FILTER(WHERE oi.item_type='service')>0
       THEN sum(oi.quantity) FILTER(WHERE oi.item_type='service') ELSE 1::numeric END units
     FROM public.commercial_bookings b LEFT JOIN public.commercial_customer_order_items oi ON oi.order_id=b.order_id
     WHERE b.organization_id=p_organization_id AND b.resource_id=v_booking.resource_id
       AND b.id<>v_booking.id AND b.status IN ('requested','confirmed','checked_in')
       AND b.starts_at<v_booking.ends_at AND b.ends_at>v_booking.starts_at GROUP BY b.id
   ) x;
   IF v_used+v_requested>v_resource.capacity THEN RAISE EXCEPTION 'booking_resource_capacity_exceeded' USING ERRCODE='22023'; END IF;
 END IF;
 UPDATE public.commercial_bookings SET status=v_next,merchant_reviewed_at=now(),merchant_reviewed_by=v_uid,
   merchant_decision_reason=v_reason,updated_at=now()
 WHERE id=v_booking.id AND organization_id=p_organization_id AND status='requested';
 IF NOT FOUND THEN RAISE EXCEPTION 'customer_booking_decision_race_retry' USING ERRCODE='40001'; END IF;
 UPDATE public.commercial_customer_orders SET status=v_next,merchant_reviewed_at=now(),merchant_reviewed_by=v_uid,
   merchant_decision_reason=v_reason,updated_at=now()
 WHERE id=v_order.id AND organization_id=p_organization_id AND status='pending';
 IF NOT FOUND THEN RAISE EXCEPTION 'customer_booking_order_decision_race_retry' USING ERRCODE='40001'; END IF;
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(p_organization_id,v_uid,'customer_booking.'||p_decision,'customer_booking',v_booking.id::text,
   jsonb_build_object('order_id',v_order.id,'previous_booking_status',v_booking.status,'previous_order_status',v_order.status,
     'new_status',v_next,'reason',v_reason,'resource_id',v_booking.resource_id,'starts_at',v_booking.starts_at,'ends_at',v_booking.ends_at));
 RETURN jsonb_build_object('booking_id',v_booking.id,'booking_number',v_booking.booking_number,'order_id',v_order.id,'status',v_next,'order_status',v_next,'reason',v_reason);
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_decide_booking(uuid,uuid,text,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_decide_booking(uuid,uuid,text,text) TO authenticated;
