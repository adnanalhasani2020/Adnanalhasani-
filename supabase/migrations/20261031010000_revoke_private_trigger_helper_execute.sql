-- Harden internal SECURITY DEFINER helpers in the non-exposed schema.
-- These routines are invoked by triggers or by owner-executed SECURITY DEFINER
-- entry points; they are not client RPCs and must not inherit PUBLIC EXECUTE.
BEGIN;

REVOKE ALL ON FUNCTION commercial_private.commercial_complete_return_after_restock() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION commercial_private.commercial_customer_party_account(uuid, uuid, text) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION commercial_private.commercial_gl_payment_status_trigger() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION commercial_private.commercial_gl_return_status_trigger() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION commercial_private.commercial_gl_sale_cancel_trigger() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION commercial_private.commercial_initialize_gl_chart(uuid) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION commercial_private.commercial_org_gl_init_trigger() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION commercial_private.commercial_validate_return_completion() FROM PUBLIC, anon, authenticated;

DO $$
DECLARE
  v_signature regprocedure;
BEGIN
  FOREACH v_signature IN ARRAY ARRAY[
    'commercial_private.commercial_complete_return_after_restock()'::regprocedure,
    'commercial_private.commercial_customer_party_account(uuid,uuid,text)'::regprocedure,
    'commercial_private.commercial_gl_payment_status_trigger()'::regprocedure,
    'commercial_private.commercial_gl_return_status_trigger()'::regprocedure,
    'commercial_private.commercial_gl_sale_cancel_trigger()'::regprocedure,
    'commercial_private.commercial_initialize_gl_chart(uuid)'::regprocedure,
    'commercial_private.commercial_org_gl_init_trigger()'::regprocedure,
    'commercial_private.commercial_validate_return_completion()'::regprocedure
  ] LOOP
    IF has_function_privilege('anon', v_signature, 'EXECUTE')
       OR has_function_privilege('authenticated', v_signature, 'EXECUTE') THEN
      RAISE EXCEPTION 'private_definer_execute_grant_remains:%', v_signature;
    END IF;
  END LOOP;
END;
$$;

COMMIT;
