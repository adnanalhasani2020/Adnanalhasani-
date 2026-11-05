BEGIN;
SELECT plan(5);

SELECT ok(
  to_regprocedure('public.commercial_allocate_inventory_to_storage_location(uuid,uuid,uuid,uuid,uuid,numeric,text)') IS NOT NULL,
  'storage allocation RPC exists'
);

SELECT ok(
  position(
    'quantity_reserved' IN pg_get_functiondef(
      'public.commercial_allocate_inventory_to_storage_location(uuid,uuid,uuid,uuid,uuid,numeric,text)'::regprocedure
    )
  ) > 0,
  'allocation RPC reads reserved quantity'
);

SELECT ok(
  position(
    'v_allocated + p_quantity > v_balance.quantity_on_hand - coalesce(v_balance.quantity_reserved, 0)' IN
    pg_get_functiondef(
      'public.commercial_allocate_inventory_to_storage_location(uuid,uuid,uuid,uuid,uuid,numeric,text)'::regprocedure
    )
  ) > 0,
  'allocation ceiling excludes reserved stock'
);

SELECT ok(
  has_function_privilege(
    'authenticated',
    'public.commercial_allocate_inventory_to_storage_location(uuid,uuid,uuid,uuid,uuid,numeric,text)',
    'EXECUTE'
  ),
  'authenticated members can call the guarded allocation RPC'
);

SELECT ok(
  NOT has_function_privilege(
    'anon',
    'public.commercial_allocate_inventory_to_storage_location(uuid,uuid,uuid,uuid,uuid,numeric,text)',
    'EXECUTE'
  ),
  'anonymous callers cannot call the guarded allocation RPC'
);

SELECT * FROM finish();
ROLLBACK;
