package handlers

import (
	"errors"
	"net/http"

	httpapi "summer-school-2026/backend/internal/http"
	"summer-school-2026/backend/internal/service/push"
)

// PushTokenRequest/PushTokenDeleteRequest mirror 01-analysis/api/auth/models.yaml
// (registerPushToken/deletePushToken). Hand-written rather than oapi-codegen'd: the
// auth domain's bundled spec also already contains the unrelated access/refresh token
// rework (R-016), and regenerating auth.gen.go would force that larger, separate change
// through at the same time. See 02-development/FEATURE_push-notifications.md.
type PushTokenRequest struct {
	Token    string `json:"token"`
	Platform string `json:"platform"`
}

type PushTokenDeleteRequest struct {
	Token    string `json:"token"`
	Platform string `json:"platform"`
}

type PushHandler struct {
	service *push.Service
}

func NewPushHandler(service *push.Service) *PushHandler {
	return &PushHandler{service: service}
}

func (h *PushHandler) RegisterPushToken(w http.ResponseWriter, r *http.Request) {
	token, ok := bearerOrUnauthorized(w, r)
	if !ok {
		return
	}
	var req PushTokenRequest
	if err := httpapi.DecodeJSON(r, &req); err != nil {
		httpapi.WriteError(w, http.StatusBadRequest, httpapi.CodeBadRequest, "Неверные параметры запроса. Проверьте корректность переданных значений.", nil)
		return
	}
	if err := h.service.Register(r.Context(), token, req.Token, req.Platform); err != nil {
		writePushError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *PushHandler) DeletePushToken(w http.ResponseWriter, r *http.Request) {
	token, ok := bearerOrUnauthorized(w, r)
	if !ok {
		return
	}
	var req PushTokenDeleteRequest
	if err := httpapi.DecodeJSON(r, &req); err != nil {
		httpapi.WriteError(w, http.StatusBadRequest, httpapi.CodeBadRequest, "Неверные параметры запроса. Проверьте корректность переданных значений.", nil)
		return
	}
	if err := h.service.Delete(r.Context(), token, req.Token, req.Platform); err != nil {
		writePushError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func writePushError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, push.ErrUnauthorized):
		httpapi.WriteError(w, http.StatusUnauthorized, httpapi.CodeUnauthorized, "Требуется авторизация. Передайте действительный токен в заголовке Authorization.", nil)
	case errors.Is(err, push.ErrInvalidToken), errors.Is(err, push.ErrInvalidPlatform):
		httpapi.WriteError(w, http.StatusBadRequest, httpapi.CodeBadRequest, "Неверные параметры запроса. Проверьте корректность переданных значений.", nil)
	default:
		httpapi.WriteError(w, http.StatusInternalServerError, httpapi.CodeInternalError, "Что-то пошло не так. Попробуйте ещё раз позже.", nil)
	}
}
