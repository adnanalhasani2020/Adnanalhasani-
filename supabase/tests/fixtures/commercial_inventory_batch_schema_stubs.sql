-- Minimal structural stubs required to exercise the batch migrations on top
-- of the production-shaped, data-free commercial quantity fixture.
CREATE TABLE public.commercial_organizations (id uuid PRIMARY KEY);
CREATE TABLE public.commercial_organization_locations (id uuid PRIMARY KEY, organization_id uuid, state text);
CREATE TABLE public.commercial_offerings (id uuid PRIMARY KEY);
CREATE TABLE public.commercial_purchase_receipts (
  id uuid PRIMARY KEY, organization_id uuid, purchase_order_id uuid, location_id uuid,
  idempotency_key text, request_fingerprint text, received_by uuid, created_at timestamptz
);
CREATE TABLE public.commercial_purchase_orders (
  id uuid PRIMARY KEY, organization_id uuid, status text, receive_location_id uuid, updated_at timestamptz
);
CREATE TABLE public.commercial_organization_members (
  organization_id uuid, user_id uuid, state text, role text
);
CREATE TABLE public.commercial_audit_events (
  id uuid, organization_id uuid, actor_user_id uuid, action text, entity_type text,
  entity_id text, metadata jsonb, created_at timestamptz
);
CREATE TABLE public.commercial_inventory_costing_policies (
  id uuid, organization_id uuid, location_id uuid, costing_method text
);

CREATE TABLE public.commercial_activities (id uuid PRIMARY KEY, organization_id uuid, deleted_at timestamptz);
CREATE TABLE public.commercial_products (id uuid PRIMARY KEY, name text);
