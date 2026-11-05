-- Structural, data-free PostgreSQL fixture for the commercial integer quantity migration.
-- Column definitions are generated from production schema metadata; no customer or business rows are copied.
-- This intentionally omits unrelated constraints and policies. It validates migration DDL, row types,
-- whole-quantity constraints, non-finite rejection, and the sell-unit idempotency boundary.
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='anon') THEN CREATE ROLE anon NOLOGIN; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN CREATE ROLE authenticated NOLOGIN; END IF;
END $$;
CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS commercial_private;
GRANT USAGE ON SCHEMA commercial_private TO authenticated;
CREATE TABLE auth.users (id uuid PRIMARY KEY);
CREATE OR REPLACE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $auth_uid$
  SELECT nullif(current_setting('request.jwt.claim.sub',true),'')::uuid
$auth_uid$;

CREATE TABLE public.commercial_sales (
  id uuid PRIMARY KEY,
  receipt_number bigint,
  user_id uuid,
  activity_id uuid,
  idempotency_key text,
  request_fingerprint text,
  status text,
  currency_code text,
  total_minor bigint,
  created_at timestamptz,
  organization_id uuid,
  customer_id uuid,
  payment_status text,
  channel text,
  updated_at timestamptz,
  subtotal_minor bigint,
  discount_minor bigint,
  tax_minor bigint,
  delegated_access_id uuid,
  principal_account_id uuid,
  acting_account_id uuid
);

CREATE TABLE public.commercial_sale_items (
  id uuid,
  sale_id uuid,
  offering_id uuid,
  product_name_snapshot text,
  quantity integer,
  unit_price_minor bigint,
  line_total_minor bigint,
  created_at timestamptz,
  unit_cost_minor bigint,
  cost_total_minor bigint,
  selling_unit_code text,
  selling_unit_name text,
  selling_quantity numeric,
  quantity_in_stock_units bigint
);

CREATE TABLE public.commercial_stock_movements (
  id uuid,
  offering_id uuid,
  sale_id uuid,
  movement_type text,
  quantity_delta numeric,
  source_ref text,
  created_at timestamptz,
  created_by uuid
);

CREATE TABLE public.commercial_purchase_order_items (
  id uuid,
  organization_id uuid,
  purchase_order_id uuid,
  line_number integer,
  item_description text,
  quantity_ordered numeric,
  quantity_received numeric,
  unit_cost_minor bigint,
  created_at timestamptz,
  updated_at timestamptz,
  offering_id uuid,
  source_selling_unit_code text,
  source_selling_unit_name text,
  source_selling_quantity numeric,
  source_quantity_in_stock_units bigint
);

CREATE TABLE public.commercial_customer_orders (
  id uuid,
  order_number bigint,
  organization_id uuid,
  buyer_account_id uuid,
  created_by uuid,
  order_type text,
  status text,
  payment_status text,
  currency_code text,
  subtotal_minor bigint,
  discount_minor bigint,
  tax_minor bigint,
  total_minor bigint,
  reserved_from timestamptz,
  reserved_until timestamptz,
  customer_note text,
  idempotency_key text,
  created_at timestamptz,
  updated_at timestamptz,
  customer_cancelled_at timestamptz,
  customer_cancellation_reason text,
  merchant_hidden_at timestamptz,
  merchant_hidden_by uuid,
  merchant_reviewed_at timestamptz,
  merchant_reviewed_by uuid,
  merchant_decision_reason text,
  delegated_access_id uuid,
  principal_account_id uuid,
  acting_account_id uuid
);

CREATE TABLE public.commercial_customer_order_items (
  id uuid,
  order_id uuid,
  offering_id uuid,
  item_type text,
  item_name_snapshot text,
  quantity numeric,
  unit_price_minor bigint,
  line_total_minor bigint,
  created_at timestamptz
);

CREATE TABLE public.commercial_booking_resources (
  id uuid,
  organization_id uuid,
  location_id uuid,
  resource_type text,
  name text,
  description text,
  capacity integer,
  timezone text,
  status text,
  metadata jsonb,
  created_at timestamptz,
  updated_at timestamptz
);

CREATE TABLE public.commercial_bookings (
  id uuid,
  booking_number bigint,
  organization_id uuid,
  resource_id uuid,
  customer_account_id uuid,
  order_id uuid,
  status text,
  starts_at timestamptz,
  ends_at timestamptz,
  currency_code text,
  price_minor bigint,
  paid_minor bigint,
  created_by uuid,
  cancellation_reason text,
  notes text,
  idempotency_key text,
  created_at timestamptz,
  updated_at timestamptz,
  merchant_hidden_at timestamptz,
  merchant_hidden_by uuid,
  merchant_reviewed_at timestamptz,
  merchant_reviewed_by uuid,
  merchant_decision_reason text,
  delegated_access_id uuid,
  principal_account_id uuid,
  acting_account_id uuid
);

CREATE TABLE public.commercial_inventory_balances (
  id uuid,
  organization_id uuid,
  location_id uuid,
  offering_id uuid,
  quantity_on_hand numeric,
  quantity_reserved numeric,
  reorder_point numeric,
  updated_at timestamptz,
  unit_cost_minor bigint
);

CREATE TABLE public.commercial_global_units (
  code text,
  name_ar text,
  name_en text,
  dimension text,
  base_unit_code text,
  factor_to_base numeric,
  decimal_places smallint,
  is_active boolean,
  version integer,
  created_at timestamptz,
  updated_at timestamptz,
  owner_organization_id uuid,
  created_by uuid
);

CREATE TABLE public.commercial_global_catalog_items (
  id uuid,
  catalog_code text,
  name_ar text,
  name_en text,
  description_ar text,
  description_en text,
  category_code text,
  item_type text,
  unit_code text,
  barcode_gtin text,
  attributes_schema jsonb,
  is_active boolean,
  version integer,
  created_at timestamptz,
  updated_at timestamptz,
  owner_organization_id uuid,
  brand text,
  manufacturer_name text,
  country_of_manufacture text,
  model_number text,
  part_number text,
  material_composition jsonb,
  ingredients jsonb,
  recipe jsonb,
  specifications jsonb,
  allergens jsonb,
  storage_instructions text,
  usage_instructions text,
  warnings text,
  shelf_life_days integer,
  requires_expiry_date boolean,
  is_batch_tracked boolean,
  is_serial_tracked boolean,
  is_prescription_only boolean,
  image_url text,
  image_alt text,
  created_by uuid,
  updated_by uuid
);

CREATE TABLE public.commercial_sale_return_items (
  id uuid,
  return_id uuid,
  sale_item_id uuid,
  quantity integer,
  refund_amount_minor bigint,
  restock boolean,
  restocked_quantity integer,
  condition_code text,
  note text,
  created_at timestamptz,
  updated_at timestamptz
);

CREATE TABLE public.commercial_catalog_product_units (
  id uuid,
  product_id uuid,
  unit_code text,
  unit_role text,
  quantity_in_base numeric,
  barcode text,
  sku text,
  package_name text,
  net_quantity numeric,
  net_unit_code text,
  image_url text,
  is_default boolean,
  is_active boolean,
  created_by uuid,
  created_at timestamptz,
  updated_at timestamptz
);

CREATE TABLE public.commercial_catalog_product_recipes (
  id uuid,
  product_id uuid,
  locale_code text,
  recipe_name text,
  instructions text,
  yield_quantity numeric,
  yield_unit_code text,
  preparation_minutes integer,
  cooking_minutes integer,
  allergen_notes text,
  source_reference text,
  reviewed_by uuid,
  reviewed_at timestamptz,
  created_by uuid,
  created_at timestamptz
);

CREATE TABLE public.commercial_catalog_recipe_ingredients (
  id uuid,
  recipe_id uuid,
  ingredient_product_id uuid,
  ingredient_name text,
  quantity numeric,
  unit_code text,
  preparation_note text
);

CREATE TABLE public.commercial_inventory_receipts (
  id uuid,
  organization_id uuid,
  location_id uuid,
  offering_id uuid,
  source_ref text,
  request_fingerprint text,
  quantity numeric,
  unit_cost_minor bigint,
  created_by uuid,
  created_at timestamptz
);

CREATE TABLE public.commercial_return_restock_postings (
  return_item_id uuid,
  return_id uuid,
  organization_id uuid,
  location_id uuid,
  offering_id uuid,
  quantity bigint,
  unit_cost_minor bigint,
  source_ref text,
  created_by uuid,
  created_at timestamptz
);

CREATE TABLE public.commercial_offering_sell_units (
  id uuid,
  offering_id uuid,
  unit_code text,
  display_name text,
  quantity_in_stock bigint,
  is_base boolean,
  is_active boolean,
  created_at timestamptz,
  updated_at timestamptz
);

CREATE TABLE public.commercial_supplier_invoice_items (
  id uuid,
  organization_id uuid,
  supplier_invoice_id uuid,
  purchase_order_item_id uuid,
  quantity numeric,
  unit_price_minor bigint,
  line_total_minor bigint,
  created_at timestamptz
);

CREATE TABLE public.commercial_purchase_receipt_items (
  id uuid,
  organization_id uuid,
  receipt_id uuid,
  purchase_order_id uuid,
  purchase_order_item_id uuid,
  offering_id uuid,
  quantity numeric,
  unit_cost_minor bigint,
  inventory_receipt_id uuid,
  created_at timestamptz
);

CREATE TABLE public.commercial_account_delegations (
  id uuid,
  parent_account_id uuid,
  child_account_id uuid,
  organization_id uuid,
  activity_id uuid,
  permissions jsonb,
  expires_at timestamptz,
  spend_limit_minor bigint,
  spend_currency_code text,
  spent_minor bigint,
  status text,
  merchant_display_name text,
  merchant_icon_key text,
  requested_by uuid,
  reviewed_by uuid,
  reviewed_at timestamptz,
  revoked_by uuid,
  revoked_at timestamptz,
  created_at timestamptz,
  updated_at timestamptz
);

CREATE TABLE public.commercial_organization_catalog_item_setup (
  organization_id uuid,
  global_item_id uuid,
  activity_node_id uuid,
  branch_location_id uuid,
  warehouse_id uuid,
  base_unit_code text,
  sale_unit_code text,
  sale_quantity_in_base numeric,
  created_by uuid,
  updated_at timestamptz
);

CREATE TABLE public.commercial_invoice_purchase_request_items (
  id uuid,
  request_id uuid,
  source_sale_item_id uuid,
  global_item_id uuid,
  product_name_snapshot text,
  quantity numeric,
  unit_price_minor bigint,
  line_total_minor bigint,
  line_number integer,
  selling_unit_code text,
  selling_unit_name text,
  selling_quantity numeric,
  quantity_in_stock_units bigint
);

CREATE TABLE public.commercial_catalog_inventory_creation_requests (
  organization_id uuid,
  idempotency_key text,
  request_fingerprint text,
  global_item_id uuid,
  location_id uuid,
  offering_id uuid,
  response jsonb,
  created_by uuid,
  created_at timestamptz
);

CREATE TABLE public.commercial_inventory_cost_layers (
  id uuid,
  organization_id uuid,
  location_id uuid,
  offering_id uuid,
  inventory_receipt_id uuid,
  source_type text,
  source_ref text,
  received_at timestamptz,
  quantity_received numeric,
  quantity_remaining numeric,
  source_unit_cost_minor bigint,
  unit_cost_minor bigint,
  created_at timestamptz
);

CREATE TABLE public.commercial_inventory_cost_allocations (
  id uuid,
  organization_id uuid,
  location_id uuid,
  offering_id uuid,
  sale_id uuid,
  sale_item_id uuid,
  cost_layer_id uuid,
  costing_method text,
  quantity numeric,
  unit_cost_minor bigint,
  total_cost_minor numeric,
  created_at timestamptz
);

CREATE TABLE public.commercial_inventory_return_cost_allocations (
  id uuid,
  organization_id uuid,
  location_id uuid,
  offering_id uuid,
  return_id uuid,
  return_item_id uuid,
  sale_id uuid,
  sale_item_id uuid,
  sale_cost_allocation_id uuid,
  cost_layer_id uuid,
  quantity numeric,
  unit_cost_minor bigint,
  total_cost_minor numeric,
  created_at timestamptz
);

CREATE TABLE public.commercial_inventory_cost_reversals (
  id uuid,
  organization_id uuid,
  location_id uuid,
  offering_id uuid,
  sale_id uuid,
  sale_item_id uuid,
  sale_cost_allocation_id uuid,
  cost_layer_id uuid,
  reversal_type text,
  quantity numeric,
  unit_cost_minor bigint,
  total_cost_minor numeric,
  created_at timestamptz
);

-- Existing RPC signatures renamed by this migration.
CREATE FUNCTION public.commercial_checkout_sale_multi_base_units(jsonb,uuid,text,uuid,text,bigint,bigint,bigint)
RETURNS jsonb LANGUAGE sql AS $$ SELECT '{}'::jsonb $$;
CREATE FUNCTION public.commercial_request_sale_return(uuid,text,text,jsonb,text)
RETURNS uuid LANGUAGE sql AS $$ SELECT NULL::uuid $$;
