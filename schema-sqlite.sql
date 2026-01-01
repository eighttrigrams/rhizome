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
    annotation TEXT
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
    version INTEGER NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, version),
    FOREIGN KEY (id) REFERENCES items(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_items_title ON items(title);
CREATE INDEX IF NOT EXISTS idx_items_is_context ON items(is_context);
CREATE INDEX IF NOT EXISTS idx_relations_owner_id ON relations(owner_id);
CREATE INDEX IF NOT EXISTS idx_relations_target_id ON relations(target_id);
