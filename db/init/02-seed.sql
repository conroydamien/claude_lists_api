-- Seed data for development and testing.

INSERT INTO lists (name, description) VALUES
    ('Groceries', 'Weekly shopping list'),
    ('Project Tasks', 'Things to do for the project'),
    ('Books to Read', 'Reading list for 2025');

INSERT INTO items (list_id, title, description, done) VALUES
    -- Groceries
    (1, 'Milk', '2 litres, semi-skimmed', false),
    (1, 'Bread', 'Sourdough loaf', true),
    (1, 'Eggs', 'Free range, dozen', false),
    -- Project Tasks
    (2, 'Set up CI pipeline', 'GitHub Actions or similar', false),
    (2, 'Write API docs', 'Document PostgREST endpoints', false),
    (2, 'Add JWT auth', 'Secure the API with token-based auth', false),
    -- Books to Read
    (3, 'Designing Data-Intensive Applications', 'Martin Kleppmann', true),
    (3, 'The Pragmatic Programmer', 'Hunt & Thomas', false);
