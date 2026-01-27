-- Lists table. Each row represents a collection of items.
CREATE TABLE lists (
    id          SERIAL PRIMARY KEY,          -- Auto-incrementing unique identifier
    name        TEXT NOT NULL,                -- Display name of the list
    description TEXT,                         -- Optional longer description
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- List items table. Each row represents a single to-do/checklist entry.
CREATE TABLE items (
    id          SERIAL PRIMARY KEY,          -- Auto-incrementing unique identifier
    list_id     INTEGER NOT NULL REFERENCES lists(id) ON DELETE CASCADE, -- Parent list
    title       TEXT NOT NULL,                -- Short summary of the item
    description TEXT,                         -- Optional longer description
    done        BOOLEAN NOT NULL DEFAULT false, -- Completion status
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
