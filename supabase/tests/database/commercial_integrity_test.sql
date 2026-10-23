-- Run with Supabase CLI / pgTAP in a disposable local or staging database.
-- These are schema regression tests; they do not replace authenticated-session integration tests.
BEGIN;
SELECT plan(7);

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

SELECT * FROM finish();
ROLLBACK;
