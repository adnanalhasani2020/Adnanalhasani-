-- Run with Supabase CLI / pgTAP in a disposable local or staging database.
-- These are schema regression tests; they do not replace authenticated-session integration tests.
BEGIN;
SELECT plan(44);

SELECT has_table('public', 'commercial_audit_events',
  'append-only audit event table exists');

SELECT has_trigger('public', 'commercial_journal_entries',
  'commercial_journal_entries_audit_trg',
  'journal entry lifecycle changes are audited');

SELECT has_trigger('public', 'commercial_sales_returns',
  'commercial_sales_returns_audit_trg',
  'sale return lifecycle changes are audited');

SELECT has_trigger('public', 'commercial_sale_return_items',
  'commercial_sale_return_items_audit_trg',
  'sale return item changes are audited');

SELECT has_trigger('public', 'commercial_journal_lines',
  'commercial_journal_lines_immutable_after_posting',
  'journal lines have a posting immutability guard');

SELECT has_check('public', 'commercial_journal_lines',
  'journal lines enforce amount integrity with a check constraint');

SELECT has_index('public', 'commercial_journal_lines',
  'commercial_journal_lines_journal_entry_id_line_number_key',
  'journal line numbers are unique within an entry');

SELECT ok(
  (SELECT c.relrowsecurity
   FROM pg_class c
   JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = 'public' AND c.relname = 'commercial_audit_events'),
  'audit events table has row-level security enabled');

SELECT ok(
  NOT has_table_privilege('authenticated', 'public.commercial_audit_events', 'INSERT'),
  'authenticated users cannot directly insert audit events');

SELECT ok(
  NOT has_table_privilege('authenticated', 'public.commercial_audit_events', 'UPDATE'),
  'authenticated users cannot directly update audit events');

SELECT ok(
  NOT has_table_privilege('authenticated', 'public.commercial_audit_events', 'DELETE'),
  'authenticated users cannot directly delete audit events');

SELECT ok(
  (SELECT count(*) = 7
   FROM pg_proc p
   JOIN pg_namespace n ON n.oid = p.pronamespace
   WHERE n.nspname = 'public'
     AND p.proname IN (
       'commercial_add_global_item_to_organization',
       'commercial_create_personal_account',
       'commercial_decide_sale_return',
       'commercial_inspect_sale_return_item',
       'commercial_post_journal_entry',
       'commercial_request_sale_return',
       'commercial_restock_approved_sale_return'
     )
     AND p.prosecdef
     AND coalesce(p.proconfig, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog, public']),
  'all seven existing exposed security-definer functions pin their search_path');

SELECT ok(
  (SELECT count(*) = 0
   FROM pg_proc p
   JOIN pg_namespace n ON n.oid = p.pronamespace
   WHERE n.nspname = 'public'
     AND p.proname IN (
       'commercial_add_global_item_to_organization',
       'commercial_create_personal_account',
       'commercial_decide_sale_return',
       'commercial_inspect_sale_return_item',
       'commercial_post_journal_entry',
       'commercial_request_sale_return',
       'commercial_restock_approved_sale_return',
       'commercial_create_organization'
     )
     AND has_function_privilege('anon', p.oid, 'EXECUTE')),
  'anonymous users cannot execute sensitive commercial functions');

SELECT has_index('public', 'commercial_sales_returns',
  'commercial_sales_returns_sale_id_idempotency_key_key',
  'sale return requests have a unique sale/idempotency key');

SELECT has_index('public', 'commercial_inventory',
  'commercial_inventory_offering_id_key',
  'personal inventory has one row per offering');

SELECT ok(
  position('p_items IS NULL' in pg_get_functiondef(
    'public.commercial_request_sale_return(uuid,text,text,jsonb,text)'::regprocedure
  )) > 0
  AND position('duplicate_sale_item_in_return_request' in pg_get_functiondef(
    'public.commercial_request_sale_return(uuid,text,text,jsonb,text)'::regprocedure
  )) > 0,
  'return RPC rejects null item arrays and duplicate sale lines in one request');

SELECT has_index('public', 'commercial_party_accounts',
  'commercial_party_accounts_one_active_personal_per_user_key',
  'each user can have at most one active personal account');

SELECT ok(
  position('p_decision IS NULL' in pg_get_functiondef(
    'public.commercial_decide_sale_return(uuid,text,bigint,text)'::regprocedure
  )) > 0,
  'return decision RPC rejects a null decision');

SELECT ok(
  position('p_condition_code IS NULL' in pg_get_functiondef(
    'public.commercial_inspect_sale_return_item(uuid,text,boolean,text)'::regprocedure
  )) > 0
  AND position('p_restock IS NULL' in pg_get_functiondef(
    'public.commercial_inspect_sale_return_item(uuid,text,boolean,text)'::regprocedure
  )) > 0,
  'return inspection RPC rejects null condition and restock inputs');

SELECT ok(
  (SELECT count(*) = 0
   FROM pg_class c
   JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = 'public'
     AND c.relkind = 'r'
     AND (NOT c.relrowsecurity OR NOT EXISTS (
       SELECT 1 FROM pg_policy pol WHERE pol.polrelid = c.oid
     ))),
  'every public table has RLS enabled and at least one policy');

SELECT ok(
  (SELECT p.prosecdef
          AND coalesce(p.proconfig, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog, public']
          AND NOT has_function_privilege('anon', p.oid, 'EXECUTE')
          AND has_function_privilege('authenticated', p.oid, 'EXECUTE')
   FROM pg_proc p
   JOIN pg_namespace n ON n.oid = p.pronamespace
   WHERE n.nspname = 'public'
     AND p.proname = 'commercial_create_organization'
     AND pg_get_function_identity_arguments(p.oid) =
       'p_name text, p_slug text, p_organization_type text, p_currency_code text, p_timezone text, p_locale text, p_country_code text, p_module_keys text[]'),
  'organization onboarding RPC is hardened and restricted to authenticated users');

SELECT ok(
  EXISTS (
    SELECT 1 FROM pg_policy p
    JOIN pg_class c ON c.oid = p.polrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public'
      AND c.relname = 'commercial_sales'
      AND p.polname = 'organization managers read sales'
      AND p.polcmd = 'r'
      AND position('owner' in pg_get_expr(p.polqual, p.polrelid)) > 0
      AND position('manager' in pg_get_expr(p.polqual, p.polrelid)) > 0
  ),
  'organization owners, admins and managers have a read policy for organization sales'
);

SELECT ok(
  EXISTS (
    SELECT 1 FROM pg_policy p
    JOIN pg_class c ON c.oid = p.polrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public'
      AND c.relname = 'commercial_sale_items'
      AND p.polname = 'organization managers read sale items'
      AND p.polcmd = 'r'
      AND position('commercial_sales' in pg_get_expr(p.polqual, p.polrelid)) > 0
      AND position('manager' in pg_get_expr(p.polqual, p.polrelid)) > 0
  ),
  'organization managers can read sale lines only through sales in their organization'
);

SELECT ok(
  position('a.currency_code IS DISTINCT FROM l.currency_code' in pg_get_functiondef(
    'public.commercial_post_journal_entry(uuid)'::regprocedure
  )) > 0,
  'journal posting rejects account/line currency mismatches');

SELECT ok(
  position('a.is_active IS DISTINCT FROM true' in pg_get_functiondef(
    'public.commercial_post_journal_entry(uuid)'::regprocedure
  )) > 0,
  'journal posting rejects inactive ledger accounts');


SELECT ok(
  position('paid_minor' in pg_get_functiondef(
    'commercial_private.commercial_checkout_sale_multi(jsonb,uuid,text,uuid,text,bigint)'::regprocedure
  )) > 0,
  'multi-line checkout returns confirmed paid amount on idempotent replay');

SELECT ok(
  position('outstanding_minor' in pg_get_functiondef(
    'commercial_private.commercial_checkout_sale_multi(jsonb,uuid,text,uuid,text,bigint)'::regprocedure
  )) > 0,
  'multi-line checkout returns outstanding balance on idempotent replay');


SELECT ok(
  (SELECT
     (length(pg_get_functiondef(
       'commercial_private.commercial_checkout_sale_multi(jsonb,uuid,text,uuid,text,bigint)'::regprocedure
     )) - length(replace(pg_get_functiondef(
       'commercial_private.commercial_checkout_sale_multi(jsonb,uuid,text,uuid,text,bigint)'::regprocedure
     ), '''paid_minor''', ''))) / length('''paid_minor''') >= 2
     AND position('sum(p.amount_minor)' in pg_get_functiondef(
       'commercial_private.commercial_checkout_sale_multi(jsonb,uuid,text,uuid,text,bigint)'::regprocedure
     )) > 0
     AND position('outstanding_minor' in pg_get_functiondef(
       'commercial_private.commercial_checkout_sale_multi(jsonb,uuid,text,uuid,text,bigint)'::regprocedure
     )) > 0),
  'first checkout response and idempotent replay derive paid/outstanding balances from persisted successful payments'
);


SELECT ok(
  position('INSERT INTO public.commercial_audit_events' in pg_get_functiondef(
    'commercial_private.commercial_checkout_sale_multi(jsonb,uuid,text,uuid,text,bigint)'::regprocedure
  )) > 0
  AND position('paid_minor' in substring(pg_get_functiondef(
    'commercial_private.commercial_checkout_sale_multi(jsonb,uuid,text,uuid,text,bigint)'::regprocedure
  ) from position('INSERT INTO public.commercial_audit_events' in pg_get_functiondef(
    'commercial_private.commercial_checkout_sale_multi(jsonb,uuid,text,uuid,text,bigint)'::regprocedure
  )))) > 0
  AND position('outstanding_minor' in substring(pg_get_functiondef(
    'commercial_private.commercial_checkout_sale_multi(jsonb,uuid,text,uuid,text,bigint)'::regprocedure
  ) from position('INSERT INTO public.commercial_audit_events' in pg_get_functiondef(
    'commercial_private.commercial_checkout_sale_multi(jsonb,uuid,text,uuid,text,bigint)'::regprocedure
  )))) > 0,
  'checkout audit event records persisted paid and outstanding amounts'
);


SELECT ok(
  position('''payment_recorded'',EXISTS(SELECT 1 FROM public.commercial_payments' in pg_get_functiondef(
    'commercial_private.commercial_checkout_sale_multi(jsonb,uuid,text,uuid,text,bigint)'::regprocedure
  )) > 0
  AND position('''paid_minor'',(SELECT coalesce(sum(p.amount_minor),0)::bigint FROM public.commercial_payments' in pg_get_functiondef(
    'commercial_private.commercial_checkout_sale_multi(jsonb,uuid,text,uuid,text,bigint)'::regprocedure
  )) > 0,
  'first checkout response derives payment-recorded status and paid amount from persisted successful payment rows'
);


-- Accounting diagnostics must not be exposed through the public Data API.
SELECT ok(
  NOT has_table_privilege('anon', 'public.commercial_accounting_integrity_checks', 'SELECT'),
  'anonymous API clients cannot read accounting integrity diagnostics'
);

SELECT ok(
  NOT has_table_privilege('authenticated', 'public.commercial_accounting_integrity_checks', 'SELECT'),
  'authenticated API clients cannot read privileged accounting integrity diagnostics'
);

SELECT ok(
  has_table_privilege('service_role', 'public.commercial_accounting_integrity_checks', 'SELECT'),
  'trusted server-side service role retains access to accounting integrity diagnostics'
);

-- Purchase fractional-quantity and cumulative accounting regressions.
SELECT ok(
  position('scale(v_qty)>6' in pg_get_functiondef(
    'commercial_private.commercial_receive_purchase_order(uuid,uuid,uuid,jsonb,text)'::regprocedure
  )) > 0
  AND position('v_qty<>trunc(v_qty)' in pg_get_functiondef(
    'commercial_private.commercial_receive_purchase_order(uuid,uuid,uuid,jsonb,text)'::regprocedure
  )) = 0,
  'effective purchase receipt RPC accepts fractional quantities up to six decimal places'
);

SELECT ok(
  position('round((prior.quantity_before + ri.quantity::numeric) * ri.unit_cost_minor::numeric)' in pg_get_functiondef(
    'commercial_private.commercial_post_purchase_receipt_financials()'::regprocedure
  )) > 0
  AND position('pr.accounting_sequence < NEW.accounting_sequence' in pg_get_functiondef(
    'commercial_private.commercial_post_purchase_receipt_financials()'::regprocedure
  )) > 0,
  'purchase receipt posting uses cumulative per-line rounding in accounting sequence order'
);

SELECT ok(
  position('round((prior.quantity_before + si.quantity::numeric) * poi.unit_cost_minor::numeric)' in pg_get_functiondef(
    'commercial_private.commercial_post_supplier_invoice_financials()'::regprocedure
  )) > 0
  AND position('prior_invoice.accounting_sequence < NEW.accounting_sequence' in pg_get_functiondef(
    'commercial_private.commercial_post_supplier_invoice_financials()'::regprocedure
  )) > 0,
  'supplier invoice posting uses cumulative per-line rounding in accounting sequence order'
);

SELECT has_trigger('public', 'commercial_purchase_receipts',
  'commercial_purchase_receipt_financials_after_insert',
  'purchase receipt accounting is wired through a deferred constraint trigger');

SELECT has_trigger('public', 'commercial_supplier_invoices',
  'commercial_supplier_invoice_financials_after_insert',
  'supplier invoice accounting is wired through a deferred constraint trigger');


-- Catalog setup RPCs are privileged writes: pin search_path and restrict execution.
SELECT ok(
  (SELECT count(*) = 2
   FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
   WHERE n.nspname = 'public'
     AND p.proname IN ('commercial_catalog_link_unpriced_items','commercial_catalog_assign_items_to_activity')
     AND p.prosecdef
     AND coalesce(p.proconfig, ARRAY[]::text[]) @> ARRAY['search_path=pg_catalog, public']
     AND NOT has_function_privilege('anon', p.oid, 'EXECUTE')
     AND has_function_privilege('authenticated', p.oid, 'EXECUTE')),
  'catalog link and activity assignment RPCs pin search_path and are executable only by authenticated callers'
);
SELECT ok(
  (SELECT c.relrowsecurity AND EXISTS (SELECT 1 FROM pg_policy pol WHERE pol.polrelid = c.oid)
   FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = 'public' AND c.relname = 'commercial_organization_catalog_item_activities'),
  'organization catalog activity mapping is protected by RLS and an explicit policy'
);
SELECT ok(
  (SELECT position('commercial_catalog_member_of' in pg_get_functiondef(p.oid)) > 0
          AND position('cardinality(p_global_item_ids) NOT BETWEEN 1 AND 100' in pg_get_functiondef(p.oid)) > 0
          AND position('array_position(p_global_item_ids, NULL)' in pg_get_functiondef(p.oid)) > 0
          AND position('duplicate_catalog_item_selection' in pg_get_functiondef(p.oid)) > 0
   FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
   WHERE n.nspname = 'public' AND p.proname = 'commercial_catalog_link_unpriced_items'),
  'catalog linking validates membership, bounded non-null unique selections before writes'
);


-- Repository-wide SECURITY DEFINER audit: no public function may be callable by anon,
-- and every such function must pin search_path (an explicitly empty path is also pinned).
SELECT ok(
  (SELECT count(*) = 0
   FROM pg_proc p
   JOIN pg_namespace n ON n.oid = p.pronamespace
   WHERE n.nspname = 'public'
     AND p.prosecdef
     AND (
       has_function_privilege('anon', p.oid, 'EXECUTE')
       OR NOT EXISTS (
         SELECT 1 FROM unnest(coalesce(p.proconfig, ARRAY[]::text[])) AS settings(setting)
         WHERE settings.setting LIKE 'search_path=%'
       )
     )),
  'all public SECURITY DEFINER functions pin search_path and none are executable by anon'
);


-- Internal financial helpers are called only by SECURITY DEFINER wrappers or
-- triggers; they are not public RPC entry points.
SELECT ok(
  (SELECT count(*) = 0
   FROM pg_proc p
   JOIN pg_namespace n ON n.oid = p.pronamespace
   WHERE n.nspname = 'commercial_private'
     AND p.proname IN ('commercial_customer_party_account','commercial_initialize_gl_chart')
     AND has_function_privilege('authenticated', p.oid, 'EXECUTE'))
  AND (SELECT count(*) = 0
       FROM pg_proc p
       JOIN pg_namespace n ON n.oid = p.pronamespace
       WHERE n.nspname = 'commercial_private'
         AND p.proname IN ('commercial_customer_party_account','commercial_initialize_gl_chart')
         AND has_function_privilege('anon', p.oid, 'EXECUTE')),
  'internal customer-party and GL initialization helpers are not directly executable by API roles'
);


SELECT ok(
  (SELECT count(*) = 7
   FROM pg_proc p
   JOIN pg_namespace n ON n.oid = p.pronamespace
   WHERE n.nspname = 'public'
     AND p.proname IN (
       'commercial_add_global_item_to_organization',
       'commercial_create_personal_account',
       'commercial_decide_sale_return',
       'commercial_inspect_sale_return_item',
       'commercial_post_journal_entry',
       'commercial_request_sale_return',
       'commercial_restock_approved_sale_return'
     )
     AND p.prosecdef
     AND has_function_privilege('authenticated', p.oid, 'EXECUTE')
     AND NOT has_function_privilege('anon', p.oid, 'EXECUTE')),
  'all seven protected commercial RPCs remain authenticated-only');

SELECT * FROM finish();
ROLLBACK;
