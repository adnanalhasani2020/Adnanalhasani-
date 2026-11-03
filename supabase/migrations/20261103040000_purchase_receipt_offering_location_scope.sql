BEGIN;

-- Purchase inventory offerings are branch-scoped. A receipt must never create
-- a balance for an offering under a different branch location.
CREATE OR REPLACE FUNCTION commercial_private.commercial_guard_purchase_order_item_receiving_location()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
  v_receive_location uuid;
  v_offering_location text;
  v_offering_organization uuid;
BEGIN
  IF NEW.offering_id IS NULL THEN
    RETURN NEW;
  END IF;

  SELECT receive_location_id
    INTO v_receive_location
  FROM public.commercial_purchase_orders
  WHERE id = NEW.purchase_order_id
    AND organization_id = NEW.organization_id
  FOR SHARE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'purchase_order_not_found' USING ERRCODE='P0002';
  END IF;

  SELECT o.location_ref, a.organization_id
    INTO v_offering_location, v_offering_organization
  FROM public.commercial_offerings o
  JOIN public.commercial_activities a ON a.id = o.activity_id
  WHERE o.id = NEW.offering_id;

  IF NOT FOUND
     OR v_offering_organization IS DISTINCT FROM NEW.organization_id
     OR v_receive_location IS NULL
     OR v_offering_location IS DISTINCT FROM v_receive_location::text THEN
    RAISE EXCEPTION 'purchase_offering_receiving_location_mismatch' USING ERRCODE='22023';
  END IF;

  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS commercial_purchase_order_item_receiving_location_guard
  ON public.commercial_purchase_order_items;
CREATE TRIGGER commercial_purchase_order_item_receiving_location_guard
BEFORE INSERT OR UPDATE OF offering_id
ON public.commercial_purchase_order_items
FOR EACH ROW
EXECUTE FUNCTION commercial_private.commercial_guard_purchase_order_item_receiving_location();


-- If the receiving branch changes after lines are linked, require the existing
-- offerings to belong to the new branch before accepting the header update.
CREATE OR REPLACE FUNCTION commercial_private.commercial_guard_purchase_order_receiving_location_change()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
BEGIN
  IF NEW.receive_location_id IS NOT DISTINCT FROM OLD.receive_location_id THEN
    RETURN NEW;
  END IF;

  IF EXISTS (
    SELECT 1
    FROM public.commercial_purchase_order_items poi
    JOIN public.commercial_offerings o ON o.id = poi.offering_id
    JOIN public.commercial_activities a ON a.id = o.activity_id
    WHERE poi.organization_id = NEW.organization_id
      AND poi.purchase_order_id = NEW.id
      AND poi.offering_id IS NOT NULL
      AND (
        NEW.receive_location_id IS NULL
        OR a.organization_id IS DISTINCT FROM NEW.organization_id
        OR o.location_ref IS DISTINCT FROM NEW.receive_location_id::text
      )
  ) THEN
    RAISE EXCEPTION 'purchase_order_receiving_location_conflicts_with_linked_offerings'
      USING ERRCODE='22023';
  END IF;

  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS commercial_purchase_order_receiving_location_change_guard
  ON public.commercial_purchase_orders;
CREATE TRIGGER commercial_purchase_order_receiving_location_change_guard
BEFORE UPDATE OF receive_location_id
ON public.commercial_purchase_orders
FOR EACH ROW
EXECUTE FUNCTION commercial_private.commercial_guard_purchase_order_receiving_location_change();


-- Final posting invariant: the receipt header, purchase line, offering and
-- inventory receipt must all point to the same organization and branch.
-- Any mismatch aborts the surrounding receipt transaction, including the
-- balance update and stock movement already attempted by the receiving RPC.
CREATE OR REPLACE FUNCTION commercial_private.commercial_guard_purchase_receipt_item_location_scope()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
  v_receipt_location uuid;
  v_order_location uuid;
  v_line_offering uuid;
  v_inventory_organization uuid;
  v_inventory_location uuid;
  v_inventory_offering uuid;
  v_inventory_source_ref text;
  v_offering_location text;
  v_activity_organization uuid;
BEGIN
  SELECT pr.location_id, po.receive_location_id, poi.offering_id,
         ir.organization_id, ir.location_id, ir.offering_id, ir.source_ref,
         o.location_ref, a.organization_id
    INTO v_receipt_location, v_order_location, v_line_offering,
         v_inventory_organization, v_inventory_location, v_inventory_offering,
         v_inventory_source_ref, v_offering_location, v_activity_organization
  FROM public.commercial_purchase_receipts pr
  JOIN public.commercial_purchase_orders po
    ON po.id = pr.purchase_order_id
   AND po.organization_id = pr.organization_id
  JOIN public.commercial_purchase_order_items poi
    ON poi.id = NEW.purchase_order_item_id
   AND poi.purchase_order_id = pr.purchase_order_id
   AND poi.organization_id = pr.organization_id
  JOIN public.commercial_inventory_receipts ir
    ON ir.id = NEW.inventory_receipt_id
   AND ir.organization_id = pr.organization_id
  JOIN public.commercial_offerings o ON o.id = NEW.offering_id
  JOIN public.commercial_activities a ON a.id = o.activity_id
  WHERE pr.id = NEW.receipt_id
    AND pr.organization_id = NEW.organization_id
    AND pr.purchase_order_id = NEW.purchase_order_id;

  IF NOT FOUND
     OR v_receipt_location IS NULL
     OR v_order_location IS DISTINCT FROM v_receipt_location
     OR v_line_offering IS DISTINCT FROM NEW.offering_id
     OR v_inventory_organization IS DISTINCT FROM NEW.organization_id
     OR v_inventory_location IS DISTINCT FROM v_receipt_location
     OR v_inventory_offering IS DISTINCT FROM NEW.offering_id
     OR v_offering_location IS DISTINCT FROM v_receipt_location::text
     OR v_activity_organization IS DISTINCT FROM NEW.organization_id
     OR v_inventory_source_ref IS DISTINCT FROM
       ('purchase-receipt:' || NEW.receipt_id::text || ':' || NEW.purchase_order_item_id::text) THEN
    RAISE EXCEPTION 'purchase_receipt_item_location_or_offering_scope_mismatch'
      USING ERRCODE='23514';
  END IF;

  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS commercial_purchase_receipt_item_location_scope_guard
  ON public.commercial_purchase_receipt_items;
CREATE TRIGGER commercial_purchase_receipt_item_location_scope_guard
BEFORE INSERT OR UPDATE OF organization_id, receipt_id, purchase_order_id,
  purchase_order_item_id, offering_id, inventory_receipt_id
ON public.commercial_purchase_receipt_items
FOR EACH ROW
EXECUTE FUNCTION commercial_private.commercial_guard_purchase_receipt_item_location_scope();

REVOKE ALL ON FUNCTION commercial_private.commercial_guard_purchase_order_item_receiving_location()
  FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION commercial_private.commercial_guard_purchase_order_receiving_location_change()
  FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION commercial_private.commercial_guard_purchase_receipt_item_location_scope()
  FROM PUBLIC, anon, authenticated;

COMMENT ON FUNCTION commercial_private.commercial_guard_purchase_receipt_item_location_scope() IS
'Prevents a purchase receipt from posting an offering or inventory receipt to a branch different from the purchase order receiving location. Runs inside the posting transaction so any mismatch rolls back stock, ledger and receipt writes.';

COMMIT;
