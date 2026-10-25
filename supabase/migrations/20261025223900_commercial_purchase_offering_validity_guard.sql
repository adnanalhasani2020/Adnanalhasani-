-- Reject inactive, expired, cross-organization, or cross-currency offerings on purchase lines.
CREATE OR REPLACE FUNCTION commercial_private.commercial_guard_purchase_order_item_offering()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_org uuid; v_currency text;
BEGIN
  IF NEW.offering_id IS NULL THEN RETURN NEW; END IF;
  SELECT organization_id,currency_code INTO v_org,v_currency
    FROM public.commercial_purchase_orders
    WHERE id=NEW.purchase_order_id AND organization_id=NEW.organization_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'purchase_order_not_found' USING ERRCODE='P0002'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_offerings o
    JOIN public.commercial_activities a ON a.id=o.activity_id
    JOIN public.commercial_products p ON p.id=o.product_id
    WHERE o.id=NEW.offering_id AND a.organization_id=v_org AND a.state='active'
      AND o.state='active' AND p.state='active' AND upper(o.currency_code)=v_currency
      AND (o.valid_from IS NULL OR o.valid_from<=now())
      AND (o.valid_to IS NULL OR o.valid_to>=now())
  ) THEN RAISE EXCEPTION 'purchase_offering_inactive_expired_or_scope_mismatch' USING ERRCODE='22023'; END IF;
  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS commercial_guard_purchase_order_item_offering ON public.commercial_purchase_order_items;
CREATE TRIGGER commercial_guard_purchase_order_item_offering
BEFORE INSERT OR UPDATE OF offering_id ON public.commercial_purchase_order_items
FOR EACH ROW EXECUTE FUNCTION commercial_private.commercial_guard_purchase_order_item_offering();

REVOKE ALL ON FUNCTION commercial_private.commercial_guard_purchase_order_item_offering() FROM PUBLIC,anon,authenticated;
