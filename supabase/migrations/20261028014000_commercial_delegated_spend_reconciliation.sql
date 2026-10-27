-- Release delegated spend when an order or booking is cancelled/rejected; re-reserve if a cancelled order is explicitly reopened.
CREATE OR REPLACE FUNCTION public.commercial_reconcile_delegated_spend_on_order_change()
RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
  v_delta numeric := 0;
  v_was_counted boolean;
  v_is_counted boolean;
  v_current_spend bigint;
  v_limit bigint;
BEGIN
  IF OLD.delegated_access_id IS NULL AND NEW.delegated_access_id IS NULL THEN RETURN NEW; END IF;
  IF OLD.delegated_access_id IS NOT NULL AND (
       NEW.delegated_access_id IS DISTINCT FROM OLD.delegated_access_id
       OR NEW.principal_account_id IS DISTINCT FROM OLD.principal_account_id
       OR NEW.acting_account_id IS DISTINCT FROM OLD.acting_account_id
     ) THEN
    RAISE EXCEPTION 'delegated_attribution_is_immutable' USING ERRCODE='22023';
  END IF;
  IF OLD.delegated_access_id IS NULL AND NEW.delegated_access_id IS NOT NULL THEN
    RAISE EXCEPTION 'delegated_attribution_cannot_be_added_after_order_creation' USING ERRCODE='22023';
  END IF;

  -- A cancelled order still consumes the cap while money is paid/partially paid; release only unpaid, credit, or refunded orders.
  v_was_counted := OLD.status NOT IN ('cancelled','rejected') OR OLD.payment_status NOT IN ('unpaid','credit','refunded');
  v_is_counted := NEW.status NOT IN ('cancelled','rejected') OR NEW.payment_status NOT IN ('unpaid','credit','refunded');
  IF v_was_counted AND NOT v_is_counted THEN
    v_delta := -OLD.total_minor::numeric;
  ELSIF NOT v_was_counted AND v_is_counted THEN
    v_delta := NEW.total_minor::numeric;
  ELSIF v_was_counted AND v_is_counted AND NEW.total_minor <> OLD.total_minor THEN
    v_delta := NEW.total_minor::numeric - OLD.total_minor::numeric;
  END IF;
  IF v_delta = 0 THEN RETURN NEW; END IF;

  SELECT spent_minor, spend_limit_minor INTO v_current_spend, v_limit
  FROM public.commercial_account_delegations
  WHERE id=coalesce(NEW.delegated_access_id,OLD.delegated_access_id)
  FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'delegated_access_not_found' USING ERRCODE='P0002'; END IF;
  IF v_delta > 0 AND v_limit IS NOT NULL AND v_current_spend::numeric + v_delta > v_limit::numeric THEN
    RAISE EXCEPTION 'delegated_spend_limit_exceeded' USING ERRCODE='22023';
  END IF;
  UPDATE public.commercial_account_delegations
  SET spent_minor=greatest(0, (v_current_spend::numeric + v_delta)::bigint), updated_at=now()
  WHERE id=coalesce(NEW.delegated_access_id,OLD.delegated_access_id);
  IF NEW.delegated_access_id IS NOT NULL AND NEW.status IN ('cancelled','rejected')
     AND OLD.status NOT IN ('cancelled','rejected') THEN
    UPDATE public.commercial_bookings
    SET status=NEW.status
    WHERE order_id=NEW.id AND delegated_access_id=NEW.delegated_access_id
      AND status NOT IN ('cancelled','rejected');
  END IF;
  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS trg_commercial_delegated_spend_order_change ON public.commercial_customer_orders;
CREATE TRIGGER trg_commercial_delegated_spend_order_change
AFTER UPDATE OF status,total_minor,payment_status,delegated_access_id,principal_account_id,acting_account_id ON public.commercial_customer_orders
FOR EACH ROW EXECUTE FUNCTION public.commercial_reconcile_delegated_spend_on_order_change();

CREATE OR REPLACE FUNCTION public.commercial_sync_delegated_booking_order_status()
RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
BEGIN
  IF NEW.order_id IS NOT NULL AND NEW.status IN ('cancelled','rejected')
     AND OLD.status NOT IN ('cancelled','rejected')
     AND NEW.delegated_access_id IS NOT NULL THEN
    UPDATE public.commercial_customer_orders
    SET status=NEW.status, updated_at=now()
    WHERE id=NEW.order_id AND status NOT IN ('cancelled','rejected');
  END IF;
  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS trg_commercial_sync_delegated_booking_order_status ON public.commercial_bookings;
CREATE TRIGGER trg_commercial_sync_delegated_booking_order_status
AFTER UPDATE OF status ON public.commercial_bookings
FOR EACH ROW EXECUTE FUNCTION public.commercial_sync_delegated_booking_order_status();

REVOKE ALL ON FUNCTION public.commercial_reconcile_delegated_spend_on_order_change() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.commercial_sync_delegated_booking_order_status() FROM PUBLIC, anon, authenticated;


CREATE OR REPLACE FUNCTION public.commercial_protect_delegated_booking_attribution()
RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
BEGIN
  IF OLD.delegated_access_id IS NOT NULL AND (
    NEW.delegated_access_id IS DISTINCT FROM OLD.delegated_access_id
    OR NEW.principal_account_id IS DISTINCT FROM OLD.principal_account_id
    OR NEW.acting_account_id IS DISTINCT FROM OLD.acting_account_id
  ) THEN
    RAISE EXCEPTION 'delegated_attribution_is_immutable' USING ERRCODE='22023';
  END IF;
  IF OLD.delegated_access_id IS NULL AND NEW.delegated_access_id IS NOT NULL THEN
    RAISE EXCEPTION 'delegated_attribution_cannot_be_added_after_booking_creation' USING ERRCODE='22023';
  END IF;
  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS trg_commercial_protect_delegated_booking_attribution ON public.commercial_bookings;
CREATE TRIGGER trg_commercial_protect_delegated_booking_attribution
BEFORE UPDATE OF delegated_access_id,principal_account_id,acting_account_id ON public.commercial_bookings
FOR EACH ROW EXECUTE FUNCTION public.commercial_protect_delegated_booking_attribution();

REVOKE ALL ON FUNCTION public.commercial_protect_delegated_booking_attribution() FROM PUBLIC, anon, authenticated;
