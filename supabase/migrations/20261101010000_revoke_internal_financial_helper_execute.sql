-- These helpers are implementation details invoked only by SECURITY DEFINER
-- checkout/payment wrappers or the organization-insert trigger. Direct API
-- callers must not be able to create ledger accounts or customer party accounts
-- for organizations they do not administer.
REVOKE ALL ON FUNCTION commercial_private.commercial_customer_party_account(uuid,uuid,text)
  FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION commercial_private.commercial_initialize_gl_chart(uuid)
  FROM PUBLIC, anon, authenticated;
