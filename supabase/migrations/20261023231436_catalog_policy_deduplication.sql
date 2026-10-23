-- Remove overlapping permissive SELECT paths and split FOR ALL writes into least-privilege commands.
DROP POLICY IF EXISTS commercial_catalog_categories_org_select ON public.commercial_global_catalog_categories;
DROP POLICY IF EXISTS commercial_catalog_items_org_select ON public.commercial_global_catalog_items;

DROP POLICY IF EXISTS commercial_global_catalog_categories_authenticated_read ON public.commercial_global_catalog_categories;
CREATE POLICY commercial_global_catalog_categories_authenticated_read
ON public.commercial_global_catalog_categories FOR SELECT TO authenticated
USING (
  (owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin','manager','staff']::text[]))
  OR (is_active AND (
    owner_organization_id IS NULL
    OR EXISTS (
      SELECT 1 FROM public.commercial_global_catalog_items i
      JOIN public.commercial_organization_catalog_items oi ON oi.global_item_id=i.id
      WHERE i.category_code=code AND i.is_active
        AND public.commercial_catalog_member_of(oi.organization_id,ARRAY['owner','admin','manager','staff']::text[])
    )
  ))
);

DROP POLICY IF EXISTS commercial_global_catalog_authenticated_read ON public.commercial_global_catalog_items;
CREATE POLICY commercial_global_catalog_authenticated_read
ON public.commercial_global_catalog_items FOR SELECT TO authenticated
USING (
  (owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin','manager','staff']::text[]))
  OR (is_active AND (
    owner_organization_id IS NULL
    OR EXISTS (
      SELECT 1 FROM public.commercial_organization_catalog_items oi
      WHERE oi.global_item_id=id
        AND public.commercial_catalog_member_of(oi.organization_id,ARRAY['owner','admin','manager','staff']::text[])
    )
  ))
);

DROP POLICY IF EXISTS commercial_catalog_product_business_levels_write ON public.commercial_catalog_product_business_levels;
CREATE POLICY commercial_catalog_product_business_levels_insert ON public.commercial_catalog_product_business_levels FOR INSERT TO authenticated
WITH CHECK (EXISTS (
  SELECT 1 FROM public.commercial_global_catalog_items p
  WHERE p.id = commercial_catalog_product_business_levels.product_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
));
CREATE POLICY commercial_catalog_product_business_levels_update ON public.commercial_catalog_product_business_levels FOR UPDATE TO authenticated
USING (EXISTS (
  SELECT 1 FROM public.commercial_global_catalog_items p
  WHERE p.id = commercial_catalog_product_business_levels.product_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
))
WITH CHECK (EXISTS (
  SELECT 1 FROM public.commercial_global_catalog_items p
  WHERE p.id = commercial_catalog_product_business_levels.product_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
));
CREATE POLICY commercial_catalog_product_business_levels_delete ON public.commercial_catalog_product_business_levels FOR DELETE TO authenticated
USING (EXISTS (
  SELECT 1 FROM public.commercial_global_catalog_items p
  WHERE p.id = commercial_catalog_product_business_levels.product_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
));

DROP POLICY IF EXISTS commercial_catalog_product_recipes_write ON public.commercial_catalog_product_recipes;
CREATE POLICY commercial_catalog_product_recipes_insert ON public.commercial_catalog_product_recipes FOR INSERT TO authenticated
WITH CHECK (EXISTS (
  SELECT 1 FROM public.commercial_global_catalog_items p
  WHERE p.id = commercial_catalog_product_recipes.product_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
));
CREATE POLICY commercial_catalog_product_recipes_update ON public.commercial_catalog_product_recipes FOR UPDATE TO authenticated
USING (EXISTS (
  SELECT 1 FROM public.commercial_global_catalog_items p
  WHERE p.id = commercial_catalog_product_recipes.product_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
))
WITH CHECK (EXISTS (
  SELECT 1 FROM public.commercial_global_catalog_items p
  WHERE p.id = commercial_catalog_product_recipes.product_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
));
CREATE POLICY commercial_catalog_product_recipes_delete ON public.commercial_catalog_product_recipes FOR DELETE TO authenticated
USING (EXISTS (
  SELECT 1 FROM public.commercial_global_catalog_items p
  WHERE p.id = commercial_catalog_product_recipes.product_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
));

DROP POLICY IF EXISTS commercial_catalog_product_regions_write ON public.commercial_catalog_product_regions;
CREATE POLICY commercial_catalog_product_regions_insert ON public.commercial_catalog_product_regions FOR INSERT TO authenticated
WITH CHECK (EXISTS (
  SELECT 1 FROM public.commercial_global_catalog_items p
  WHERE p.id = commercial_catalog_product_regions.product_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
));
CREATE POLICY commercial_catalog_product_regions_update ON public.commercial_catalog_product_regions FOR UPDATE TO authenticated
USING (EXISTS (
  SELECT 1 FROM public.commercial_global_catalog_items p
  WHERE p.id = commercial_catalog_product_regions.product_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
))
WITH CHECK (EXISTS (
  SELECT 1 FROM public.commercial_global_catalog_items p
  WHERE p.id = commercial_catalog_product_regions.product_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
));
CREATE POLICY commercial_catalog_product_regions_delete ON public.commercial_catalog_product_regions FOR DELETE TO authenticated
USING (EXISTS (
  SELECT 1 FROM public.commercial_global_catalog_items p
  WHERE p.id = commercial_catalog_product_regions.product_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
));

DROP POLICY IF EXISTS commercial_catalog_product_rulings_write ON public.commercial_catalog_product_rulings;
CREATE POLICY commercial_catalog_product_rulings_insert ON public.commercial_catalog_product_rulings FOR INSERT TO authenticated
WITH CHECK (EXISTS (
  SELECT 1 FROM public.commercial_global_catalog_items p
  WHERE p.id = commercial_catalog_product_rulings.product_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
));
CREATE POLICY commercial_catalog_product_rulings_update ON public.commercial_catalog_product_rulings FOR UPDATE TO authenticated
USING (EXISTS (
  SELECT 1 FROM public.commercial_global_catalog_items p
  WHERE p.id = commercial_catalog_product_rulings.product_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
))
WITH CHECK (EXISTS (
  SELECT 1 FROM public.commercial_global_catalog_items p
  WHERE p.id = commercial_catalog_product_rulings.product_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
));
CREATE POLICY commercial_catalog_product_rulings_delete ON public.commercial_catalog_product_rulings FOR DELETE TO authenticated
USING (EXISTS (
  SELECT 1 FROM public.commercial_global_catalog_items p
  WHERE p.id = commercial_catalog_product_rulings.product_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
));

DROP POLICY IF EXISTS commercial_catalog_product_sectors_write ON public.commercial_catalog_product_sectors;
CREATE POLICY commercial_catalog_product_sectors_insert ON public.commercial_catalog_product_sectors FOR INSERT TO authenticated
WITH CHECK (EXISTS (
  SELECT 1 FROM public.commercial_global_catalog_items p
  WHERE p.id = commercial_catalog_product_sectors.product_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
));
CREATE POLICY commercial_catalog_product_sectors_update ON public.commercial_catalog_product_sectors FOR UPDATE TO authenticated
USING (EXISTS (
  SELECT 1 FROM public.commercial_global_catalog_items p
  WHERE p.id = commercial_catalog_product_sectors.product_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
))
WITH CHECK (EXISTS (
  SELECT 1 FROM public.commercial_global_catalog_items p
  WHERE p.id = commercial_catalog_product_sectors.product_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
));
CREATE POLICY commercial_catalog_product_sectors_delete ON public.commercial_catalog_product_sectors FOR DELETE TO authenticated
USING (EXISTS (
  SELECT 1 FROM public.commercial_global_catalog_items p
  WHERE p.id = commercial_catalog_product_sectors.product_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
));

DROP POLICY IF EXISTS commercial_catalog_product_units_write ON public.commercial_catalog_product_units;
CREATE POLICY commercial_catalog_product_units_insert ON public.commercial_catalog_product_units FOR INSERT TO authenticated
WITH CHECK (EXISTS (
  SELECT 1 FROM public.commercial_global_catalog_items p
  WHERE p.id = commercial_catalog_product_units.product_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
));
CREATE POLICY commercial_catalog_product_units_update ON public.commercial_catalog_product_units FOR UPDATE TO authenticated
USING (EXISTS (
  SELECT 1 FROM public.commercial_global_catalog_items p
  WHERE p.id = commercial_catalog_product_units.product_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
))
WITH CHECK (EXISTS (
  SELECT 1 FROM public.commercial_global_catalog_items p
  WHERE p.id = commercial_catalog_product_units.product_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
));
CREATE POLICY commercial_catalog_product_units_delete ON public.commercial_catalog_product_units FOR DELETE TO authenticated
USING (EXISTS (
  SELECT 1 FROM public.commercial_global_catalog_items p
  WHERE p.id = commercial_catalog_product_units.product_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
));

DROP POLICY IF EXISTS commercial_catalog_recipe_ingredients_write ON public.commercial_catalog_recipe_ingredients;
CREATE POLICY commercial_catalog_recipe_ingredients_insert ON public.commercial_catalog_recipe_ingredients
FOR INSERT TO authenticated WITH CHECK (EXISTS (
  SELECT 1 FROM public.commercial_catalog_product_recipes r
  JOIN public.commercial_global_catalog_items p ON p.id=r.product_id
  WHERE r.id=recipe_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
));
CREATE POLICY commercial_catalog_recipe_ingredients_update ON public.commercial_catalog_recipe_ingredients
FOR UPDATE TO authenticated USING (EXISTS (
  SELECT 1 FROM public.commercial_catalog_product_recipes r
  JOIN public.commercial_global_catalog_items p ON p.id=r.product_id
  WHERE r.id=recipe_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
))
WITH CHECK (EXISTS (
  SELECT 1 FROM public.commercial_catalog_product_recipes r
  JOIN public.commercial_global_catalog_items p ON p.id=r.product_id
  WHERE r.id=recipe_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
));
CREATE POLICY commercial_catalog_recipe_ingredients_delete ON public.commercial_catalog_recipe_ingredients
FOR DELETE TO authenticated USING (EXISTS (
  SELECT 1 FROM public.commercial_catalog_product_recipes r
  JOIN public.commercial_global_catalog_items p ON p.id=r.product_id
  WHERE r.id=recipe_id AND p.owner_organization_id IS NOT NULL
    AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
));
