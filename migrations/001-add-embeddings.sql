CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE items
  ADD COLUMN IF NOT EXISTS embedding vector(768);

CREATE INDEX IF NOT EXISTS items_embedding_hnsw_idx
  ON items USING hnsw (embedding vector_cosine_ops);
