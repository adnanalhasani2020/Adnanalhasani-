-- PostgreSQL truncates identifiers to 63 bytes. Rename the original overlong RPC
-- to a stable name that the Android PostgREST client can call without truncation.
ALTER FUNCTION public.commercial_customer_portal_create_delegated_multi_service_booki(
  uuid,uuid,jsonb,uuid,timestamptz,timestamptz,text,text,text
) RENAME TO commercial_customer_portal_create_delegated_multi_booking;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_create_delegated_multi_booking(
  uuid,uuid,jsonb,uuid,timestamptz,timestamptz,text,text,text
) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_create_delegated_multi_booking(
  uuid,uuid,jsonb,uuid,timestamptz,timestamptz,text,text,text
) TO authenticated;
COMMENT ON FUNCTION public.commercial_customer_portal_create_delegated_multi_booking(
  uuid,uuid,jsonb,uuid,timestamptz,timestamptz,text,text,text
) IS 'Creates a multi-service booking under an approved delegated account; parent is billed and child is attributed as actor.';
