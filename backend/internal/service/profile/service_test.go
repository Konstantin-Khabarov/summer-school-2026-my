package profile

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"summer-school-2026/backend/internal/service/auth"
)

func TestCurrentRejectsEmptyToken(t *testing.T) {
	svc := NewService(&fakeRepo{}, nil)

	_, err := svc.Current(context.Background(), "")
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("Current() error = %v, want %v", err, ErrUnauthorized)
	}
}

func TestCurrentRejectsUnknownClient(t *testing.T) {
	repo := &fakeRepo{clientFound: false}
	svc := NewService(repo, nil)

	_, err := svc.Current(context.Background(), "token")
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("Current() error = %v, want %v", err, ErrUnauthorized)
	}
}

func TestUpdateNameRejectsInvalidLength(t *testing.T) {
	tests := []struct {
		name  string
		input string
	}{
		{"blank after trim", "   "},
		{"too long", strings.Repeat("a", 101)},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			repo := &fakeRepo{clientFound: true}
			svc := NewService(repo, nil)

			_, err := svc.UpdateName(context.Background(), "token", tt.input)
			if !errors.Is(err, ErrInvalidName) {
				t.Fatalf("UpdateName() error = %v, want %v", err, ErrInvalidName)
			}
			if repo.updateNameCalls != 0 {
				t.Fatalf("updateNameCalls = %d, want 0 (should fail before repo call)", repo.updateNameCalls)
			}
		})
	}
}

func TestUpdateNameTrimsWhitespaceAndDelegates(t *testing.T) {
	repo := &fakeRepo{clientFound: true}
	svc := NewService(repo, nil)

	_, err := svc.UpdateName(context.Background(), "token", "  Мария  ")
	if err != nil {
		t.Fatalf("UpdateName() error = %v, want nil", err)
	}
	if repo.updateNameCalls != 1 {
		t.Fatalf("updateNameCalls = %d, want 1", repo.updateNameCalls)
	}
	if repo.updateNameArg != "Мария" {
		t.Fatalf("updateNameArg = %q, want %q", repo.updateNameArg, "Мария")
	}
}

func TestRequestPhoneChangeCodeRejectsInvalidPhone(t *testing.T) {
	repo := &fakeRepo{clientFound: true}
	svc := NewService(repo, nil)

	_, err := svc.RequestPhoneChangeCode(context.Background(), "token", "not-a-phone")
	if !errors.Is(err, ErrInvalidPhone) {
		t.Fatalf("RequestPhoneChangeCode() error = %v, want %v", err, ErrInvalidPhone)
	}
	if repo.clientLookups != 0 {
		t.Fatalf("clientLookups = %d, want 0 (should fail before auth check)", repo.clientLookups)
	}
}

func TestRequestPhoneChangeCodeRejectsSamePhone(t *testing.T) {
	repo := &fakeRepo{clientFound: true, client: Client{ID: "client-id", Phone: "+79990000000"}}
	svc := NewService(repo, nil)

	_, err := svc.RequestPhoneChangeCode(context.Background(), "token", "+79990000000")
	if !errors.Is(err, ErrPhoneConflict) {
		t.Fatalf("RequestPhoneChangeCode() error = %v, want %v", err, ErrPhoneConflict)
	}
}

func TestRequestPhoneChangeCodeRejectsExistingOwner(t *testing.T) {
	repo := &fakeRepo{
		clientFound:      true,
		client:           Client{ID: "client-id", Phone: "+79990000000"},
		findByPhoneFound: true,
	}
	svc := NewService(repo, nil)

	_, err := svc.RequestPhoneChangeCode(context.Background(), "token", "+79991112233")
	if !errors.Is(err, ErrPhoneConflict) {
		t.Fatalf("RequestPhoneChangeCode() error = %v, want %v", err, ErrPhoneConflict)
	}
}

func TestRequestPhoneChangeCodeResendWindowBoundary(t *testing.T) {
	now := time.Date(2026, 6, 22, 12, 0, 0, 0, time.UTC)
	tests := []struct {
		name      string
		createdAt time.Time
		wantErr   error
	}{
		{"just under resend window", now.Add(-59 * time.Second), ErrTooManyRequests},
		{"exactly resend window", now.Add(-1 * time.Minute), nil},
		{"just over resend window", now.Add(-61 * time.Second), nil},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			repo := &fakeRepo{
				clientFound:    true,
				client:         Client{ID: "client-id", Phone: "+79990000000"},
				latestOTPFound: true,
				latestOTP:      auth.OTP{ID: "otp-1", CreatedAt: tt.createdAt},
			}
			svc := NewService(repo, nil)
			svc.now = func() time.Time { return now }

			_, err := svc.RequestPhoneChangeCode(context.Background(), "token", "+79991112233")
			if tt.wantErr == nil {
				if err != nil {
					t.Fatalf("RequestPhoneChangeCode() error = %v, want nil", err)
				}
				if repo.createOTPCalls != 1 {
					t.Fatalf("createOTPCalls = %d, want 1", repo.createOTPCalls)
				}
				return
			}
			if !errors.Is(err, tt.wantErr) {
				t.Fatalf("RequestPhoneChangeCode() error = %v, want %v", err, tt.wantErr)
			}
		})
	}
}

func TestRequestPhoneChangeCodeHappyPath(t *testing.T) {
	repo := &fakeRepo{clientFound: true, client: Client{ID: "client-id", Phone: "+79990000000"}}
	svc := NewService(repo, nil)

	result, err := svc.RequestPhoneChangeCode(context.Background(), "token", "+79991112233")
	if err != nil {
		t.Fatalf("RequestPhoneChangeCode() error = %v, want nil", err)
	}
	if repo.createOTPCalls != 1 {
		t.Fatalf("createOTPCalls = %d, want 1", repo.createOTPCalls)
	}
	if result.TTLSeconds != 300 {
		t.Fatalf("TTLSeconds = %d, want 300", result.TTLSeconds)
	}
	if result.ResendAfterSeconds != 60 {
		t.Fatalf("ResendAfterSeconds = %d, want 60", result.ResendAfterSeconds)
	}
}

func TestConfirmPhoneChangeRejectsMalformedInput(t *testing.T) {
	repo := &fakeRepo{clientFound: true}
	svc := NewService(repo, nil)

	_, err := svc.ConfirmPhoneChange(context.Background(), "token", "not-a-phone", "1234")
	if !errors.Is(err, ErrInvalidCode) {
		t.Fatalf("ConfirmPhoneChange() error = %v, want %v", err, ErrInvalidCode)
	}
}

func TestConfirmPhoneChangeRejectsPhoneConflict(t *testing.T) {
	repo := &fakeRepo{clientFound: true, client: Client{ID: "client-id", Phone: "+79990000000"}}
	svc := NewService(repo, nil)

	_, err := svc.ConfirmPhoneChange(context.Background(), "token", "+79990000000", "1234")
	if !errors.Is(err, ErrPhoneConflict) {
		t.Fatalf("ConfirmPhoneChange() error = %v, want %v", err, ErrPhoneConflict)
	}
}

func TestConfirmPhoneChangeRejectsInvalidOtpState(t *testing.T) {
	now := time.Date(2026, 6, 22, 12, 0, 0, 0, time.UTC)
	consumedAt := now.Add(-time.Minute)
	tests := []struct {
		name     string
		otpFound bool
		otp      auth.OTP
	}{
		{"otp not found", false, auth.OTP{}},
		{"otp already consumed", true, auth.OTP{ExpiresAt: now.Add(time.Minute), ConsumedAt: &consumedAt}},
		{"otp expired", true, auth.OTP{ExpiresAt: now.Add(-time.Second)}},
		{"otp attempts exhausted", true, auth.OTP{ExpiresAt: now.Add(time.Minute), AttemptCount: 5}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			repo := &fakeRepo{
				clientFound:    true,
				client:         Client{ID: "client-id", Phone: "+79990000000"},
				latestOTPFound: tt.otpFound,
				latestOTP:      tt.otp,
			}
			svc := NewService(repo, nil)
			svc.now = func() time.Time { return now }

			_, err := svc.ConfirmPhoneChange(context.Background(), "token", "+79991112233", "1234")
			if !errors.Is(err, ErrInvalidCode) {
				t.Fatalf("ConfirmPhoneChange() error = %v, want %v", err, ErrInvalidCode)
			}
		})
	}
}

func TestConfirmPhoneChangeRejectsCodeMismatchAndIncrementsAttempts(t *testing.T) {
	now := time.Date(2026, 6, 22, 12, 0, 0, 0, time.UTC)
	newPhone := "+79991112233"
	repo := &fakeRepo{
		clientFound:    true,
		client:         Client{ID: "client-id", Phone: "+79990000000"},
		latestOTPFound: true,
		latestOTP: auth.OTP{
			ID:        "otp-1",
			ExpiresAt: now.Add(time.Minute),
			CodeHash:  auth.HashOTP(newPhone, phoneChangePurpose, "0000"),
		},
	}
	svc := NewService(repo, nil)
	svc.now = func() time.Time { return now }

	_, err := svc.ConfirmPhoneChange(context.Background(), "token", newPhone, "9999")
	if !errors.Is(err, ErrInvalidCode) {
		t.Fatalf("ConfirmPhoneChange() error = %v, want %v", err, ErrInvalidCode)
	}
	if repo.incrementAttemptsCalls != 1 {
		t.Fatalf("incrementAttemptsCalls = %d, want 1", repo.incrementAttemptsCalls)
	}
}

func TestConfirmPhoneChangeHappyPathDelegatesToRepository(t *testing.T) {
	now := time.Date(2026, 6, 22, 12, 0, 0, 0, time.UTC)
	code := "1234"
	newPhone := "+79991112233"
	repo := &fakeRepo{
		clientFound:    true,
		client:         Client{ID: "client-id", Phone: "+79990000000"},
		latestOTPFound: true,
		latestOTP: auth.OTP{
			ID:        "otp-1",
			ExpiresAt: now.Add(time.Minute),
			CodeHash:  auth.HashOTP(newPhone, phoneChangePurpose, code),
		},
		changePhoneResult: Client{ID: "client-id", Phone: newPhone},
	}
	svc := NewService(repo, nil)
	svc.now = func() time.Time { return now }

	client, err := svc.ConfirmPhoneChange(context.Background(), "token", newPhone, code)
	if err != nil {
		t.Fatalf("ConfirmPhoneChange() error = %v, want nil", err)
	}
	if repo.changePhoneCalls != 1 {
		t.Fatalf("changePhoneCalls = %d, want 1", repo.changePhoneCalls)
	}
	if client.Phone != newPhone {
		t.Fatalf("client.Phone = %q, want %q", client.Phone, newPhone)
	}
}

func TestDeleteAccountPropagatesUnauthorized(t *testing.T) {
	svc := NewService(&fakeRepo{clientFound: false}, nil)

	err := svc.DeleteAccount(context.Background(), "token")
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("DeleteAccount() error = %v, want %v", err, ErrUnauthorized)
	}
}

func TestDeleteAccountDelegatesToRepository(t *testing.T) {
	repo := &fakeRepo{clientFound: true, client: Client{ID: "client-id"}}
	svc := NewService(repo, nil)

	if err := svc.DeleteAccount(context.Background(), "token"); err != nil {
		t.Fatalf("DeleteAccount() error = %v, want nil", err)
	}
	if repo.deleteAccountCalls != 1 {
		t.Fatalf("deleteAccountCalls = %d, want 1", repo.deleteAccountCalls)
	}
}

type fakeRepo struct {
	client        Client
	clientFound   bool
	clientLookups int

	updateNameCalls int
	updateNameArg   string

	findByPhoneFound bool

	latestOTP      auth.OTP
	latestOTPFound bool

	createOTPCalls int

	incrementAttemptsCalls int

	changePhoneCalls  int
	changePhoneResult Client

	deleteAccountCalls int
}

func (r *fakeRepo) ClientBySessionTokenHash(context.Context, string) (Client, bool, error) {
	r.clientLookups++
	return r.client, r.clientFound, nil
}

func (r *fakeRepo) UpdateClientName(_ context.Context, _ string, name string) (Client, error) {
	r.updateNameCalls++
	r.updateNameArg = name
	return r.client, nil
}

func (r *fakeRepo) FindClientByPhone(context.Context, string) (Client, bool, error) {
	return Client{}, r.findByPhoneFound, nil
}

func (r *fakeRepo) LatestOTP(context.Context, string, string) (auth.OTP, bool, error) {
	return r.latestOTP, r.latestOTPFound, nil
}

func (r *fakeRepo) CreateOTP(context.Context, string, string, string, time.Time) error {
	r.createOTPCalls++
	return nil
}

func (r *fakeRepo) ConsumeOTP(context.Context, string, time.Time) error {
	return nil
}

func (r *fakeRepo) IncrementOTPAttempts(context.Context, string) error {
	r.incrementAttemptsCalls++
	return nil
}

func (r *fakeRepo) ChangeClientPhone(context.Context, string, string, string, time.Time) (Client, error) {
	r.changePhoneCalls++
	return r.changePhoneResult, nil
}

func (r *fakeRepo) DeleteClientAccount(context.Context, string, time.Time) error {
	r.deleteAccountCalls++
	return nil
}
