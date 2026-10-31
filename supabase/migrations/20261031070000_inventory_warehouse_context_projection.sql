BEGIN;

-- Read-only bridge between the existing location-level inventory ledger and
-- catalog warehouse setup. It deliberately does not allocate or move stock.
CREATE OR REPLACE VIEW public.commercial_inventory_warehouse_context
WITH (security_invoker = true)
AS
WITH warehouse_mapping AS (
  SELECT
    b.id AS inventory_balance_id,
    b.organization_id,
    b.location_id,
    b.offering_id,
    count(DISTINCT s.warehouse_id)::integer AS mapped_warehouse_count,
    CASE
      WHEN count(DISTINCT s.warehouse_id) = 1
        THEN min(s.warehouse_id::text)::uuid
      ELSE NULL
    END AS warehouse_id
  FROM public.commercial_inventory_balances b
  LEFT JOIN public.commercial_catalog_item_offerings cio
    ON cio.organization_id = b.organization_id
   AND cio.location_id = b.location_id
   AND cio.offering_id = b.offering_id
  LEFT JOIN public.commercial_organization_catalog_item_setup s
    ON s.organization_id = cio.organization_id
   AND s.global_item_id = cio.global_item_id
   AND s.branch_location_id = b.location_id
   AND s.warehouse_id IS NOT NULL
  GROUP BY b.id, b.organization_id, b.location_id, b.offering_id
)
SELECT
  b.id AS inventory_balance_id,
  b.organization_id,
  b.location_id,
  b.offering_id,
  m.warehouse_id,
  CASE
    WHEN m.mapped_warehouse_count = 0 THEN 'unconfigured'
    WHEN m.mapped_warehouse_count = 1 THEN 'mapped'
    ELSE 'ambiguous'
  END AS warehouse_mapping_status,
  m.mapped_warehouse_count,
  b.quantity_on_hand,
  b.quantity_reserved,
  greatest(b.quantity_on_hand - b.quantity_reserved, 0) AS available_quantity,
  b.reorder_point,
  w.code AS warehouse_code,
  w.name AS warehouse_name,
  w.state AS warehouse_state,
  b.updated_at
FROM public.commercial_inventory_balances b
JOIN warehouse_mapping m ON m.inventory_balance_id = b.id
LEFT JOIN public.commercial_warehouses w
  ON w.id = m.warehouse_id
 AND w.organization_id = b.organization_id
 AND w.branch_location_id = b.location_id;

COMMENT ON VIEW public.commercial_inventory_warehouse_context IS
  'Read-only warehouse context for location-level inventory. A warehouse is exposed only when exactly one catalog setup maps the offering to it; ambiguous and unconfigured balances remain unassigned. This view does not change stock posting or ledger source of truth.';

REVOKE ALL ON public.commercial_inventory_warehouse_context FROM PUBLIC, anon;
GRANT SELECT ON public.commercial_inventory_warehouse_context TO authenticated;

COMMIT;
