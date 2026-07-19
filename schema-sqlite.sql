CREATE TABLE IF NOT EXISTS items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT,
    short_title TEXT,
    description TEXT,
    inserted_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at_ctx TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    tags TEXT DEFAULT '',
    data TEXT,
    is_context INTEGER NOT NULL DEFAULT 0,
    date TEXT,
    sort_idx INTEGER NOT NULL DEFAULT -1,
    annotation TEXT,
    hide_in_global_search INTEGER NOT NULL DEFAULT 0,
    human_readable_id TEXT,
    embedding TEXT,
    description_source TEXT
);

CREATE TABLE IF NOT EXISTS relations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_id INTEGER NOT NULL,
    target_id INTEGER NOT NULL,
    show_badge INTEGER DEFAULT 1,
    annotation TEXT,
    FOREIGN KEY (owner_id) REFERENCES items(id),
    FOREIGN KEY (target_id) REFERENCES items(id)
);

CREATE TABLE IF NOT EXISTS history (
    id INTEGER NOT NULL,
    text TEXT,
    title TEXT,
    version INTEGER NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    source TEXT,
    PRIMARY KEY (id, version),
    FOREIGN KEY (id) REFERENCES items(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_items_title ON items(title);
CREATE INDEX IF NOT EXISTS idx_items_is_context ON items(is_context);
CREATE UNIQUE INDEX IF NOT EXISTS idx_items_human_readable_id
  ON items(human_readable_id) WHERE human_readable_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_relations_owner_id ON relations(owner_id);
CREATE INDEX IF NOT EXISTS idx_relations_target_id ON relations(target_id);

CREATE VIRTUAL TABLE IF NOT EXISTS items_fts USING fts5(
    title, short_title, tags,
    content='items', content_rowid='id',
    tokenize='unicode61 remove_diacritics 2'
);

CREATE TRIGGER IF NOT EXISTS items_ai AFTER INSERT ON items BEGIN
    INSERT INTO items_fts(rowid, title, short_title, tags)
    VALUES (new.id, new.title, new.short_title, new.tags);
END;

CREATE TRIGGER IF NOT EXISTS items_ad AFTER DELETE ON items BEGIN
    INSERT INTO items_fts(items_fts, rowid, title, short_title, tags)
    VALUES('delete', old.id, old.title, old.short_title, old.tags);
END;

CREATE TRIGGER IF NOT EXISTS items_au AFTER UPDATE ON items BEGIN
    INSERT INTO items_fts(items_fts, rowid, title, short_title, tags)
    VALUES('delete', old.id, old.title, old.short_title, old.tags);
    INSERT INTO items_fts(rowid, title, short_title, tags)
    VALUES (new.id, new.title, new.short_title, new.tags);
END;

CREATE VIRTUAL TABLE IF NOT EXISTS items_vec USING vec0(
    item_id INTEGER PRIMARY KEY,
    embedding FLOAT[1024]
);

CREATE TABLE IF NOT EXISTS items_vec_skipped (
    item_id INTEGER PRIMARY KEY,
    FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS youtube_poll_channels (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    channel_id TEXT NOT NULL UNIQUE,
    name TEXT,
    min_duration_minutes INTEGER,
    added_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS youtube_poll_seen (
    video_id TEXT PRIMARY KEY,
    seen_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS atom_poll_feeds (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    feed_url TEXT NOT NULL UNIQUE,
    name TEXT,
    added_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS atom_poll_seen (
    entry_id TEXT PRIMARY KEY,
    seen_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
