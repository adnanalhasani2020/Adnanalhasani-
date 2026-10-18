-- Preserve the product display name at the time a commercial sale is recorded.
-- Existing rows retain an empty snapshot because current product names cannot prove
-- what was displayed historically; new checkout writes a non-empty snapshot.
ALTER TABLE sale_lines
    ADD COLUMN product_name_snapshot TEXT NOT NULL DEFAULT '';
