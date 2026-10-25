-- Schema-level regression checks for explicit checkout discount/tax amounts.
-- Run after migrations in a disposable local/staging database with pgTAP installed.
BEGIN;
SELECT plan(10);

SELECT has_column('public','commercial_sales','subtotal_minor','invoice persists subtotal');
SELECT has_column('public','commercial_sales','discount_minor','invoice persists discount');
SELECT has_column('public','commercial_sales','tax_minor','invoice persists tax');
SELECT has_check('public','commercial_sales','commercial_sales_amounts_nonnegative',
  'invoice amounts are nonnegative and discount cannot exceed subtotal');
SELECT has_check('public','commercial_sales','commercial_sales_total_reconciles',
  'invoice total reconciles as subtotal minus discount plus tax');
SELECT has_function('public','commercial_checkout_sale_multi_priced',
  ARRAY['jsonb','uuid','text','uuid','text','bigint','bigint','bigint'],
  'priced checkout RPC requires explicit discount and tax arguments');
SELECT ok(
  NOT has_function_privilege('anon',
    'public.commercial_checkout_sale_multi_priced(jsonb,uuid,text,uuid,text,bigint,bigint,bigint)',
    'EXECUTE'),
  'anonymous users cannot execute priced checkout');
SELECT ok(
  position('discount_exceeds_subtotal' in pg_get_functiondef(
    'commercial_private.commercial_checkout_sale_multi_priced(jsonb,uuid,text,uuid,text,bigint,bigint,bigint)'::regprocedure
  )) > 0,
  'checkout rejects discounts larger than subtotal');
SELECT ok(
  position('idempotency_key_reused_with_different_request' in pg_get_functiondef(
    'commercial_private.commercial_checkout_sale_multi_priced(jsonb,uuid,text,uuid,text,bigint,bigint,bigint)'::regprocedure
  )) > 0
  AND position('discount_minor' in pg_get_functiondef(
    'commercial_private.commercial_checkout_sale_multi_priced(jsonb,uuid,text,uuid,text,bigint,bigint,bigint)'::regprocedure
  )) > 0,
  'idempotency fingerprint covers pricing inputs');
SELECT ok(
  position('sales_discounts' in pg_get_functiondef(
    'commercial_private.commercial_checkout_sale_multi_priced(jsonb,uuid,text,uuid,text,bigint,bigint,bigint)'::regprocedure
  )) > 0
  AND position('sales_tax_payable' in pg_get_functiondef(
    'commercial_private.commercial_checkout_sale_multi_priced(jsonb,uuid,text,uuid,text,bigint,bigint,bigint)'::regprocedure
  )) > 0,
  'discount and tax use explicit configured GL mappings');
SELECT * FROM finish();
ROLLBACK;
