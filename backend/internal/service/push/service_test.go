package push

import (
	"context"
	"errors"
	"testing"

	"summer-school-2026/backend/internal/service/auth"
)

func TestRegisterRejectsEmptyToken(t *testing.T) {
	repo := &fakeRepo{clientFound: true}
	svc := NewService(repo)

	err := svc.Register(context.Background(), "session-token", "", "ios")
	if !errors.Is(err, ErrInvalidToken) {
		t.Fatalf("Register() error = %v, want %v", err, ErrInvalidToken)
	}
	if repo.clientLookups != 0 {
		t.Fatalf("clientLookups = %d, want 0 (should fail before auth check)", repo.clientLookups)
	}
}

func TestRegisterRejectsInvalidPlatform(t *testing.T) {
	tests := []string{"web", ""}
	for _, platform := range tests {
		t.Run(platform, func(t *testing.T) {
			repo := &fakeRepo{clientFound: true}
			svc := NewService(repo)

			err := svc.Register(context.Background(), "session-token", "push-token", platform)
			if !errors.Is(err, ErrInvalidPlatform) {
				t.Fatalf("Register() error = %v, want %v", err, ErrInvalidPlatform)
			}
		})
	}
}

func TestRegisterRejectsEmptySessionToken(t *testing.T) {
	repo := &fakeRepo{clientFound: true}
	svc := NewService(repo)

	err := svc.Register(context.Background(), "", "push-token", "ios")
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("Register() error = %v, want %v", err, ErrUnauthorized)
	}
	if repo.clientLookups != 0 {
		t.Fatalf("clientLookups = %d, want 0 (empty token should short-circuit)", repo.clientLookups)
	}
}

func TestRegisterRejectsUnknownClient(t *testing.T) {
	repo := &fakeRepo{clientFound: false}
	svc := NewService(repo)

	err := svc.Register(context.Background(), "session-token", "push-token", "android")
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("Register() error = %v, want %v", err, ErrUnauthorized)
	}
}

func TestRegisterHappyPathDelegatesToRepository(t *testing.T) {
	repo := &fakeRepo{clientFound: true, client: auth.Client{ID: "client-id"}}
	svc := NewService(repo)

	if err := svc.Register(context.Background(), "session-token", "push-token", "android"); err != nil {
		t.Fatalf("Register() error = %v, want nil", err)
	}
	if repo.registerCalls != 1 {
		t.Fatalf("registerCalls = %d, want 1", repo.registerCalls)
	}
	if repo.registerClientID != "client-id" {
		t.Fatalf("registerClientID = %q, want %q", repo.registerClientID, "client-id")
	}
}

func TestDeleteRejectsEmptyToken(t *testing.T) {
	repo := &fakeRepo{clientFound: true}
	svc := NewService(repo)

	err := svc.Delete(context.Background(), "session-token", "", "ios")
	if !errors.Is(err, ErrInvalidToken) {
		t.Fatalf("Delete() error = %v, want %v", err, ErrInvalidToken)
	}
}

func TestDeleteRejectsInvalidPlatform(t *testing.T) {
	repo := &fakeRepo{clientFound: true}
	svc := NewService(repo)

	err := svc.Delete(context.Background(), "session-token", "push-token", "web")
	if !errors.Is(err, ErrInvalidPlatform) {
		t.Fatalf("Delete() error = %v, want %v", err, ErrInvalidPlatform)
	}
}

func TestDeleteRejectsUnauthorized(t *testing.T) {
	repo := &fakeRepo{clientFound: false}
	svc := NewService(repo)

	err := svc.Delete(context.Background(), "session-token", "push-token", "ios")
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("Delete() error = %v, want %v", err, ErrUnauthorized)
	}
}

func TestDeleteHappyPathDelegatesToRepository(t *testing.T) {
	repo := &fakeRepo{clientFound: true, client: auth.Client{ID: "client-id"}}
	svc := NewService(repo)

	if err := svc.Delete(context.Background(), "session-token", "push-token", "ios"); err != nil {
		t.Fatalf("Delete() error = %v, want nil", err)
	}
	if repo.deleteCalls != 1 {
		t.Fatalf("deleteCalls = %d, want 1", repo.deleteCalls)
	}
	if repo.deleteClientID != "client-id" {
		t.Fatalf("deleteClientID = %q, want %q", repo.deleteClientID, "client-id")
	}
}

type fakeRepo struct {
	client        auth.Client
	clientFound   bool
	clientLookups int

	registerCalls    int
	registerClientID string

	deleteCalls    int
	deleteClientID string
}

func (r *fakeRepo) ClientBySessionTokenHash(context.Context, string) (auth.Client, bool, error) {
	r.clientLookups++
	return r.client, r.clientFound, nil
}

func (r *fakeRepo) Register(_ context.Context, clientID, _ string, _ string) error {
	r.registerCalls++
	r.registerClientID = clientID
	return nil
}

func (r *fakeRepo) Delete(_ context.Context, clientID, _ string) error {
	r.deleteCalls++
	r.deleteClientID = clientID
	return nil
}
