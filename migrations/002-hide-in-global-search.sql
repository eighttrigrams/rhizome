ALTER TABLE items
  ADD COLUMN IF NOT EXISTS hide_in_global_search BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE items
  SET hide_in_global_search = true
  WHERE (data->>'hide-in-global-search')::boolean IS TRUE;
