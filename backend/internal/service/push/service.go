package push

import (
	"context"
	"errors"

	"summer-school-2026/backend/internal/service/auth"
)

var (
	ErrUnauthorized    = errors.New("unauthorized")
	ErrInvalidToken    = errors.New("invalid push token")
	ErrInvalidPlatform = errors.New("invalid platform")
)

type Client = auth.Client

// LOGIC-007: reminder timing is server-owned config, never hardcoded on the client.
var ReminderHours = []int{24, 2}

type Repository interface {
	ClientBySessionTokenHash(ctx context.Context, tokenHash string) (Client, bool, error)
	Register(ctx context.Context, clientID, token, platform string) error
	Delete(ctx context.Context, clientID, token string) error
}

type Service struct {
	repo Repository
}

func NewService(repo Repository) *Service {
	return &Service{repo: repo}
}

func (s *Service) Register(ctx context.Context, sessionToken, pushToken, platform string) error {
	if pushToken == "" {
		return ErrInvalidToken
	}
	if platform != "ios" && platform != "android" {
		return ErrInvalidPlatform
	}
	client, err := s.currentClient(ctx, sessionToken)
	if err != nil {
		return err
	}
	return s.repo.Register(ctx, client.ID, pushToken, platform)
}

func (s *Service) Delete(ctx context.Context, sessionToken, pushToken, platform string) error {
	if pushToken == "" {
		return ErrInvalidToken
	}
	if platform != "ios" && platform != "android" {
		return ErrInvalidPlatform
	}
	client, err := s.currentClient(ctx, sessionToken)
	if err != nil {
		return err
	}
	return s.repo.Delete(ctx, client.ID, pushToken)
}

func (s *Service) currentClient(ctx context.Context, token string) (Client, error) {
	if token == "" {
		return Client{}, ErrUnauthorized
	}
	client, ok, err := s.repo.ClientBySessionTokenHash(ctx, auth.HashToken(token))
	if err != nil {
		return Client{}, err
	}
	if !ok {
		return Client{}, ErrUnauthorized
	}
	return client, nil
}
