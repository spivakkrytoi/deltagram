package main

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"golang.org/x/crypto/bcrypt"

	"telesrv/internal/admin"
)

type investorRow struct {
	UserID int64  `json:"user_id,string"`
	Login  string `json:"login"`
	Name   string `json:"name"`
	Active bool   `json:"active"`
}

func (s *server) handleInvestorsAPI(w http.ResponseWriter, r *http.Request) {
	if s.read == nil {
		writeAPIError(w, http.StatusServiceUnavailable, "Хранилище недоступно")
		return
	}
	rows, err := s.read.pool.Query(r.Context(), `
		SELECT m.user_id,m.login,u.first_name,m.active
		FROM investor_memberships m JOIN users u ON u.id=m.user_id
		ORDER BY m.created_at DESC LIMIT 200`)
	if err != nil {
		writeAPIError(w, http.StatusInternalServerError, "Не удалось загрузить список инвесторов")
		return
	}
	defer rows.Close()
	list := make([]investorRow, 0)
	for rows.Next() {
		var row investorRow
		if err := rows.Scan(&row.UserID, &row.Login, &row.Name, &row.Active); err != nil {
			writeAPIError(w, http.StatusInternalServerError, "Не удалось загрузить список инвесторов")
			return
		}
		list = append(list, row)
	}
	if rows.Err() != nil {
		writeAPIError(w, http.StatusInternalServerError, "Не удалось загрузить список инвесторов")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"rows": list})
}

type investorAccessRequest struct {
	CommandID string `json:"command_id"`
	Reason    string `json:"reason"`
	Confirm   bool   `json:"confirm"`
	UserID    int64  `json:"user_id,string"`
	Login     string `json:"login"`
	Password  string `json:"password"`
	Enabled   bool   `json:"enabled"`
}

func validInvestorLogin(login string) bool {
	if len(login) < 3 || len(login) > 48 {
		return false
	}
	for i, c := range login {
		if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (i > 0 && ((c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-')) {
			continue
		}
		return false
	}
	return true
}

// The admin console is the only writer of investor memberships. This action
// inherits its existing session, CSRF, admins.manage and audited command flow.
// A password is never returned to the browser or written to audit records.
func (s *server) handleInvestorAccessAPI(w http.ResponseWriter, r *http.Request) {
	if s.read == nil {
		writeAPIError(w, http.StatusServiceUnavailable, "Хранилище недоступно")
		return
	}
	var body investorAccessRequest
	if err := decodeJSON(r, &body); err != nil {
		writeAPIError(w, http.StatusBadRequest, err.Error())
		return
	}
	body.Login = strings.TrimSpace(body.Login)
	meta := s.commandMetaFromAPI(r, body.CommandID, body.Reason, body.Confirm, "investor-access")
	params := map[string]any{"user_id": body.UserID, "login": body.Login, "enabled": body.Enabled, "password_changed": body.Password != ""}
	fn := func(ctx context.Context, tx pgx.Tx) (admin.CommandResult, error) {
		if body.UserID <= 0 || !validInvestorLogin(body.Login) {
			return admin.CommandResult{}, errors.New("Нужен ID аккаунта DeltaGram и логин из 3–48 латинских символов")
		}
		var exists bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM users WHERE id=$1)`, body.UserID).Scan(&exists); err != nil || !exists {
			return admin.CommandResult{}, errors.New("Аккаунт DeltaGram не найден")
		}
		var prior bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM investor_memberships WHERE user_id=$1)`, body.UserID).Scan(&prior); err != nil {
			return admin.CommandResult{}, err
		}
		if !prior && !body.Enabled {
			return admin.CommandResult{}, errors.New("Нельзя отключить несуществующего инвестора")
		}
		if !prior && body.Password == "" {
			return admin.CommandResult{}, errors.New("Для нового инвестора нужен пароль")
		}
		if body.Password != "" && (len(body.Password) < 12 || len(body.Password) > 72) {
			return admin.CommandResult{}, errors.New("Пароль должен содержать от 12 до 72 байт")
		}
		var occupied bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM investor_memberships WHERE lower(login)=lower($1) AND user_id<>$2)`, body.Login, body.UserID).Scan(&occupied); err != nil {
			return admin.CommandResult{}, err
		}
		if occupied {
			return admin.CommandResult{}, errors.New("Логин уже занят")
		}
		if meta.DryRun {
			state := "включить"
			if !body.Enabled {
				state = "отключить"
			}
			return admin.CommandResult{Status: "ok", DryRun: true, Message: fmt.Sprintf("Проверка: %s доступ инвестору #%d (%s).", state, body.UserID, body.Login), Details: params}, nil
		}
		var hash string
		if body.Password != "" {
			generated, err := bcrypt.GenerateFromPassword([]byte(body.Password), 12)
			if err != nil {
				return admin.CommandResult{}, err
			}
			hash = string(generated)
		}
		_, err := tx.Exec(ctx, `INSERT INTO investor_memberships(user_id,login,password_hash,active) VALUES($1,$2,$3,$4)
			ON CONFLICT(user_id) DO UPDATE SET login=EXCLUDED.login,
			password_hash=CASE WHEN $5 THEN EXCLUDED.password_hash ELSE investor_memberships.password_hash END,
			active=EXCLUDED.active`, body.UserID, body.Login, hash, body.Enabled, body.Password != "")
		if err != nil {
			var pg *pgconn.PgError
			if errors.As(err, &pg) && pg.Code == "23505" {
				return admin.CommandResult{}, errors.New("Логин уже занят")
			}
			return admin.CommandResult{}, err
		}
		// Credential changes and disabling revoke all sessions immediately.
		if body.Password != "" || !body.Enabled {
			if _, err := tx.Exec(ctx, `DELETE FROM investor_sessions WHERE user_id=$1`, body.UserID); err != nil {
				return admin.CommandResult{}, err
			}
		}
		return admin.CommandResult{Status: "ok", Message: fmt.Sprintf("Доступ инвестора #%d (%s) обновлён.", body.UserID, body.Login), Details: params}, nil
	}
	result, err := s.runOperatorCommand(r.Context(), meta, "set-investor-access", params, body.Password, fn)
	writeCommandResultAPI(w, result, err)
}
