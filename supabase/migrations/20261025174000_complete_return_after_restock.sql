-- Complete an approved return only after inventory disposition is settled.
-- This transition unlocks the existing accounting trigger and the refund RPC.
CREATE OR REPLACE FUNCTION commercial_private.commercial_complete_return_after_restock()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public', 'commercial_private'
AS $function$
BEGIN
  IF OLD.status = 'approved'
     AND NEW.status = 'approved'
     AND NEW.restock_status IN ('restocked', 'not_required') THEN
    UPDATE public.commercial_sales_returns
       SET status = 'completed',
           updated_at = now()
     WHERE id = NEW.id
       AND status = 'approved';
  END IF;
  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS commercial_return_complete_after_restock
  ON public.commercial_sales_returns;

CREATE TRIGGER commercial_return_complete_after_restock
AFTER UPDATE OF restock_status ON public.commercial_sales_returns
FOR EACH ROW
EXECUTE FUNCTION commercial_private.commercial_complete_return_after_restock();

COMMENT ON FUNCTION commercial_private.commercial_complete_return_after_restock() IS
'Moves an approved sale return to completed only after restocking is fully settled or explicitly not required; this enables accounting and refund execution.';
