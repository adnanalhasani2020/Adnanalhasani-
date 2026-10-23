-- Additional locale labels for the existing canonical unit directory.
-- The unit master remains commercial_global_units; this is a translation extension, not a duplicate unit catalog.
CREATE TABLE IF NOT EXISTS public.commercial_catalog_unit_translations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  unit_code text NOT NULL REFERENCES public.commercial_global_units(code) ON DELETE CASCADE,
  owner_organization_id uuid REFERENCES public.commercial_organizations(id) ON DELETE CASCADE,
  locale_code text NOT NULL CHECK (length(btrim(locale_code)) BETWEEN 2 AND 35),
  name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 120),
  symbol text,
  description text,
  created_by uuid REFERENCES auth.users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE NULLS NOT DISTINCT (unit_code, owner_organization_id, locale_code)
);

ALTER TABLE public.commercial_catalog_unit_translations ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.commercial_catalog_unit_translations FROM anon;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.commercial_catalog_unit_translations TO authenticated;

CREATE POLICY catalog_unit_translations_read
ON public.commercial_catalog_unit_translations
FOR SELECT TO authenticated
USING (
  owner_organization_id IS NULL
  OR public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin','manager','staff']::text[])
);

CREATE POLICY catalog_unit_translations_write
ON public.commercial_catalog_unit_translations
FOR ALL TO authenticated
USING (
  owner_organization_id IS NOT NULL
  AND public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin']::text[])
)
WITH CHECK (
  owner_organization_id IS NOT NULL
  AND public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin']::text[])
);

COMMENT ON TABLE public.commercial_catalog_unit_translations IS
'Locale-specific labels for the existing canonical commercial_global_units directory; organization-scoped overrides are isolated by RLS.';
