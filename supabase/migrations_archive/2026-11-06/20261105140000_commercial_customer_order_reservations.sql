BEGIN;

ALTER TABLE public.commercial_user_notifications
  DROP CONSTRAINT IF EXISTS commercial_user_notifications_notification_type_check;
ALTER TABLE public.commercial_user_notifications
  ADD CONSTRAINT commercial_user_notifications_notification_type_check
  CHECK (notification_type = ANY (ARRAY[
    'order','payment','booking','account_access','approval','system','security','message','reminder'
  ]::text[]));



-- Stock reservations are a commitment against sellable quantity, not a physical
-- stock movement. on-hand remains unchanged until the existing checkout RPC posts
-- the actual sale and inventory movement.
CREATE TABLE IF NOT EXISTS public.commercial_customer_order_reservations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  order_id uuid NOT NULL REFERENCES public.commercial_customer_orders(id) ON DELETE RESTRICT,
  location_id uuid NOT NULL REFERENCES public.commercial_organization_locations(id) ON DELETE RESTRICT,
  status text NOT NULL DEFAULT 'active'
    CHECK (status IN ('active','released','expired','cancelled','consumed')),
  expiry_policy text NOT NULL
    CHECK (expiry_policy IN ('release_stock','keep_reserved_notify','cancel_order')),
  expires_at timestamptz NOT NULL,
  next_reminder_at timestamptz,
  idempotency_key text NOT NULL CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 200),
  request_fingerprint text NOT NULL CHECK (length(request_fingerprint)=32),
  created_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT commercial_customer_order_reservations_order_uq UNIQUE(order_id),
  CONSTRAINT commercial_customer_order_reservations_org_key_uq UNIQUE(organization_id,idempotency_key)
);

CREATE TABLE IF NOT EXISTS public.commercial_customer_order_reservation_items (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  reservation_id uuid NOT NULL REFERENCES public.commercial_customer_order_reservations(id) ON DELETE RESTRICT,
  order_item_id uuid NOT NULL REFERENCES public.commercial_customer_order_items(id) ON DELETE RESTRICT,
  offering_id uuid NOT NULL REFERENCES public.commercial_offerings(id) ON DELETE RESTRICT,
  quantity numeric NOT NULL CHECK (quantity > 0 AND quantity = trunc(quantity)),
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT commercial_customer_order_reservation_items_item_uq UNIQUE(order_item_id)
);

CREATE INDEX IF NOT EXISTS commercial_customer_order_reservations_expiry_idx
  ON public.commercial_customer_order_reservations(status,expires_at,next_reminder_at);
CREATE INDEX IF NOT EXISTS commercial_customer_order_reservations_org_status_idx
  ON public.commercial_customer_order_reservations(organization_id,status,created_at DESC);

ALTER TABLE public.commercial_customer_order_reservations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_customer_order_reservation_items ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.commercial_customer_order_reservations FROM PUBLIC,anon,authenticated;
REVOKE ALL ON TABLE public.commercial_customer_order_reservation_items FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_reserve_order(
  p_organization_id uuid,
  p_order_id uuid,
  p_location_id uuid,
  p_expires_at timestamptz,
  p_expiry_policy text,
  p_idempotency_key text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_order public.commercial_customer_orders%ROWTYPE;
  v_existing public.commercial_customer_order_reservations%ROWTYPE;
  v_item record;
  v_on_hand numeric;
  v_reserved numeric;
  v_reservation_id uuid;
  v_fingerprint text;
  v_count integer := 0;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR p_order_id IS NULL OR p_location_id IS NULL
     OR p_expires_at IS NULL OR p_expires_at <= now()
     OR p_expires_at > now() + interval '90 days'
     OR p_expiry_policy NOT IN ('release_stock','keep_reserved_notify','cancel_order')
     OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
    RAISE EXCEPTION 'invalid_customer_order_reservation' USING ERRCODE='22023';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=p_organization_id AND m.user_id=v_uid
      AND m.state='active' AND m.role IN ('owner','admin','manager')
  ) THEN RAISE EXCEPTION 'customer_order_reservation_permission_required' USING ERRCODE='42501'; END IF;

  v_fingerprint := md5(concat_ws('|',p_organization_id::text,p_order_id::text,p_location_id::text,
    p_expires_at::text,p_expiry_policy));
  PERFORM pg_advisory_xact_lock(hashtextextended(p_organization_id::text||':'||btrim(p_idempotency_key),0));
  SELECT * INTO v_existing FROM public.commercial_customer_order_reservations r
  WHERE r.organization_id=p_organization_id AND r.idempotency_key=btrim(p_idempotency_key) FOR UPDATE;
  IF FOUND THEN
    IF v_existing.request_fingerprint IS DISTINCT FROM v_fingerprint THEN
      RAISE EXCEPTION 'reservation_idempotency_key_reused' USING ERRCODE='23505';
    END IF;
    RETURN jsonb_build_object('reservation_id',v_existing.id,'order_id',v_existing.order_id,
      'status',v_existing.status,'expires_at',v_existing.expires_at,'expiry_policy',v_existing.expiry_policy,'replayed',true);
  END IF;

  SELECT * INTO v_order FROM public.commercial_customer_orders o
  WHERE o.id=p_order_id AND o.organization_id=p_organization_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'customer_order_not_found' USING ERRCODE='P0002'; END IF;
  IF v_order.order_type IS DISTINCT FROM 'purchase' OR v_order.status IS DISTINCT FROM 'pending'
     OR v_order.payment_status NOT IN ('unpaid','credit') THEN
    RAISE EXCEPTION 'customer_order_not_reservable' USING ERRCODE='22023';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_locations l
    WHERE l.id=p_location_id AND l.organization_id=p_organization_id AND l.state='active') THEN
    RAISE EXCEPTION 'reservation_location_not_active' USING ERRCODE='22023';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_customer_order_items i WHERE i.order_id=v_order.id)
     OR EXISTS (SELECT 1 FROM public.commercial_customer_order_items i
       WHERE i.order_id=v_order.id AND (i.item_type<>'product' OR i.quantity<=0
         OR i.quantity<>trunc(i.quantity) OR i.quantity>100000)) THEN
    RAISE EXCEPTION 'customer_order_contains_invalid_reservation_lines' USING ERRCODE='22023';
  END IF;
  IF EXISTS (
    SELECT 1 FROM public.commercial_customer_order_items i
    JOIN public.commercial_offerings f ON f.id=i.offering_id
    JOIN public.commercial_activities a ON a.id=f.activity_id
    WHERE i.order_id=v_order.id AND a.organization_id<>p_organization_id
  ) THEN RAISE EXCEPTION 'reservation_offering_outside_organization' USING ERRCODE='42501'; END IF;

  -- Lock every affected balance in a stable order before changing any quantity.
  PERFORM b.id
  FROM public.commercial_inventory_balances b
  JOIN public.commercial_customer_order_items i ON i.offering_id=b.offering_id
  WHERE i.order_id=v_order.id AND b.organization_id=p_organization_id AND b.location_id=p_location_id
  ORDER BY b.offering_id
  FOR UPDATE OF b;

  SELECT count(*) INTO v_count FROM public.commercial_customer_order_items i
  JOIN public.commercial_inventory_balances b
    ON b.organization_id=p_organization_id AND b.location_id=p_location_id AND b.offering_id=i.offering_id
  WHERE i.order_id=v_order.id;
  IF v_count<>(SELECT count(*) FROM public.commercial_customer_order_items i WHERE i.order_id=v_order.id) THEN
    RAISE EXCEPTION 'reservation_inventory_balance_missing' USING ERRCODE='22023';
  END IF;

  INSERT INTO public.commercial_customer_order_reservations(
    organization_id,order_id,location_id,expiry_policy,expires_at,next_reminder_at,
    idempotency_key,request_fingerprint,created_by
  ) VALUES (
    p_organization_id,v_order.id,p_location_id,p_expiry_policy,p_expires_at,
    CASE WHEN p_expiry_policy='keep_reserved_notify' THEN p_expires_at ELSE NULL END,
    btrim(p_idempotency_key),v_fingerprint,v_uid
  ) RETURNING id INTO v_reservation_id;

  FOR v_item IN
    SELECT i.id,i.offering_id,i.quantity
    FROM public.commercial_customer_order_items i
    WHERE i.order_id=v_order.id ORDER BY i.offering_id
  LOOP
    UPDATE public.commercial_inventory_balances b
    SET quantity_reserved=b.quantity_reserved+v_item.quantity,updated_at=now()
    WHERE b.organization_id=p_organization_id AND b.location_id=p_location_id
      AND b.offering_id=v_item.offering_id
      AND b.quantity_on_hand-b.quantity_reserved>=v_item.quantity
    RETURNING b.quantity_on_hand,b.quantity_reserved INTO v_on_hand,v_reserved;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'reservation_insufficient_available_stock' USING ERRCODE='22023';
    END IF;
    INSERT INTO public.commercial_customer_order_reservation_items(
      reservation_id,order_item_id,offering_id,quantity
    ) VALUES(v_reservation_id,v_item.id,v_item.offering_id,v_item.quantity);
  END LOOP;

  UPDATE public.commercial_customer_orders
  SET status='confirmed',reserved_from=now(),reserved_until=p_expires_at,
      merchant_reviewed_at=now(),merchant_reviewed_by=v_uid,
      merchant_decision_reason='Inventory reserved until '||p_expires_at::text,updated_at=now()
  WHERE id=v_order.id AND organization_id=p_organization_id AND status='pending';
  IF NOT FOUND THEN RAISE EXCEPTION 'customer_order_reservation_race_retry' USING ERRCODE='40001'; END IF;

  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
  VALUES(p_organization_id,v_uid,'customer_order.stock_reserved','customer_order',v_order.id::text,
    jsonb_build_object('reservation_id',v_reservation_id,'location_id',p_location_id,
      'expires_at',p_expires_at,'expiry_policy',p_expiry_policy,'physical_on_hand_unchanged',true));
  RETURN jsonb_build_object('reservation_id',v_reservation_id,'order_id',v_order.id,
    'status','active','expires_at',p_expires_at,'expiry_policy',p_expiry_policy,'replayed',false);
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_reserve_order(uuid,uuid,uuid,timestamptz,text,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_reserve_order(uuid,uuid,uuid,timestamptz,text,text) TO authenticated;

-- A manager-triggered worker can safely process due reservations. A scheduler can call
-- this same RPC later; the free PostgreSQL test environment does not need paid Supabase
-- compute or an always-on worker.
CREATE OR REPLACE FUNCTION public.commercial_customer_portal_process_expired_order_reservations(
  p_organization_id uuid,p_limit integer DEFAULT 100
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_reservation public.commercial_customer_order_reservations%ROWTYPE;
  v_item record;
  v_processed integer := 0;
  v_released integer := 0;
  v_notified integer := 0;
  v_new_status text;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 200 THEN
    RAISE EXCEPTION 'invalid_reservation_expiry_batch' USING ERRCODE='22023';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=p_organization_id AND m.user_id=v_uid
      AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN
    RAISE EXCEPTION 'customer_order_reservation_permission_required' USING ERRCODE='42501';
  END IF;

  FOR v_reservation IN
    SELECT r.* FROM public.commercial_customer_order_reservations r
    WHERE r.organization_id=p_organization_id AND r.status='active'
      AND r.expires_at<=now()
      AND (r.expiry_policy<>'keep_reserved_notify' OR r.next_reminder_at<=now())
    ORDER BY r.expires_at,r.id LIMIT p_limit FOR UPDATE SKIP LOCKED
  LOOP
    IF v_reservation.expiry_policy='keep_reserved_notify' THEN
      INSERT INTO public.commercial_user_notifications(
        user_id,organization_id,notification_type,title,body,entity_type,entity_id,priority,metadata
      )
      SELECT m.user_id,p_organization_id,'reminder','حجز متأخر عن موعده',
        'يوجد طلب عميل ما زال يحجز مخزوناً بعد انتهاء الموعد المحدد. راجع الحجز أو أكمل البيع.',
        'customer_order',v_reservation.order_id,'high',
        jsonb_build_object('reservation_id',v_reservation.id,'expires_at',v_reservation.expires_at)
      FROM public.commercial_organization_members m
      WHERE m.organization_id=p_organization_id AND m.state='active'
        AND m.role IN ('owner','admin','manager');
      UPDATE public.commercial_customer_order_reservations
      SET next_reminder_at=now()+interval '1 day',updated_at=now()
      WHERE id=v_reservation.id;
      v_notified:=v_notified+1;
    ELSE
      FOR v_item IN SELECT offering_id,quantity FROM public.commercial_customer_order_reservation_items
        WHERE reservation_id=v_reservation.id ORDER BY offering_id
      LOOP
        UPDATE public.commercial_inventory_balances b
        SET quantity_reserved=b.quantity_reserved-v_item.quantity,updated_at=now()
        WHERE b.organization_id=p_organization_id AND b.location_id=v_reservation.location_id
          AND b.offering_id=v_item.offering_id AND b.quantity_reserved>=v_item.quantity;
        IF NOT FOUND THEN RAISE EXCEPTION 'reservation_balance_release_integrity_error' USING ERRCODE='55000'; END IF;
      END LOOP;
      v_new_status:='cancelled';
      UPDATE public.commercial_customer_order_reservations
      SET status=CASE WHEN v_reservation.expiry_policy='cancel_order' THEN 'cancelled' ELSE 'expired' END,updated_at=now()
      WHERE id=v_reservation.id;
      UPDATE public.commercial_customer_orders
      SET status=v_new_status,updated_at=now()
      WHERE id=v_reservation.order_id AND organization_id=p_organization_id AND status IN ('confirmed','in_progress');
      INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
      VALUES(p_organization_id,v_uid,'customer_order.reservation_expired','customer_order',v_reservation.order_id::text,
        jsonb_build_object('reservation_id',v_reservation.id,'policy',v_reservation.expiry_policy,
          'released_stock',true,'new_order_status',v_new_status));
      v_released:=v_released+1;
    END IF;
    v_processed:=v_processed+1;
  END LOOP;
  RETURN jsonb_build_object('processed',v_processed,'released',v_released,'reminded',v_notified);
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_process_expired_order_reservations(uuid,integer) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_process_expired_order_reservations(uuid,integer) TO authenticated;


CREATE OR REPLACE FUNCTION public.commercial_customer_portal_list_order_reservations(
  p_organization_id uuid
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=p_organization_id AND m.user_id=v_uid
      AND m.state='active' AND m.role IN ('owner','admin','manager')
  ) THEN RAISE EXCEPTION 'customer_order_reservation_permission_required' USING ERRCODE='42501'; END IF;
  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'order_id',r.order_id,'reservation_id',r.id,'status',r.status,
    'expires_at',r.expires_at,'expiry_policy',r.expiry_policy,'location_id',r.location_id
  ) ORDER BY r.created_at DESC),'[]'::jsonb)
  INTO v_result
  FROM public.commercial_customer_order_reservations r
  WHERE r.organization_id=p_organization_id;
  RETURN v_result;
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_list_order_reservations(uuid) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_list_order_reservations(uuid) TO authenticated;

-- Release the reservation in the same transaction before calling the existing checkout.
-- Checkout then sees the quantity as available and posts the real stock movement. If
-- checkout fails, PostgreSQL rolls back both the release and the attempted sale.
CREATE OR REPLACE FUNCTION public.commercial_customer_portal_fulfill_reserved_order(
  p_organization_id uuid,p_order_id uuid,p_location_id uuid,p_idempotency_key text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
  v_uid uuid:=auth.uid();
  v_reservation public.commercial_customer_order_reservations%ROWTYPE;
  v_item record;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR p_order_id IS NULL OR p_location_id IS NULL
     OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
    RAISE EXCEPTION 'invalid_reserved_order_fulfillment' USING ERRCODE='22023';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=p_organization_id AND m.user_id=v_uid
      AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN
    RAISE EXCEPTION 'customer_order_fulfillment_permission_required' USING ERRCODE='42501';
  END IF;
  -- Preserve the original checkout idempotency contract after an ambiguous client retry.
  IF EXISTS (SELECT 1 FROM public.commercial_customer_order_fulfillments f
    WHERE f.organization_id=p_organization_id AND f.order_id=p_order_id) THEN
    RETURN public.commercial_customer_portal_fulfill_order(
      p_organization_id,p_order_id,p_location_id,p_idempotency_key
    );
  END IF;
  SELECT * INTO v_reservation FROM public.commercial_customer_order_reservations r
  WHERE r.organization_id=p_organization_id AND r.order_id=p_order_id AND r.status='active'
    AND r.location_id=p_location_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'active_order_reservation_not_found' USING ERRCODE='22023'; END IF;
  IF v_reservation.expires_at<=now() AND v_reservation.expiry_policy<>'keep_reserved_notify' THEN
    RAISE EXCEPTION 'customer_order_reservation_expired' USING ERRCODE='22023';
  END IF;
  FOR v_item IN SELECT offering_id,quantity FROM public.commercial_customer_order_reservation_items
    WHERE reservation_id=v_reservation.id ORDER BY offering_id
  LOOP
    UPDATE public.commercial_inventory_balances b
    SET quantity_reserved=b.quantity_reserved-v_item.quantity,updated_at=now()
    WHERE b.organization_id=p_organization_id AND b.location_id=p_location_id
      AND b.offering_id=v_item.offering_id AND b.quantity_reserved>=v_item.quantity;
    IF NOT FOUND THEN RAISE EXCEPTION 'reservation_balance_release_integrity_error' USING ERRCODE='55000'; END IF;
  END LOOP;
  UPDATE public.commercial_customer_order_reservations
  SET status='consumed',updated_at=now() WHERE id=v_reservation.id;
  RETURN public.commercial_customer_portal_fulfill_order(
    p_organization_id,p_order_id,p_location_id,p_idempotency_key
  );
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_fulfill_reserved_order(uuid,uuid,uuid,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_fulfill_reserved_order(uuid,uuid,uuid,text) TO authenticated;

COMMIT;
