-- Preserve delegated account attribution when an approved order is converted into an operational sale.
-- The order is billed to the parent's organization-specific customer account; the sale keeps the same parent/child attribution.
CREATE OR REPLACE FUNCTION public.commercial_sync_delegated_attribution_to_sale()
RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
  v_order record;
  v_updated_sale_id uuid;
BEGIN
  SELECT o.organization_id,o.delegated_access_id,o.principal_account_id,o.acting_account_id,o.buyer_account_id
    INTO v_order
  FROM public.commercial_customer_orders o
  WHERE o.id=NEW.order_id AND o.organization_id=NEW.organization_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'fulfilled_order_not_found_for_attribution' USING ERRCODE='P0002';
  END IF;
  IF v_order.delegated_access_id IS NULL THEN
    RETURN NEW;
  END IF;
  IF v_order.principal_account_id IS NULL OR v_order.acting_account_id IS NULL THEN
    RAISE EXCEPTION 'delegated_order_attribution_incomplete' USING ERRCODE='23514';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_account_delegations d
    WHERE d.id=v_order.delegated_access_id
      AND d.organization_id=NEW.organization_id
      AND d.parent_account_id=v_order.principal_account_id
      AND d.child_account_id=v_order.acting_account_id
  ) THEN
    RAISE EXCEPTION 'delegated_order_attribution_mismatch' USING ERRCODE='23514';
  END IF;

  UPDATE public.commercial_sales s
  SET delegated_access_id=v_order.delegated_access_id,
      principal_account_id=v_order.principal_account_id,
      acting_account_id=v_order.acting_account_id
  WHERE s.id=NEW.sale_id
    AND s.organization_id=NEW.organization_id
    AND EXISTS (
      SELECT 1 FROM public.commercial_customer_party_accounts cpa
      WHERE cpa.organization_id=s.organization_id
        AND cpa.customer_id=s.customer_id
        AND cpa.party_account_id=v_order.buyer_account_id
    )
  RETURNING s.id INTO v_updated_sale_id;

  IF v_updated_sale_id IS NULL THEN
    RAISE EXCEPTION 'delegated_sale_parent_customer_mismatch' USING ERRCODE='23514';
  END IF;

  INSERT INTO public.commercial_audit_events(
    organization_id, actor_user_id, action, entity_type, entity_id, metadata
  ) VALUES (
    NEW.organization_id, NEW.fulfilled_by, 'delegated_order.sale_attributed', 'sale', NEW.sale_id::text,
    jsonb_build_object(
      'order_id',NEW.order_id,
      'delegation_id',v_order.delegated_access_id,
      'principal_account_id',v_order.principal_account_id,
      'acting_account_id',v_order.acting_account_id
    )
  );
  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS trg_commercial_sync_delegated_attribution_to_sale ON public.commercial_customer_order_fulfillments;
CREATE TRIGGER trg_commercial_sync_delegated_attribution_to_sale
AFTER INSERT ON public.commercial_customer_order_fulfillments
FOR EACH ROW EXECUTE FUNCTION public.commercial_sync_delegated_attribution_to_sale();

REVOKE ALL ON FUNCTION public.commercial_sync_delegated_attribution_to_sale() FROM PUBLIC, anon, authenticated;
