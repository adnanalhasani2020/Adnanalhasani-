-- Release-gate regression checks for receivables, fractional quantities, and ledger integrity.
-- Run after all repository migrations in a disposable local/staging Supabase database:
--   supabase test db
-- This is intentionally a gate, not a substitute for authenticated end-to-end checkout/payment tests.
BEGIN;
CREATE EXTENSION IF NOT EXISTS pgtap WITH SCHEMA extensions;
SELECT plan(10);

-- Receivable arithmetic: approved return credit reduces the sale obligation once;
-- an executed cash refund also reduces net cash paid, not the sale credit itself.
SELECT is(
  GREATEST(10000::bigint - GREATEST(5000::bigint - 0::bigint, 0) - 2000::bigint, 0),
  3000::bigint,
  'partial return credit reduces unpaid receivable'
);
SELECT is(
  GREATEST(10000::bigint - GREATEST(5000::bigint - 2000::bigint, 0) - 2000::bigint, 0),
  5000::bigint,
  'executed refund and return credit are both reflected without double-crediting'
);
SELECT is(
  GREATEST(10000::bigint - GREATEST(10000::bigint - 2000::bigint, 0) - 2000::bigint, 0),
  0::bigint,
  'fully paid sale with a refunded completed return has no outstanding receivable'
);
SELECT is(
  GREATEST(10000::bigint - GREATEST(10000::bigint, 0) - 10000::bigint, 0),
  0::bigint,
  'fully returned sale cannot produce a negative receivable'
);

-- Ensure the server-side collection guard and GL posting path use return/refund adjustments.
SELECT ok(
  position('v_credited' in pg_get_functiondef(
    'commercial_private.commercial_record_customer_payment(uuid,bigint,text,text)'::regprocedure
  )) > 0
  AND position('v_refunded' in pg_get_functiondef(
    'commercial_private.commercial_record_customer_payment(uuid,bigint,text,text)'::regprocedure
  )) > 0,
  'customer payment RPC derives outstanding amount from return credits and executed refunds'
);
SELECT ok(
  position('v_credited' in pg_get_functiondef(
    'public.commercial_gl_post_payment(uuid)'::regprocedure
  )) > 0
  AND position('v_refunded' in pg_get_functiondef(
    'public.commercial_gl_post_payment(uuid)'::regprocedure
  )) > 0,
  'GL payment guard uses the same return-adjusted receivable logic'
);

-- This assertion is expected to remain red until the coordinated fractional-sale
-- migration changes the canonical sale quantity and stock-unit quantity to NUMERIC.
SELECT ok(
  (SELECT data_type = 'numeric'
   FROM information_schema.columns
   WHERE table_schema = 'public'
     AND table_name = 'commercial_sale_items'
     AND column_name = 'quantity'),
  'sale line canonical quantity supports fractional values'
);
SELECT ok(
  (SELECT data_type = 'numeric'
   FROM information_schema.columns
   WHERE table_schema = 'public'
     AND table_name = 'commercial_sale_items'
     AND column_name = 'quantity_in_stock_units'),
  'converted base-stock quantity supports fractional values'
);

SELECT ok(
  NOT EXISTS (
    SELECT 1
    FROM public.commercial_journal_entries e
    JOIN public.commercial_journal_lines l ON l.journal_entry_id = e.id
    WHERE e.status = 'posted'
    GROUP BY e.id, l.currency_code
    HAVING sum(l.debit_minor) <> sum(l.credit_minor)
  ),
  'all posted journal entries balance by currency'
);
SELECT ok(
  NOT EXISTS (
    SELECT 1 FROM public.commercial_ar_control_reconciliation
    WHERE NOT is_reconciled OR difference_minor <> 0
  ),
  'customer AR control reconciliation has no unreconciled differences'
);

SELECT * FROM finish();
ROLLBACK;
