-- Persist the domain-required Product name without inventing values for legacy rows.
ALTER TABLE products ADD COLUMN name TEXT NULL;
