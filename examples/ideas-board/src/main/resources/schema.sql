-- tag::schema[]
CREATE TABLE IF NOT EXISTS ideas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    text STRING NOT NULL,
    votes INT NOT NULL DEFAULT 0
);
-- end::schema[]
