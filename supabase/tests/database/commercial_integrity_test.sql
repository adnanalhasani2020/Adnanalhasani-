-- Run with Supabase CLI / pgTAP in a disposable local or staging database.
-- These are schema regression tests; they do not replace authenticated-session integration tests.
BEGIN;
SELECT plan(20);

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
  'all seven exposed security-definer functions pin their search_path');

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
       'commercial_restock_approved_sale_return'
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

SELECT * FROM finish();
ROLLBACK;
