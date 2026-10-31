BEGIN;

-- Read-only, row-level-security-aware audit feed. One row represents one
-- valuation event; it does not change balances, layers, allocations, or GL.
CREATE OR REPLACE VIEW public.commercial_inventory_cost_audit_report
WITH (security_invoker = true)
AS
SELECT
  l.id AS event_id,
  l.source_type AS event_type,
  l.received_at AS event_at,
  l.organization_id,
  l.location_id,
  l.offering_id,
  NULL::uuid AS sale_id,
  NULL::uuid AS sale_item_id,
  NULL::uuid AS return_id,
  NULL::uuid AS return_item_id,
  l.id AS cost_layer_id,
  l.quantity_received AS quantity,
  l.source_unit_cost_minor AS unit_cost_minor,
  round(l.quantity_received * l.source_unit_cost_minor::numeric)::numeric(30,0) AS total_cost_minor,
  NULL::text AS costing_method,
  l.source_type AS source_type,
  l.source_ref AS source_ref
FROM public.commercial_inventory_cost_layers l

UNION ALL

SELECT
  a.id AS event_id,
  'sale_allocation'::text AS event_type,
  a.created_at AS event_at,
  a.organization_id,
  a.location_id,
  a.offering_id,
  a.sale_id,
  a.sale_item_id,
  NULL::uuid AS return_id,
  NULL::uuid AS return_item_id,
  a.cost_layer_id,
  a.quantity,
  a.unit_cost_minor,
  a.total_cost_minor,
  a.costing_method,
  'sale'::text AS source_type,
  a.sale_id::text AS source_ref
FROM public.commercial_inventory_cost_allocations a

UNION ALL

SELECT
  rca.id AS event_id,
  'return_restoration'::text AS event_type,
  rca.created_at AS event_at,
  rca.organization_id,
  rca.location_id,
  rca.offering_id,
  rca.sale_id,
  rca.sale_item_id,
  rca.return_id,
  rca.return_item_id,
  rca.cost_layer_id,
  rca.quantity,
  rca.unit_cost_minor,
  rca.total_cost_minor,
  NULL::text AS costing_method,
  'return'::text AS source_type,
  rca.return_id::text AS source_ref
FROM public.commercial_inventory_return_cost_allocations rca

UNION ALL

SELECT
  cr.id AS event_id,
  'sale_cancellation_reversal'::text AS event_type,
  cr.created_at AS event_at,
  cr.organization_id,
  cr.location_id,
  cr.offering_id,
  cr.sale_id,
  cr.sale_item_id,
  NULL::uuid AS return_id,
  NULL::uuid AS return_item_id,
  cr.cost_layer_id,
  cr.quantity,
  cr.unit_cost_minor,
  cr.total_cost_minor,
  NULL::text AS costing_method,
  cr.reversal_type AS source_type,
  cr.sale_id::text AS source_ref
FROM public.commercial_inventory_cost_reversals cr;

COMMENT ON VIEW public.commercial_inventory_cost_audit_report IS
  'Read-only organization-scoped inventory costing event feed for audit and export. Base-table RLS is enforced by security_invoker. Amounts are in currency minor units; this view is not a GL journal.';

REVOKE ALL ON public.commercial_inventory_cost_audit_report FROM PUBLIC, anon;
GRANT SELECT ON public.commercial_inventory_cost_audit_report TO authenticated;

COMMIT;
