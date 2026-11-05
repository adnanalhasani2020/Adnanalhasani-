-- Backfill the required base sell unit for active offerings that have none.
-- The product's own unit code/name are authoritative; factor 1 preserves stock quantities.
WITH missing AS (
  SELECT o.id AS offering_id,
         coalesce(nullif(btrim(p.unit_code), ''), 'each') AS unit_code,
         coalesce(nullif(btrim(p.unit_name), ''), nullif(btrim(p.unit_code), ''), 'حبة') AS display_name
  FROM public.commercial_offerings o
  JOIN public.commercial_products p ON p.id = o.product_id
  WHERE o.state = 'active'
    AND NOT EXISTS (
      SELECT 1
      FROM public.commercial_offering_sell_units su
      WHERE su.offering_id = o.id
        AND su.is_active = true
        AND su.is_base = true
    )
)
INSERT INTO public.commercial_offering_sell_units
  (offering_id, unit_code, display_name, quantity_in_stock, is_base, is_active, created_at, updated_at)
SELECT offering_id, unit_code, display_name, 1, true, true, now(), now()
FROM missing
ON CONFLICT (offering_id, unit_code) DO UPDATE
SET display_name = EXCLUDED.display_name,
    quantity_in_stock = 1,
    is_base = true,
    is_active = true,
    updated_at = now();
