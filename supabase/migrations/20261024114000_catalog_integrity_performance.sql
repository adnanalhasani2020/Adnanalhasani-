-- Close the obsolete one-phase transfer route; the application uses send/accept/reject only.
DROP FUNCTION IF EXISTS public.commercial_catalog_transfer(uuid,uuid,uuid[]);

-- Add missing FK indexes identified by Supabase's performance advisor.
CREATE INDEX IF NOT EXISTS ix_catalog_recipes_created_by ON public.commercial_catalog_product_recipes(created_by);
CREATE INDEX IF NOT EXISTS ix_catalog_recipes_product ON public.commercial_catalog_product_recipes(product_id);
CREATE INDEX IF NOT EXISTS ix_catalog_recipes_reviewed_by ON public.commercial_catalog_product_recipes(reviewed_by);
CREATE INDEX IF NOT EXISTS ix_catalog_recipes_yield_unit ON public.commercial_catalog_product_recipes(yield_unit_code);

CREATE INDEX IF NOT EXISTS ix_catalog_rulings_created_by ON public.commercial_catalog_product_rulings(created_by);
CREATE INDEX IF NOT EXISTS ix_catalog_rulings_reviewed_by ON public.commercial_catalog_product_rulings(reviewed_by);

CREATE INDEX IF NOT EXISTS ix_catalog_product_units_created_by ON public.commercial_catalog_product_units(created_by);
CREATE INDEX IF NOT EXISTS ix_catalog_product_units_net_unit ON public.commercial_catalog_product_units(net_unit_code);
CREATE INDEX IF NOT EXISTS ix_catalog_product_units_unit ON public.commercial_catalog_product_units(unit_code);

CREATE INDEX IF NOT EXISTS ix_catalog_recipe_ingredients_product ON public.commercial_catalog_recipe_ingredients(ingredient_product_id);
CREATE INDEX IF NOT EXISTS ix_catalog_recipe_ingredients_recipe ON public.commercial_catalog_recipe_ingredients(recipe_id);
CREATE INDEX IF NOT EXISTS ix_catalog_recipe_ingredients_unit ON public.commercial_catalog_recipe_ingredients(unit_code);

CREATE INDEX IF NOT EXISTS ix_catalog_transfers_accepted_by ON public.commercial_catalog_transfers(accepted_by);
CREATE INDEX IF NOT EXISTS ix_catalog_transfers_destination ON public.commercial_catalog_transfers(destination_organization_id);
CREATE INDEX IF NOT EXISTS ix_catalog_transfers_source ON public.commercial_catalog_transfers(source_organization_id);
CREATE INDEX IF NOT EXISTS ix_catalog_transfers_transferred_by ON public.commercial_catalog_transfers(transferred_by);

CREATE INDEX IF NOT EXISTS ix_global_catalog_categories_created_by ON public.commercial_global_catalog_categories(created_by);
CREATE INDEX IF NOT EXISTS ix_global_catalog_categories_updated_by ON public.commercial_global_catalog_categories(updated_by);
CREATE INDEX IF NOT EXISTS ix_global_catalog_items_created_by ON public.commercial_global_catalog_items(created_by);
CREATE INDEX IF NOT EXISTS ix_global_catalog_items_updated_by ON public.commercial_global_catalog_items(updated_by);
CREATE INDEX IF NOT EXISTS ix_global_entity_translations_owner_org ON public.commercial_global_entity_translations(owner_organization_id);
CREATE INDEX IF NOT EXISTS ix_global_entity_translations_created_by ON public.commercial_global_entity_translations(created_by);
CREATE INDEX IF NOT EXISTS ix_global_units_created_by ON public.commercial_global_units(created_by);
CREATE INDEX IF NOT EXISTS ix_org_catalog_items_source_org ON public.commercial_organization_catalog_items(source_organization_id);

-- Product-region rules already have a null-aware natural uniqueness constraint; add a stable row identity as well.
ALTER TABLE public.commercial_catalog_product_regions
  ADD COLUMN IF NOT EXISTS id uuid DEFAULT gen_random_uuid();
UPDATE public.commercial_catalog_product_regions SET id=gen_random_uuid() WHERE id IS NULL;
ALTER TABLE public.commercial_catalog_product_regions ALTER COLUMN id SET NOT NULL;
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname='commercial_catalog_product_regions_pkey'
      AND conrelid='public.commercial_catalog_product_regions'::regclass
  ) THEN
    ALTER TABLE public.commercial_catalog_product_regions
      ADD CONSTRAINT commercial_catalog_product_regions_pkey PRIMARY KEY (id);
  END IF;
END $$;
