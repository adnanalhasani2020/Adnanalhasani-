-- Delegated customer orders: parent pays, child acts, merchant policy and spend cap are enforced atomically.
-- This function intentionally duplicates the validated customer-order transaction so it can bind the
-- merchant-specific receivable account to the parent while recording the acting child account.
CREATE OR REPLACE FUNCTION public.commercial_customer_portal_create_delegated_order(p_delegation_id uuid, p_organization_id uuid, p_lines jsonb, p_payment_mode text, p_customer_note text DEFAULT NULL::text, p_reserved_from timestamp with time zone DEFAULT NULL::timestamp with time zone, p_reserved_until timestamp with time zone DEFAULT NULL::timestamp with time zone, p_idempotency_key text DEFAULT NULL::text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_parent_uid uuid;
  v_parent_personal_account_id uuid;
  v_child_personal_account_id uuid;
  v_delegation public.commercial_account_delegations%ROWTYPE;
  v_account_id uuid;
  v_customer_id uuid;
  v_account_currency text;
  v_credit_limit_minor bigint;
  v_posted_balance numeric := 0;
  v_open_credit_orders numeric := 0;
  v_currency text;
  v_order_type text := 'purchase';
  v_subtotal bigint := 0;
  v_line jsonb;
  v_offering record;
  v_qty numeric;
  v_line_total bigint;
  v_order_id uuid;
  v_existing public.commercial_customer_orders%ROWTYPE;
  v_key text := nullif(btrim(p_idempotency_key), '');
  v_count integer := 0;
  v_allow_credit boolean := false;
  v_existing_count integer;
  v_matching_count integer;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  SELECT d, parent_account.owner_user_id, child_account.id
    INTO v_delegation, v_parent_uid, v_child_personal_account_id
  FROM public.commercial_account_delegations d
  JOIN public.commercial_party_accounts parent_account ON parent_account.id=d.parent_account_id
  JOIN public.commercial_party_accounts child_account ON child_account.id=d.child_account_id
  WHERE d.id=p_delegation_id AND d.organization_id=p_organization_id
    AND d.status='active' AND (d.expires_at IS NULL OR d.expires_at > now())
    AND parent_account.account_type='personal' AND parent_account.status='active'
    AND child_account.account_type='personal' AND child_account.status='active'
    AND child_account.owner_user_id=v_uid
  FOR UPDATE OF d;
  IF NOT FOUND THEN RAISE EXCEPTION 'delegated_access_not_active_or_not_owned' USING ERRCODE='42501'; END IF;
  SELECT parent_account.owner_user_id, child_account.id
    INTO v_parent_uid, v_child_personal_account_id
  FROM public.commercial_party_accounts parent_account
  JOIN public.commercial_party_accounts child_account ON child_account.id=v_delegation.child_account_id
  WHERE parent_account.id=v_delegation.parent_account_id;
  v_parent_personal_account_id := v_delegation.parent_account_id;
  IF p_reserved_from IS NOT NULL THEN
    IF NOT coalesce((v_delegation.permissions->>'book')::boolean,false) THEN
      RAISE EXCEPTION 'delegated_booking_permission_required' USING ERRCODE='42501';
    END IF;
  ELSE
    IF NOT coalesce((v_delegation.permissions->>'purchase')::boolean,false) THEN
      RAISE EXCEPTION 'delegated_purchase_permission_required' USING ERRCODE='42501';
    END IF;
  END IF;
  -- The parent was linked by the merchant approval flow; do not auto-link the child to a separate customer account.
  IF p_organization_id IS NULL THEN RAISE EXCEPTION 'organization_required' USING ERRCODE='22023'; END IF;
  IF p_payment_mode IS NULL OR p_payment_mode NOT IN ('cash','credit') THEN RAISE EXCEPTION 'invalid_payment_mode' USING ERRCODE='22023'; END IF;
  IF v_key IS NULL OR length(v_key) < 8 OR length(v_key) > 200 THEN RAISE EXCEPTION 'invalid_idempotency_key' USING ERRCODE='22023'; END IF;
  IF p_lines IS NULL OR jsonb_typeof(p_lines) <> 'array' OR jsonb_array_length(p_lines) < 1 OR jsonb_array_length(p_lines) > 100 THEN
    RAISE EXCEPTION 'invalid_order_lines' USING ERRCODE='22023';
  END IF;
  IF p_customer_note IS NOT NULL AND length(p_customer_note) > 1000 THEN RAISE EXCEPTION 'customer_note_too_long' USING ERRCODE='22023'; END IF;
  IF p_reserved_until IS NOT NULL AND (p_reserved_from IS NULL OR p_reserved_until <= p_reserved_from) THEN
    RAISE EXCEPTION 'invalid_reservation_window' USING ERRCODE='22023';
  END IF;
  IF p_reserved_from IS NOT NULL AND p_reserved_from < now() THEN
    RAISE EXCEPTION 'reservation_must_be_future' USING ERRCODE='22023';
  END IF;

  -- The customer account is organization-specific and must be explicitly linked to this auth user.
  SELECT a.id, c.id, a.allow_credit, a.currency_code, a.credit_limit_minor
    INTO v_account_id, v_customer_id, v_allow_credit, v_account_currency, v_credit_limit_minor
  FROM public.commercial_customer_party_accounts cpa
  JOIN public.commercial_customers c
    ON c.id = cpa.customer_id AND c.organization_id = cpa.organization_id AND c.state = 'active' AND c.auth_user_id = v_parent_uid
  JOIN public.commercial_party_accounts a
    ON a.id = cpa.party_account_id AND a.organization_id = cpa.organization_id
   AND a.account_type IN ('business','organization') AND a.status = 'active'
  JOIN public.commercial_organizations o
    ON o.id = cpa.organization_id AND o.state = 'active' AND o.deleted_at IS NULL
  WHERE cpa.organization_id = p_organization_id
  LIMIT 1;
  IF v_account_id IS NULL THEN RAISE EXCEPTION 'customer_not_linked_to_organization' USING ERRCODE='42501'; END IF;
  IF p_payment_mode = 'credit' AND NOT coalesce(v_allow_credit, false) THEN
    RAISE EXCEPTION 'customer_credit_not_authorized' USING ERRCODE='42501';
  END IF;
  IF v_account_currency IS NULL THEN RAISE EXCEPTION 'customer_account_currency_missing' USING ERRCODE='22023'; END IF;

  -- Serialize retries by account/key; the unique constraint remains the final guard.
  PERFORM pg_advisory_xact_lock(hashtextextended(v_account_id::text || ':' || v_key, 0));
  SELECT * INTO v_existing
  FROM public.commercial_customer_orders
  WHERE buyer_account_id = v_account_id AND idempotency_key = v_key;
  IF FOUND THEN
    IF v_existing.organization_id <> p_organization_id
       OR coalesce(v_existing.customer_note,'') <> coalesce(nullif(btrim(p_customer_note),''),'')
       OR v_existing.reserved_from IS DISTINCT FROM p_reserved_from
       OR v_existing.reserved_until IS DISTINCT FROM p_reserved_until
       OR v_existing.payment_status <> (CASE WHEN p_payment_mode='credit' THEN 'credit' ELSE 'unpaid' END)
       OR v_existing.delegated_access_id IS DISTINCT FROM p_delegation_id
       OR v_existing.principal_account_id IS DISTINCT FROM v_parent_personal_account_id
       OR v_existing.acting_account_id IS DISTINCT FROM v_child_personal_account_id THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='22023';
    END IF;
    SELECT count(*) INTO v_existing_count FROM public.commercial_customer_order_items WHERE order_id = v_existing.id;
    SELECT count(*) INTO v_matching_count
    FROM public.commercial_customer_order_items oi
    JOIN jsonb_array_elements(p_lines) j(value)
      ON oi.offering_id = nullif(j.value->>'offering_id','')::uuid
     AND oi.quantity = nullif(j.value->>'quantity','')::numeric
    WHERE oi.order_id = v_existing.id;
    IF v_existing_count <> jsonb_array_length(p_lines) OR v_matching_count <> v_existing_count THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='22023';
    END IF;
    RETURN jsonb_build_object('order_id',v_existing.id,'order_number',v_existing.order_number,
      'status',v_existing.status,'payment_status',v_existing.payment_status,
      'total_minor',v_existing.total_minor,'currency_code',v_existing.currency_code,'replayed',true);
  END IF;

  -- Lock each active offer while reading its trusted price and validate all lines before inserting.
  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines)
  LOOP
    IF jsonb_typeof(v_line) <> 'object' OR nullif(v_line->>'offering_id','') IS NULL
       OR nullif(v_line->>'quantity','') IS NULL THEN
      RAISE EXCEPTION 'invalid_order_line' USING ERRCODE='22023';
    END IF;
    BEGIN
      IF (v_line->>'offering_id') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
        RAISE EXCEPTION 'invalid_order_offering_id' USING ERRCODE='22023';
      END IF;
      v_qty := (v_line->>'quantity')::numeric;
    EXCEPTION WHEN invalid_text_representation THEN
      RAISE EXCEPTION 'invalid_order_quantity' USING ERRCODE='22023';
    END;
    IF v_qty <= 0 OR v_qty > 1000000 OR v_qty <> round(v_qty, 3) THEN
      RAISE EXCEPTION 'invalid_order_quantity' USING ERRCODE='22023';
    END IF;
    SELECT ofr.id, ofr.price_minor, ofr.currency_code, p.name, a.activity_type, a.id AS activity_id
      INTO v_offering
    FROM public.commercial_offerings ofr
    JOIN public.commercial_activities a ON a.id=ofr.activity_id
    JOIN public.commercial_products p ON p.id=ofr.product_id
    WHERE ofr.id=(v_line->>'offering_id')::uuid
      AND ofr.state='active' AND ofr.valid_from <= now()
      AND (ofr.valid_to IS NULL OR ofr.valid_to > now())
      AND a.organization_id=p_organization_id AND a.state='active' AND a.deleted_at IS NULL
      AND p.state='active'
    FOR SHARE OF ofr, a, p;
    IF NOT FOUND THEN RAISE EXCEPTION 'offering_not_available_for_organization' USING ERRCODE='22023'; END IF;
    IF v_delegation.activity_id IS NOT NULL AND v_offering.activity_id <> v_delegation.activity_id THEN
      RAISE EXCEPTION 'delegated_activity_scope_denied' USING ERRCODE='42501';
    END IF;
    IF v_currency IS NULL THEN v_currency := v_offering.currency_code;
    ELSIF v_currency <> v_offering.currency_code THEN RAISE EXCEPTION 'mixed_order_currencies_not_supported' USING ERRCODE='22023'; END IF;
    IF v_offering.price_minor < 0 OR v_qty * v_offering.price_minor > 9000000000000000000 THEN
      RAISE EXCEPTION 'order_total_overflow' USING ERRCODE='22003';
    END IF;
    v_line_total := round(v_qty * v_offering.price_minor)::bigint;
    IF v_line_total < 0 OR v_subtotal > 9000000000000000000 - v_line_total THEN
      RAISE EXCEPTION 'order_total_overflow' USING ERRCODE='22003';
    END IF;
    v_subtotal := v_subtotal + v_line_total;
    v_count := v_count + 1;
    IF v_offering.activity_type = 'service' THEN v_order_type := 'service'; END IF;
  END LOOP;

  IF (SELECT count(DISTINCT value->>'offering_id') FROM jsonb_array_elements(p_lines)) <> v_count THEN
    RAISE EXCEPTION 'duplicate_order_offering' USING ERRCODE='22023';
  END IF;


  IF v_currency <> v_account_currency THEN
    RAISE EXCEPTION 'customer_account_currency_mismatch' USING ERRCODE='22023';
  END IF;

  IF p_payment_mode = 'credit' AND v_credit_limit_minor IS NOT NULL THEN
    -- Serialize credit-order submissions for this account before checking exposure.
    PERFORM pg_advisory_xact_lock(hashtextextended(v_account_id::text || ':credit-limit', 0));
    SELECT coalesce(sum(jl.debit_minor::numeric - jl.credit_minor::numeric), 0)
      INTO v_posted_balance
    FROM public.commercial_journal_lines jl
    JOIN public.commercial_journal_entries je
      ON je.id = jl.journal_entry_id AND je.organization_id = jl.organization_id
    WHERE jl.organization_id = p_organization_id
      AND jl.party_account_id = v_account_id
      AND je.status = 'posted';

    SELECT coalesce(sum(o.total_minor::numeric), 0)
      INTO v_open_credit_orders
    FROM public.commercial_customer_orders o
    WHERE o.organization_id = p_organization_id
      AND o.buyer_account_id = v_account_id
      AND o.payment_status = 'credit'
      AND o.status IN ('pending', 'confirmed', 'in_progress');

    IF greatest(v_posted_balance, 0) + v_open_credit_orders + v_subtotal > v_credit_limit_minor THEN
      RAISE EXCEPTION 'customer_credit_limit_exceeded' USING ERRCODE='22023';
    END IF;
  END IF;

  IF p_reserved_from IS NOT NULL THEN v_order_type := 'booking'; END IF;
  IF v_currency <> v_delegation.spend_currency_code THEN
    RAISE EXCEPTION 'delegated_spend_currency_mismatch' USING ERRCODE='22023';
  END IF;
  IF v_delegation.spend_limit_minor IS NOT NULL THEN
    IF v_delegation.spent_minor::numeric + v_subtotal::numeric > v_delegation.spend_limit_minor::numeric THEN
      RAISE EXCEPTION 'delegated_spend_limit_exceeded' USING ERRCODE='22023';
    END IF;
  END IF;

  INSERT INTO public.commercial_customer_orders(
    organization_id,buyer_account_id,created_by,order_type,status,payment_status,currency_code,
    subtotal_minor,discount_minor,tax_minor,total_minor,reserved_from,reserved_until,customer_note,idempotency_key,
    delegated_access_id,principal_account_id,acting_account_id
  ) VALUES (
    p_organization_id,v_account_id,v_uid,v_order_type,'pending',
    (CASE WHEN p_payment_mode='credit' THEN 'credit' ELSE 'unpaid' END),
    v_currency,v_subtotal,0,0,v_subtotal,p_reserved_from,p_reserved_until,
    nullif(btrim(p_customer_note),''),v_key,p_delegation_id,v_parent_personal_account_id,v_child_personal_account_id
  ) RETURNING id INTO v_order_id;

  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines)
  LOOP
    SELECT ofr.price_minor, ofr.currency_code, p.name, a.activity_type
      INTO v_offering
    FROM public.commercial_offerings ofr
    JOIN public.commercial_activities a ON a.id=ofr.activity_id
    JOIN public.commercial_products p ON p.id=ofr.product_id
    WHERE ofr.id=(v_line->>'offering_id')::uuid
      AND a.organization_id=p_organization_id AND a.state='active' AND a.deleted_at IS NULL
      AND ofr.state='active' AND ofr.valid_from <= now()
      AND (ofr.valid_to IS NULL OR ofr.valid_to > now()) AND p.state='active';
    v_qty := (v_line->>'quantity')::numeric;
    v_line_total := round(v_qty * v_offering.price_minor)::bigint;
    INSERT INTO public.commercial_customer_order_items(
      order_id,offering_id,item_type,item_name_snapshot,quantity,unit_price_minor,line_total_minor
    ) VALUES (
      v_order_id,(v_line->>'offering_id')::uuid,
      CASE WHEN v_offering.activity_type = 'service' THEN 'service' ELSE 'product' END,
      v_offering.name,v_qty,v_offering.price_minor,v_line_total
    );
  END LOOP;

  UPDATE public.commercial_account_delegations
  SET spent_minor=spent_minor + v_subtotal, updated_at=now()
  WHERE id=p_delegation_id;

  RETURN (SELECT jsonb_build_object('order_id',o.id,'order_number',o.order_number,'status',o.status,
    'payment_status',o.payment_status,'total_minor',o.total_minor,'currency_code',o.currency_code,
    'delegated_access_id',o.delegated_access_id,'principal_account_id',o.principal_account_id,
    'acting_account_id',o.acting_account_id,'replayed',false)
    FROM public.commercial_customer_orders o WHERE o.id=v_order_id);
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_create_delegated_order(uuid,uuid,jsonb,text,text,timestamptz,timestamptz,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_create_delegated_order(uuid,uuid,jsonb,text,text,timestamptz,timestamptz,text) TO authenticated;


-- Delegated bookings use the same parent receivable account and atomic spend ledger as delegated purchases.
CREATE OR REPLACE FUNCTION public.commercial_customer_portal_create_delegated_booking(p_delegation_id uuid, p_organization_id uuid, p_offering_id uuid, p_resource_id uuid, p_starts_at timestamp with time zone, p_ends_at timestamp with time zone, p_payment_mode text, p_customer_note text DEFAULT NULL::text, p_idempotency_key text DEFAULT NULL::text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE
 v_uid uuid:=auth.uid(); v_parent_uid uuid; v_parent_personal_account_id uuid; v_child_personal_account_id uuid;
 v_delegation public.commercial_account_delegations%ROWTYPE; v_account_id uuid; v_resource public.commercial_booking_resources%ROWTYPE;
 v_existing public.commercial_bookings%ROWTYPE; v_order jsonb; v_order_id uuid; v_order_total bigint;
 v_currency text; v_count integer; v_key text:=nullif(btrim(p_idempotency_key),'');
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 SELECT d.* INTO v_delegation
  FROM public.commercial_account_delegations d
  JOIN public.commercial_party_accounts parent_account ON parent_account.id=d.parent_account_id
  JOIN public.commercial_party_accounts child_account ON child_account.id=d.child_account_id
  WHERE d.id=p_delegation_id AND d.organization_id=p_organization_id
    AND d.status='active' AND (d.expires_at IS NULL OR d.expires_at>now())
    AND parent_account.account_type='personal' AND parent_account.status='active'
    AND child_account.account_type='personal' AND child_account.status='active'
    AND child_account.owner_user_id=v_uid
  FOR UPDATE OF d;
 IF NOT FOUND THEN RAISE EXCEPTION 'delegated_access_not_active_or_not_owned' USING ERRCODE='42501'; END IF;
  SELECT parent_account.owner_user_id, child_account.id
    INTO v_parent_uid, v_child_personal_account_id
  FROM public.commercial_party_accounts parent_account
  JOIN public.commercial_party_accounts child_account ON child_account.id=v_delegation.child_account_id
  WHERE parent_account.id=v_delegation.parent_account_id;
 v_parent_personal_account_id:=v_delegation.parent_account_id;
 IF NOT coalesce((v_delegation.permissions->>'book')::boolean,false) THEN
  RAISE EXCEPTION 'delegated_booking_permission_required' USING ERRCODE='42501';
 END IF;
 -- Parent is linked by the merchant approval flow; never auto-link the delegated child.
 IF p_organization_id IS NULL OR p_offering_id IS NULL OR p_resource_id IS NULL THEN RAISE EXCEPTION 'booking_fields_required' USING ERRCODE='22023'; END IF;
 IF p_starts_at IS NULL OR p_ends_at IS NULL OR p_starts_at<=now() OR p_ends_at<=p_starts_at THEN RAISE EXCEPTION 'invalid_reservation_window' USING ERRCODE='22023'; END IF;
 IF p_payment_mode IS NULL OR p_payment_mode NOT IN ('cash','credit') THEN RAISE EXCEPTION 'invalid_payment_mode' USING ERRCODE='22023'; END IF;
 IF v_key IS NULL OR length(v_key)<8 OR length(v_key)>200 THEN RAISE EXCEPTION 'invalid_idempotency_key' USING ERRCODE='22023'; END IF;
 SELECT a.id,a.currency_code INTO v_account_id,v_currency
 FROM public.commercial_customer_party_accounts cpa
 JOIN public.commercial_customers c ON c.id=cpa.customer_id AND c.organization_id=cpa.organization_id AND c.state='active' AND c.auth_user_id=v_parent_uid
 JOIN public.commercial_party_accounts a ON a.id=cpa.party_account_id AND a.organization_id=cpa.organization_id AND a.account_type IN ('business','organization') AND a.status='active'
 JOIN public.commercial_organizations o ON o.id=cpa.organization_id AND o.state='active' AND o.deleted_at IS NULL
 WHERE cpa.organization_id=p_organization_id LIMIT 1;
 IF v_account_id IS NULL THEN RAISE EXCEPTION 'customer_not_linked_to_organization' USING ERRCODE='42501'; END IF;
 IF p_payment_mode='credit' AND NOT EXISTS (SELECT 1 FROM public.commercial_party_accounts a WHERE a.id=v_account_id AND a.allow_credit) THEN RAISE EXCEPTION 'customer_credit_not_authorized' USING ERRCODE='42501'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended(v_account_id::text||':'||v_key,0));
 SELECT * INTO v_existing FROM public.commercial_bookings WHERE customer_account_id=v_account_id AND idempotency_key=v_key;
 IF FOUND THEN
  IF v_existing.organization_id<>p_organization_id OR v_existing.resource_id<>p_resource_id OR v_existing.starts_at<>p_starts_at OR v_existing.ends_at<>p_ends_at OR coalesce(v_existing.notes,'')<>coalesce(nullif(btrim(p_customer_note),''),'') OR v_existing.delegated_access_id IS DISTINCT FROM p_delegation_id OR v_existing.principal_account_id IS DISTINCT FROM v_parent_personal_account_id OR v_existing.acting_account_id IS DISTINCT FROM v_child_personal_account_id OR NOT EXISTS (SELECT 1 FROM public.commercial_customer_orders o JOIN public.commercial_customer_order_items oi ON oi.order_id=o.id WHERE o.id=v_existing.order_id AND o.payment_status=(CASE WHEN p_payment_mode='credit' THEN 'credit' ELSE 'unpaid' END) AND oi.offering_id=p_offering_id AND oi.quantity=1 AND (SELECT count(*) FROM public.commercial_customer_order_items x WHERE x.order_id=o.id)=1) THEN
   RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='22023';
  END IF;
  RETURN jsonb_build_object('booking_id',v_existing.id,'booking_number',v_existing.booking_number,'order_id',v_existing.order_id,'status',v_existing.status,'price_minor',v_existing.price_minor,'currency_code',v_existing.currency_code,'replayed',true);
 END IF;
 SELECT * INTO v_resource FROM public.commercial_booking_resources r WHERE r.id=p_resource_id AND r.organization_id=p_organization_id AND r.status='active' FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'booking_resource_not_available' USING ERRCODE='22023'; END IF;
 IF NOT EXISTS (
   SELECT 1 FROM public.commercial_offerings f
   JOIN public.commercial_activities a ON a.id=f.activity_id
   JOIN public.commercial_products p ON p.id=f.product_id
   WHERE f.id=p_offering_id AND f.state='active' AND f.valid_from<=now() AND (f.valid_to IS NULL OR f.valid_to>now())
    AND f.currency_code=v_currency AND a.organization_id=p_organization_id AND a.activity_type='service'
    AND (v_delegation.activity_id IS NULL OR a.id=v_delegation.activity_id)
    AND a.state='active' AND a.deleted_at IS NULL AND p.state='active'
 ) THEN RAISE EXCEPTION 'booking_service_not_available' USING ERRCODE='22023'; END IF;
 SELECT count(*) INTO v_count FROM public.commercial_bookings b
 WHERE b.organization_id=p_organization_id AND b.resource_id=p_resource_id
  AND b.status IN ('requested','confirmed','checked_in')
  AND b.starts_at<p_ends_at AND b.ends_at>p_starts_at;
 IF v_count>=v_resource.capacity THEN RAISE EXCEPTION 'booking_resource_capacity_exceeded' USING ERRCODE='22023'; END IF;
 v_order := public.commercial_customer_portal_create_delegated_order(
  p_delegation_id,p_organization_id,jsonb_build_array(jsonb_build_object('offering_id',p_offering_id,'quantity',1)),
  p_payment_mode,p_customer_note,p_starts_at,p_ends_at,v_key
 );
 v_order_id := (v_order->>'order_id')::uuid;
 v_order_total := (v_order->>'total_minor')::bigint;
 SELECT currency_code INTO v_currency FROM public.commercial_customer_orders WHERE id=v_order_id;
 INSERT INTO public.commercial_bookings(
  organization_id,resource_id,customer_account_id,order_id,status,starts_at,ends_at,currency_code,price_minor,paid_minor,created_by,notes,idempotency_key,
  delegated_access_id,principal_account_id,acting_account_id
 ) VALUES (
  p_organization_id,p_resource_id,v_account_id,v_order_id,'requested',p_starts_at,p_ends_at,v_currency,v_order_total,0,v_uid,nullif(btrim(p_customer_note),''),v_key,
  p_delegation_id,v_parent_personal_account_id,v_child_personal_account_id
 ) RETURNING id,booking_number INTO v_existing.id,v_existing.booking_number;
 RETURN jsonb_build_object('booking_id',v_existing.id,'booking_number',v_existing.booking_number,'order_id',v_order_id,'status','requested','price_minor',v_order_total,'currency_code',v_currency,'replayed',false);
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_create_delegated_booking(uuid,uuid,uuid,uuid,timestamptz,timestamptz,text,text,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_create_delegated_booking(uuid,uuid,uuid,uuid,timestamptz,timestamptz,text,text,text) TO authenticated;


-- Multi-service booking variant that charges the parent's organization-specific customer account.
-- Multi-service booking invoice: one booking, one order, many immutable priced lines.
-- Server-side price, tenant, permission, capacity and idempotency checks are authoritative.
CREATE OR REPLACE FUNCTION public.commercial_customer_portal_create_delegated_multi_service_booking(
  p_delegation_id uuid,
  p_organization_id uuid,
  p_lines jsonb,
  p_resource_id uuid,
  p_starts_at timestamptz,
  p_ends_at timestamptz,
  p_payment_mode text,
  p_customer_note text DEFAULT NULL,
  p_idempotency_key text DEFAULT NULL
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_parent_uid uuid;
  v_parent_personal_account_id uuid;
  v_child_personal_account_id uuid;
  v_delegation public.commercial_account_delegations%ROWTYPE;
  v_account_id uuid;
  v_account_currency text;
  v_resource public.commercial_booking_resources%ROWTYPE;
  v_existing public.commercial_bookings%ROWTYPE;
  v_existing_order public.commercial_customer_orders%ROWTYPE;
  v_order jsonb;
  v_order_id uuid;
  v_order_total bigint;
  v_key text := nullif(btrim(p_idempotency_key), '');
  v_line jsonb;
  v_offering record;
  v_qty numeric;
  v_count integer := 0;
  v_match_count integer := 0;
  v_requested_capacity numeric := 0;
  v_used_capacity numeric := 0;
  v_currency text;
  v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501';
  END IF;
  SELECT d.* INTO v_delegation
  FROM public.commercial_account_delegations d
  JOIN public.commercial_party_accounts parent_account ON parent_account.id=d.parent_account_id
  JOIN public.commercial_party_accounts child_account ON child_account.id=d.child_account_id
  WHERE d.id=p_delegation_id AND d.organization_id=p_organization_id
    AND d.status='active' AND (d.expires_at IS NULL OR d.expires_at>now())
    AND parent_account.account_type='personal' AND parent_account.status='active'
    AND child_account.account_type='personal' AND child_account.status='active'
    AND child_account.owner_user_id=v_uid
  FOR UPDATE OF d;
  IF NOT FOUND THEN RAISE EXCEPTION 'delegated_access_not_active_or_not_owned' USING ERRCODE='42501'; END IF;
  SELECT parent_account.owner_user_id, child_account.id
    INTO v_parent_uid, v_child_personal_account_id
  FROM public.commercial_party_accounts parent_account
  JOIN public.commercial_party_accounts child_account ON child_account.id=v_delegation.child_account_id
  WHERE parent_account.id=v_delegation.parent_account_id;
  v_parent_personal_account_id:=v_delegation.parent_account_id;
  IF NOT coalesce((v_delegation.permissions->>'book')::boolean,false) THEN
    RAISE EXCEPTION 'delegated_booking_permission_required' USING ERRCODE='42501';
  END IF;
  -- The parent was linked by the merchant approval flow; do not auto-link the child to a separate customer account.

  IF p_organization_id IS NULL OR p_resource_id IS NULL THEN
    RAISE EXCEPTION 'booking_fields_required' USING ERRCODE='22023';
  END IF;
  IF p_starts_at IS NULL OR p_ends_at IS NULL OR p_starts_at <= now() OR p_ends_at <= p_starts_at THEN
    RAISE EXCEPTION 'invalid_reservation_window' USING ERRCODE='22023';
  END IF;
  IF p_payment_mode IS NULL OR p_payment_mode NOT IN ('cash','credit') THEN
    RAISE EXCEPTION 'invalid_payment_mode' USING ERRCODE='22023';
  END IF;
  IF v_key IS NULL OR length(v_key) < 8 OR length(v_key) > 200 THEN
    RAISE EXCEPTION 'invalid_idempotency_key' USING ERRCODE='22023';
  END IF;
  IF p_customer_note IS NOT NULL AND length(p_customer_note) > 1000 THEN
    RAISE EXCEPTION 'customer_note_too_long' USING ERRCODE='22023';
  END IF;
  IF p_lines IS NULL OR jsonb_typeof(p_lines) <> 'array'
     OR jsonb_array_length(p_lines) < 1 OR jsonb_array_length(p_lines) > 50 THEN
    RAISE EXCEPTION 'invalid_booking_lines' USING ERRCODE='22023';
  END IF;

  -- Validate the complete submitted line set before looking up/replaying a booking.
  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines)
  LOOP
    IF jsonb_typeof(v_line) <> 'object'
       OR nullif(v_line->>'offering_id','') IS NULL
       OR nullif(v_line->>'quantity','') IS NULL
       OR (v_line->>'offering_id') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
      RAISE EXCEPTION 'invalid_booking_line' USING ERRCODE='22023';
    END IF;
    BEGIN
      v_qty := (v_line->>'quantity')::numeric;
    EXCEPTION WHEN invalid_text_representation THEN
      RAISE EXCEPTION 'invalid_booking_quantity' USING ERRCODE='22023';
    END;
    IF v_qty <= 0 OR v_qty > 100000 OR v_qty <> round(v_qty, 3) THEN
      RAISE EXCEPTION 'invalid_booking_quantity' USING ERRCODE='22023';
    END IF;
    v_count := v_count + 1;
  END LOOP;
  IF (SELECT count(DISTINCT value->>'offering_id') FROM jsonb_array_elements(p_lines)) <> v_count THEN
    RAISE EXCEPTION 'duplicate_booking_offering' USING ERRCODE='22023';
  END IF;

  SELECT a.id, a.currency_code
    INTO v_account_id, v_account_currency
  FROM public.commercial_customer_party_accounts cpa
  JOIN public.commercial_customers c
    ON c.id=cpa.customer_id AND c.organization_id=cpa.organization_id
   AND c.state='active' AND c.auth_user_id=v_parent_uid
  JOIN public.commercial_party_accounts a
    ON a.id=cpa.party_account_id AND a.organization_id=cpa.organization_id
   AND a.account_type IN ('business','organization') AND a.status='active'
  JOIN public.commercial_organizations o
    ON o.id=cpa.organization_id AND o.state='active' AND o.deleted_at IS NULL
  WHERE cpa.organization_id=p_organization_id
  LIMIT 1;
  IF v_account_id IS NULL THEN
    RAISE EXCEPTION 'customer_not_linked_to_organization' USING ERRCODE='42501';
  END IF;
  IF p_payment_mode='credit' AND NOT EXISTS (
    SELECT 1 FROM public.commercial_party_accounts a
    WHERE a.id=v_account_id AND a.organization_id=p_organization_id
      AND a.status='active' AND a.allow_credit
  ) THEN
    RAISE EXCEPTION 'customer_credit_not_authorized' USING ERRCODE='42501';
  END IF;

  -- Serialize retries for this account/key; unique constraints remain the final protection.
  PERFORM pg_advisory_xact_lock(hashtextextended(v_account_id::text || ':' || v_key, 0));

  SELECT * INTO v_existing
  FROM public.commercial_bookings b
  WHERE b.customer_account_id=v_account_id AND b.idempotency_key=v_key
  FOR UPDATE;
  IF FOUND THEN
    IF v_existing.organization_id <> p_organization_id
       OR v_existing.resource_id <> p_resource_id
       OR v_existing.starts_at <> p_starts_at
       OR v_existing.ends_at <> p_ends_at
       OR coalesce(v_existing.notes,'') <> coalesce(nullif(btrim(p_customer_note),''),'')
       OR v_existing.delegated_access_id IS DISTINCT FROM p_delegation_id
       OR v_existing.principal_account_id IS DISTINCT FROM v_parent_personal_account_id
       OR v_existing.acting_account_id IS DISTINCT FROM v_child_personal_account_id THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='22023';
    END IF;

    SELECT * INTO v_existing_order
    FROM public.commercial_customer_orders o
    WHERE o.id=v_existing.order_id AND o.organization_id=p_organization_id
      AND o.buyer_account_id=v_account_id
      AND o.payment_status=CASE WHEN p_payment_mode='credit' THEN 'credit' ELSE 'unpaid' END;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='22023';
    END IF;

    SELECT count(*) INTO v_match_count
    FROM public.commercial_customer_order_items oi
    JOIN jsonb_array_elements(p_lines) j(value)
      ON oi.offering_id=(j.value->>'offering_id')::uuid
     AND oi.quantity=(j.value->>'quantity')::numeric
    WHERE oi.order_id=v_existing_order.id AND oi.item_type='service';
    IF v_match_count <> jsonb_array_length(p_lines)
       OR (SELECT count(*) FROM public.commercial_customer_order_items oi WHERE oi.order_id=v_existing_order.id)
          <> jsonb_array_length(p_lines)
       OR v_existing_order.reserved_from IS DISTINCT FROM p_starts_at
       OR v_existing_order.reserved_until IS DISTINCT FROM p_ends_at THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='22023';
    END IF;

    RETURN jsonb_build_object(
      'booking_id',v_existing.id,'booking_number',v_existing.booking_number,
      'order_id',v_existing.order_id,'order_number',v_existing_order.order_number,
      'status',v_existing.status,'price_minor',v_existing.price_minor,
      'currency_code',v_existing.currency_code,'replayed',true,
      'items',(SELECT coalesce(jsonb_agg(jsonb_build_object(
        'offering_id',oi.offering_id,'name',oi.item_name_snapshot,'quantity',oi.quantity,
        'unit_price_minor',oi.unit_price_minor,'line_total_minor',oi.line_total_minor
      ) ORDER BY oi.created_at,oi.id),'[]'::jsonb)
      FROM public.commercial_customer_order_items oi WHERE oi.order_id=v_existing_order.id)
    );
  END IF;

  -- Lock the capacity row while validating all requested services and the shared resource.
  SELECT * INTO v_resource
  FROM public.commercial_booking_resources r
  WHERE r.id=p_resource_id AND r.organization_id=p_organization_id AND r.status='active'
  FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'booking_resource_not_available' USING ERRCODE='22023'; END IF;

  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines)
  LOOP
    SELECT f.id, f.price_minor, f.currency_code, a.activity_type, a.id AS activity_id
      INTO v_offering
    FROM public.commercial_offerings f
    JOIN public.commercial_activities a ON a.id=f.activity_id
    JOIN public.commercial_products p ON p.id=f.product_id
    WHERE f.id=(v_line->>'offering_id')::uuid
      AND f.state='active' AND f.valid_from<=now() AND (f.valid_to IS NULL OR f.valid_to>now())
      AND a.organization_id=p_organization_id AND a.activity_type='service'
      AND (v_delegation.activity_id IS NULL OR a.id=v_delegation.activity_id)
      AND a.state='active' AND a.deleted_at IS NULL AND p.state='active'
    FOR SHARE OF f,a,p;
    IF NOT FOUND THEN RAISE EXCEPTION 'booking_service_not_available' USING ERRCODE='22023'; END IF;
    IF v_offering.currency_code <> v_account_currency THEN
      RAISE EXCEPTION 'booking_currency_mismatch' USING ERRCODE='22023';
    END IF;
    IF v_currency IS NULL THEN v_currency := v_offering.currency_code;
    ELSIF v_currency <> v_offering.currency_code THEN
      RAISE EXCEPTION 'mixed_order_currencies_not_supported' USING ERRCODE='22023';
    END IF;
  END LOOP;

  -- Capacity is consumed by requested service units, not merely by booking rows.
  -- Legacy/other bookings without service order lines conservatively consume one unit.
  SELECT coalesce(sum((j.value->>'quantity')::numeric), 0)
    INTO v_requested_capacity
  FROM jsonb_array_elements(p_lines) AS j(value);

  SELECT coalesce(sum(active_booking.consumed_units), 0)
    INTO v_used_capacity
  FROM (
    SELECT b.id,
           CASE
             WHEN count(oi.id) FILTER (WHERE oi.item_type='service') > 0
               THEN sum(oi.quantity) FILTER (WHERE oi.item_type='service')
             ELSE 1::numeric
           END AS consumed_units
    FROM public.commercial_bookings b
    LEFT JOIN public.commercial_customer_order_items oi ON oi.order_id=b.order_id
    WHERE b.organization_id=p_organization_id AND b.resource_id=p_resource_id
      AND b.status IN ('requested','confirmed','checked_in')
      AND b.starts_at<p_ends_at AND b.ends_at>p_starts_at
    GROUP BY b.id
  ) AS active_booking;

  IF v_used_capacity + v_requested_capacity > v_resource.capacity THEN
    RAISE EXCEPTION 'booking_resource_capacity_exceeded' USING ERRCODE='22023';
  END IF;

  v_order := public.commercial_customer_portal_create_delegated_order(
    p_delegation_id,p_organization_id,p_lines,p_payment_mode,p_customer_note,p_starts_at,p_ends_at,v_key
  );
  v_order_id := (v_order->>'order_id')::uuid;
  v_order_total := (v_order->>'total_minor')::bigint;

  -- Defense in depth: booking orders may contain service lines only.
  IF EXISTS (
    SELECT 1 FROM public.commercial_customer_order_items oi
    WHERE oi.order_id=v_order_id AND oi.item_type IS DISTINCT FROM 'service'
  ) OR (SELECT count(*) FROM public.commercial_customer_order_items oi WHERE oi.order_id=v_order_id)
       <> jsonb_array_length(p_lines) THEN
    RAISE EXCEPTION 'booking_order_contains_invalid_lines' USING ERRCODE='22023';
  END IF;

  INSERT INTO public.commercial_bookings(
    organization_id,resource_id,customer_account_id,order_id,status,starts_at,ends_at,
    currency_code,price_minor,paid_minor,created_by,notes,idempotency_key,
    delegated_access_id,principal_account_id,acting_account_id
  ) VALUES (
    p_organization_id,p_resource_id,v_account_id,v_order_id,'requested',p_starts_at,p_ends_at,
    v_currency,v_order_total,0,v_uid,nullif(btrim(p_customer_note),''),v_key,
    p_delegation_id,v_parent_personal_account_id,v_child_personal_account_id
  ) RETURNING id,booking_number INTO v_existing.id,v_existing.booking_number;

  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'offering_id',oi.offering_id,'name',oi.item_name_snapshot,'quantity',oi.quantity,
    'unit_price_minor',oi.unit_price_minor,'line_total_minor',oi.line_total_minor
  ) ORDER BY oi.created_at,oi.id),'[]'::jsonb)
  INTO v_result
  FROM public.commercial_customer_order_items oi WHERE oi.order_id=v_order_id;

  RETURN jsonb_build_object(
    'booking_id',v_existing.id,'booking_number',v_existing.booking_number,
    'order_id',v_order_id,'order_number',v_order->>'order_number',
    'status','requested','price_minor',v_order_total,'currency_code',v_currency,
    'replayed',coalesce((v_order->>'replayed')::boolean,false),'items',v_result
  );
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_create_delegated_multi_service_booking(uuid,uuid,jsonb,uuid,timestamptz,timestamptz,text,text,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_create_delegated_multi_service_booking(uuid,uuid,jsonb,uuid,timestamptz,timestamptz,text,text,text) TO authenticated;
COMMENT ON FUNCTION public.commercial_customer_portal_create_delegated_multi_service_booking(uuid,uuid,jsonb,uuid,timestamptz,timestamptz,text,text,text) IS 'Creates a multi-service booking under an approved delegated account; parent is billed and child is attributed as actor.';
