-- Add covering indexes for the exact foreign keys reported by Supabase's performance advisor.
-- The index columns are derived from pg_constraint at migration time, so composite FK order is preserved.
BEGIN;

DO $$
DECLARE
  v_constraint_names text[] := ARRAY[
    'commercial_expense_vouchers_journal_entry_id_fkey',
    'commercial_expense_vouchers_reversal_journal_entry_id_fkey',
    'commercial_inventory_receipts_created_by_fkey',
    'commercial_inventory_receipts_location_id_fkey',
    'commercial_inventory_receipts_offering_id_fkey',
    'commercial_purchase_order_items_offering_id_fkey',
    'commercial_purchase_orders_cancelled_by_fkey',
    'commercial_purchase_orders_receive_location_id_fkey',
    'commercial_purchase_receipt_i_organization_id_purchase_ord_fkey',
    'commercial_purchase_receipt_i_organization_id_receipt_id_p_fkey',
    'commercial_purchase_receipt_items_inventory_receipt_id_fkey',
    'commercial_purchase_receipt_items_offering_id_fkey',
    'commercial_purchase_receipt_items_organization_id_fkey',
    'commercial_purchase_receipt_items_purchase_order_item_id_fkey',
    'commercial_purchase_receipts_location_id_fkey',
    'commercial_purchase_receipts_organization_id_purchase_orde_fkey',
    'commercial_purchase_receipts_received_by_fkey',
    'commercial_return_refund_postings_executed_by_fkey',
    'commercial_return_restock_postings_created_by_fkey',
    'commercial_return_restock_postings_location_id_fkey',
    'commercial_return_restock_postings_offering_id_fkey',
    'commercial_return_restock_postings_organization_id_fkey',
    'commercial_return_restock_postings_return_id_fkey',
    'commercial_supplier_gl_accoun_organization_id_ledger_accou_fkey',
    'commercial_supplier_invoice_items_purchase_order_item_id_fkey',
    'commercial_supplier_invoices_purchase_order_id_fkey',
    'commercial_supplier_invoices_recorded_by_fkey',
    'commercial_supplier_invoices_supplier_id_fkey',
    'commercial_supplier_obligations_journal_entry_id_fkey',
    'commercial_supplier_obligations_party_account_id_fkey',
    'commercial_supplier_payment_obligation_identity_fk',
    'commercial_supplier_payments_journal_entry_id_fkey',
    'commercial_supplier_payments_obligation_id_fkey',
    'commercial_supplier_payments_recorded_by_fkey',
    'commercial_supplier_payments_supplier_invoice_id_fkey'
  ];
  v_constraint record;
  v_columns text;
  v_index_name text;
  v_found integer := 0;
BEGIN
  FOR v_constraint IN
    SELECT c.oid, c.conname, c.conrelid
    FROM pg_constraint c
    JOIN pg_class t ON t.oid=c.conrelid
    JOIN pg_namespace n ON n.oid=t.relnamespace
    WHERE c.contype='f'
      AND n.nspname='public'
      AND c.conname=ANY(v_constraint_names)
  LOOP
    v_found := v_found + 1;
    SELECT string_agg(format('%I', a.attname), ', ' ORDER BY k.ordinality)
      INTO v_columns
    FROM unnest((SELECT conkey FROM pg_constraint WHERE oid=v_constraint.oid))
      WITH ORDINALITY AS k(attnum, ordinality)
    JOIN pg_attribute a ON a.attrelid=v_constraint.conrelid AND a.attnum=k.attnum;

    v_index_name := left('ix_fk_' || v_constraint.conname, 48)
      || '_' || substr(md5(v_constraint.conname), 1, 10);

    EXECUTE format(
      'CREATE INDEX IF NOT EXISTS %I ON %s (%s)',
      v_index_name,
      v_constraint.conrelid::regclass,
      v_columns
    );
  END LOOP;

  IF v_found <> cardinality(v_constraint_names) THEN
    RAISE EXCEPTION 'Expected % foreign keys for indexing, found %',
      cardinality(v_constraint_names), v_found;
  END IF;
END $$;

COMMIT;
