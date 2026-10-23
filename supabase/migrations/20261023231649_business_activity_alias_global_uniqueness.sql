-- An activity alias must resolve to one node even when a caller does not supply a locale.
-- Current data was checked for cross-locale collisions before creating this index.
DROP INDEX IF EXISTS public.ux_business_activity_alias_locale_normalized;
CREATE UNIQUE INDEX IF NOT EXISTS ux_business_activity_alias_normalized_active
  ON public.commercial_global_business_activity_aliases(normalized_alias)
  WHERE is_active;
