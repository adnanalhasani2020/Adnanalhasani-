-- Run after all migrations in a disposable Supabase database.
BEGIN;
SELECT plan(4);

SELECT ok(
  position('v_qty<>trunc(v_qty)' in pg_get_functiondef(
    'commercial_private.commercial_receive_purchase_order(uuid,uuid,uuid,jsonb,text)'::regprocedure
  )) > 0,
  'purchase receipt RPC rejects fractional quantities'
);

SELECT ok(
  position('p_quantity<>trunc(p_quantity)' in pg_get_functiondef(
    'public.commercial_receive_inventory_stock(uuid,uuid,uuid,numeric,bigint,text)'::regprocedure
  )) > 0,
  'numeric inventory receipt RPC rejects fractional quantities'
);

SELECT ok(
  has_function_privilege(
    'authenticated',
    'public.commercial_receive_purchase_order(uuid,uuid,uuid,jsonb,text)',
    'EXECUTE'
  ),
  'authenticated members retain the purchase receipt RPC'
);

SELECT ok(
  NOT has_function_privilege(
    'anon',
    'public.commercial_receive_purchase_order(uuid,uuid,uuid,jsonb,text)',
    'EXECUTE'
  ),
  'anonymous callers cannot execute the purchase receipt RPC'
);

SELECT * FROM finish();
ROLLBACK;
