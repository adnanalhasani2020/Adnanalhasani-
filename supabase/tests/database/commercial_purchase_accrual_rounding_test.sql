-- Arithmetic regression tests for fractional receipt/invoice accrual allocation.
CREATE EXTENSION IF NOT EXISTS pgtap WITH SCHEMA extensions;
-- Run with Supabase CLI/pgTAP after migrations in a disposable local or staging database.
BEGIN;
SELECT plan(6);

WITH rounding_case AS (
  SELECT
    round((0::numeric + 0.5::numeric) * 1::numeric) - round(0::numeric * 1::numeric) AS first_delta,
    round((0.5::numeric + 0.5::numeric) * 1::numeric) - round(0.5::numeric * 1::numeric) AS second_delta
)
SELECT is(first_delta, 1::numeric, 'first half-unit receipt receives the initial rounded minor unit')
FROM rounding_case;
WITH rounding_case AS (
  SELECT
    (round((0::numeric + 0.5::numeric) * 1::numeric) - round(0::numeric * 1::numeric))
    + (round((0.5::numeric + 0.5::numeric) * 1::numeric) - round(0.5::numeric * 1::numeric)) AS total
)
SELECT is(total, 1::numeric, 'two half-unit receipts do not double-round the same minor unit')
FROM rounding_case;
WITH rounding_case AS (
  SELECT
    (round((0::numeric + 0.25::numeric) * 1::numeric) - round(0::numeric * 1::numeric))
    + (round((0.25::numeric + 0.25::numeric) * 1::numeric) - round(0.25::numeric * 1::numeric))
    + (round((0.5::numeric + 0.5::numeric) * 1::numeric) - round(0.5::numeric * 1::numeric)) AS total
)
SELECT is(total, 1::numeric, 'four quarter-unit tranches reconcile to one minor unit')
FROM rounding_case;
SELECT is(round(0.5::numeric), 1::numeric, 'numeric half rounds away from zero');
SELECT is(round(0.49::numeric), 0::numeric, 'values below half round down');
SELECT is(round(1.5::numeric), 2::numeric, 'cumulative whole-plus-half amount rounds once');
SELECT * FROM finish();
ROLLBACK;
