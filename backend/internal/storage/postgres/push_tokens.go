package postgres

import (
	"context"
	"errors"
	"fmt"

	"summer-school-2026/backend/internal/service/push"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type PushTokenRepository struct {
	db *pgxpool.Pool
}

func NewPushTokenRepository(db *pgxpool.Pool) *PushTokenRepository {
	return &PushTokenRepository{db: db}
}

func (r *PushTokenRepository) ClientBySessionTokenHash(ctx context.Context, tokenHash string) (push.Client, bool, error) {
	var client push.Client
	err := r.db.QueryRow(ctx, `
SELECT c.id::text, c.name, c.phone, c.created_at
FROM auth_sessions s
JOIN clients c ON c.id = s.client_id
WHERE s.token_hash = $1
  AND s.revoked_at IS NULL
  AND s.expires_at > now()
  AND c.deleted_at IS NULL`, tokenHash).Scan(&client.ID, &client.Name, &client.Phone, &client.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return push.Client{}, false, nil
	}
	if err != nil {
		return push.Client{}, false, fmt.Errorf("query client by session: %w", err)
	}
	return client, true, nil
}

// Register is idempotent: re-registering the same token updates its owner/platform (R-006).
func (r *PushTokenRepository) Register(ctx context.Context, clientID, token, platform string) error {
	_, err := r.db.Exec(ctx, `
INSERT INTO push_tokens (client_id, token, platform)
VALUES ($1, $2, $3)
ON CONFLICT (token) DO UPDATE SET client_id = EXCLUDED.client_id, platform = EXCLUDED.platform`,
		clientID, token, platform)
	if err != nil {
		return fmt.Errorf("register push token: %w", err)
	}
	return nil
}

// Delete is idempotent: removing an already-absent token is not an error.
func (r *PushTokenRepository) Delete(ctx context.Context, clientID, token string) error {
	_, err := r.db.Exec(ctx, `DELETE FROM push_tokens WHERE client_id = $1 AND token = $2`, clientID, token)
	if err != nil {
		return fmt.Errorf("delete push token: %w", err)
	}
	return nil
}
