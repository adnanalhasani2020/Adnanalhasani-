-- Cache auth.uid() once per statement in the remaining high-traffic commercial RLS policies.
-- Authorization predicates and role scopes are intentionally unchanged.
BEGIN;

DROP POLICY IF EXISTS commercial_expense_vouchers_finance_read ON public.commercial_expense_vouchers;
CREATE POLICY commercial_expense_vouchers_finance_read ON public.commercial_expense_vouchers
 FOR SELECT TO authenticated
 USING (EXISTS (
  SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=commercial_expense_vouchers.organization_id
   AND m.user_id=(SELECT auth.uid()) AND m.state='active'
   AND m.role=ANY(ARRAY['owner','admin','manager']::text[])
 ));

DROP POLICY IF EXISTS commercial_gl_mappings_manage ON public.commercial_gl_account_mappings;
CREATE POLICY commercial_gl_mappings_manage ON public.commercial_gl_account_mappings
 FOR ALL TO authenticated
 USING (EXISTS (
  SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=commercial_gl_account_mappings.organization_id
   AND m.user_id=(SELECT auth.uid()) AND m.state='active'
   AND m.role=ANY(ARRAY['owner','admin','accountant']::text[])
 ))
 WITH CHECK (EXISTS (
  SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=commercial_gl_account_mappings.organization_id
   AND m.user_id=(SELECT auth.uid()) AND m.state='active'
   AND m.role=ANY(ARRAY['owner','admin','accountant']::text[])
 ));

DROP POLICY IF EXISTS commercial_gl_mappings_read ON public.commercial_gl_account_mappings;
CREATE POLICY commercial_gl_mappings_read ON public.commercial_gl_account_mappings
 FOR SELECT TO authenticated
 USING (EXISTS (
  SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=commercial_gl_account_mappings.organization_id
   AND m.user_id=(SELECT auth.uid()) AND m.state='active'
 ));

DROP POLICY IF EXISTS commercial_offering_sell_units_member_read ON public.commercial_offering_sell_units;
CREATE POLICY commercial_offering_sell_units_member_read ON public.commercial_offering_sell_units
 FOR SELECT TO authenticated
 USING (EXISTS (
  SELECT 1
  FROM public.commercial_offerings o
  JOIN public.commercial_activities a ON a.id=o.activity_id
  JOIN public.commercial_organization_members m ON m.organization_id=a.organization_id
  WHERE o.id=commercial_offering_sell_units.offering_id
   AND m.user_id=(SELECT auth.uid()) AND m.state='active'
 ));

DROP POLICY IF EXISTS commercial_purchase_receipt_items_org_read ON public.commercial_purchase_receipt_items;
CREATE POLICY commercial_purchase_receipt_items_org_read ON public.commercial_purchase_receipt_items
 FOR SELECT TO authenticated
 USING (EXISTS (
  SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=commercial_purchase_receipt_items.organization_id
   AND m.user_id=(SELECT auth.uid()) AND m.state='active'
 ));

DROP POLICY IF EXISTS commercial_purchase_receipts_org_read ON public.commercial_purchase_receipts;
CREATE POLICY commercial_purchase_receipts_org_read ON public.commercial_purchase_receipts
 FOR SELECT TO authenticated
 USING (EXISTS (
  SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=commercial_purchase_receipts.organization_id
   AND m.user_id=(SELECT auth.uid()) AND m.state='active'
 ));

DROP POLICY IF EXISTS commercial_supplier_invoice_items_org_read ON public.commercial_supplier_invoice_items;
CREATE POLICY commercial_supplier_invoice_items_org_read ON public.commercial_supplier_invoice_items
 FOR SELECT TO authenticated
 USING (EXISTS (
  SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=commercial_supplier_invoice_items.organization_id
   AND m.user_id=(SELECT auth.uid()) AND m.state='active'
 ));

DROP POLICY IF EXISTS commercial_supplier_invoices_org_read ON public.commercial_supplier_invoices;
CREATE POLICY commercial_supplier_invoices_org_read ON public.commercial_supplier_invoices
 FOR SELECT TO authenticated
 USING (EXISTS (
  SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=commercial_supplier_invoices.organization_id
   AND m.user_id=(SELECT auth.uid()) AND m.state='active'
 ));

DROP POLICY IF EXISTS commercial_supplier_obligations_org_read ON public.commercial_supplier_obligations;
CREATE POLICY commercial_supplier_obligations_org_read ON public.commercial_supplier_obligations
 FOR SELECT TO authenticated
 USING (EXISTS (
  SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=commercial_supplier_obligations.organization_id
   AND m.user_id=(SELECT auth.uid()) AND m.state='active'
 ));

DROP POLICY IF EXISTS commercial_supplier_payments_org_read ON public.commercial_supplier_payments;
CREATE POLICY commercial_supplier_payments_org_read ON public.commercial_supplier_payments
 FOR SELECT TO authenticated
 USING (EXISTS (
  SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=commercial_supplier_payments.organization_id
   AND m.user_id=(SELECT auth.uid()) AND m.state='active'
 ));

COMMIT;
