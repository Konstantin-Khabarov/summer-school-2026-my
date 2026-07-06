-- +goose Up
CREATE TABLE push_tokens (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id uuid NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    token text NOT NULL,
    platform text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT push_tokens_platform_chk CHECK (platform IN ('ios', 'android')),
    CONSTRAINT push_tokens_token_key UNIQUE (token)
);

CREATE INDEX push_tokens_client_id_idx ON push_tokens (client_id);

-- +goose Down
DROP TABLE push_tokens;
