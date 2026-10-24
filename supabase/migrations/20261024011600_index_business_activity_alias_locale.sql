-- Cover the locale foreign key used when filtering/searching aliases by language.
CREATE INDEX IF NOT EXISTS ix_business_activity_aliases_locale
  ON public.commercial_global_business_activity_aliases(locale_code);
