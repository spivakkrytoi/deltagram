package main

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"telesrv/internal/admin"
	"telesrv/internal/domain"
)

const investorDailyStars int64 = 150000
const investorUsernameLimit int64 = 7

var investorNamePattern = regexp.MustCompile(`^[a-z][a-z0-9_]{3,31}$`)
var investorKeyPattern = regexp.MustCompile(`^[a-zA-Z0-9-]{16,64}$`)

type investorBridgeRequest struct {
	UserID   int64  `json:"user_id"`
	Action   string `json:"action"`
	Key      string `json:"key"`
	Amount   int64  `json:"amount"`
	Username string `json:"username"`
	Verified bool   `json:"verified"`
}

func investorCommandID(kind string, userID int64, key string) string {
	sum := sha256.Sum256([]byte(fmt.Sprintf("%d:%s:%s", userID, kind, key)))
	return "investor-" + kind + "-" + hex.EncodeToString(sum[:16])
}

func (s *server) handleInvestorBridge(w http.ResponseWriter, r *http.Request) {
	// This is a server-to-server route, not another operator or browser session.
	// The independent investor process holds only this credential; every action
	// is scoped again to an active investor's own account below.
	secret := s.cfg.InvestorBridgeKey
	got := r.Header.Get("X-Investor-Bridge-Key")
	if len(secret) < 32 || subtle.ConstantTimeCompare([]byte(secret), []byte(got)) != 1 {
		writeAPIError(w, http.StatusUnauthorized, "Unauthorized")
		return
	}
	if s.read == nil {
		writeAPIError(w, 503, "Хранилище недоступно")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 2048)
	var req investorBridgeRequest
	if err := decodeJSON(r, &req); err != nil {
		writeAPIError(w, 400, "Некорректный запрос")
		return
	}
	if req.UserID <= 0 || domain.IsSystemUserID(req.UserID) {
		writeAPIError(w, 403, "Недоступный аккаунт")
		return
	}
	var active bool
	if err := s.read.pool.QueryRow(r.Context(), `SELECT active FROM investor_memberships WHERE user_id=$1`, req.UserID).Scan(&active); err != nil || !active {
		writeAPIError(w, 403, "Доступ инвестора неактивен")
		return
	}
	switch req.Action {
	case "status":
		s.investorStatus(w, r, req.UserID)
	case "availability":
		s.investorAvailability(w, r, req.Username)
	case "stars":
		s.investorStars(w, r, req)
	case "mint":
		s.investorMint(w, r, req)
	case "verification":
		s.investorVerification(w, r, req)
	default:
		writeAPIError(w, 400, "Действие не поддерживается")
	}
}

func (s *server) investorStatus(w http.ResponseWriter, r *http.Request, id int64) {
	var verified bool
	if err := s.read.pool.QueryRow(r.Context(), `SELECT verified FROM users WHERE id=$1`, id).Scan(&verified); err != nil {
		writeAPIError(w, 500, "Профиль недоступен")
		return
	}
	var spent int64
	if err := s.read.pool.QueryRow(r.Context(), `SELECT coalesce(sum(amount),0)::bigint FROM investor_star_grants
		WHERE user_id=$1 AND utc_day=(now() at time zone 'UTC')::date AND status<>'failed'`, id).Scan(&spent); err != nil {
		writeAPIError(w, 500, "Лимит недоступен")
		return
	}
	var owned, pending int64
	if err := s.read.pool.QueryRow(r.Context(), `SELECT count(*) FROM collectible_usernames WHERE owner_peer_type='user' AND owner_peer_id=$1 AND status='owned'`, id).Scan(&owned); err != nil {
		writeAPIError(w, 500, "Имена недоступны")
		return
	}
	if err := s.read.pool.QueryRow(r.Context(), `SELECT count(*) FROM investor_username_mints WHERE user_id=$1 AND status='pending'`, id).Scan(&pending); err != nil {
		writeAPIError(w, 500, "Имена недоступны")
		return
	}
	rows, err := s.read.pool.Query(r.Context(), `SELECT username FROM collectible_usernames WHERE owner_peer_type='user' AND owner_peer_id=$1 AND status='owned' ORDER BY username LIMIT 50`, id)
	if err != nil {
		writeAPIError(w, 500, "Имена недоступны")
		return
	}
	names := make([]string, 0)
	for rows.Next() {
		var name string
		if rows.Scan(&name) != nil {
			rows.Close()
			writeAPIError(w, 500, "Имена недоступны")
			return
		}
		names = append(names, name)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		writeAPIError(w, 500, "Имена недоступны")
		return
	}
	next := time.Now().UTC().Truncate(24 * time.Hour).Add(24 * time.Hour)
	writeJSON(w, 200, map[string]any{"verified": verified, "daily_limit": investorDailyStars, "spent_today": spent,
		"remaining_today": max(int64(0), investorDailyStars-spent), "next_reset": next.Format(time.RFC3339),
		"username_limit": investorUsernameLimit, "owned_names": names, "pending_names": pending, "username_remaining": max(int64(0), investorUsernameLimit-owned-pending)})
}

func (s *server) investorAvailability(w http.ResponseWriter, r *http.Request, raw string) {
	name := strings.ToLower(strings.TrimPrefix(strings.TrimSpace(raw), "@"))
	if !investorNamePattern.MatchString(name) {
		writeAPIError(w, 400, "Имя: от 4 до 32 латинских символов, первая — буква")
		return
	}
	var taken bool
	err := s.read.pool.QueryRow(r.Context(), `SELECT EXISTS(
		SELECT 1 FROM peer_usernames WHERE username_lower=$1
		UNION ALL SELECT 1 FROM collectible_usernames WHERE username_lower=$1
		UNION ALL SELECT 1 FROM investor_username_mints WHERE username_lower=$1 AND status='pending'
	)`, name).Scan(&taken)
	if err != nil {
		writeAPIError(w, 500, "Проверка недоступна")
		return
	}
	writeJSON(w, 200, map[string]any{"username": name, "available": !taken})
}

func (s *server) investorStars(w http.ResponseWriter, r *http.Request, req investorBridgeRequest) {
	if !investorKeyPattern.MatchString(req.Key) || req.Amount < 1 || req.Amount > investorDailyStars {
		writeAPIError(w, 400, "Введите сумму от 1 до 150 000 и уникальный ключ операции")
		return
	}
	ctx := r.Context()
	tx, err := s.read.pool.Begin(ctx)
	if err != nil {
		writeAPIError(w, 503, "База временно недоступна")
		return
	}
	defer tx.Rollback(ctx)
	var active bool
	err = tx.QueryRow(ctx, `SELECT active FROM investor_memberships WHERE user_id=$1 FOR UPDATE`, req.UserID).Scan(&active)
	if err != nil || !active {
		writeAPIError(w, 403, "Доступ инвестора неактивен")
		return
	}
	day := time.Now().UTC().Format("2006-01-02")
	var existingAmount int64
	var existingDay, status string
	err = tx.QueryRow(ctx, `SELECT amount,utc_day::text,status FROM investor_star_grants WHERE user_id=$1 AND request_key=$2`, req.UserID, req.Key).Scan(&existingAmount, &existingDay, &status)
	switch {
	case err == nil:
		if existingAmount != req.Amount || existingDay != day {
			writeAPIError(w, 409, "Ключ операции уже использован")
			return
		}
		if status == "completed" {
			writeJSON(w, 200, map[string]any{"ok": true, "replayed": true})
			return
		}
		if status == "failed" {
			writeAPIError(w, 409, "Операция отклонена, используйте новый запрос")
			return
		}
	case !errors.Is(err, pgx.ErrNoRows):
		writeAPIError(w, 503, "База временно недоступна")
		return
	default:
		var spent int64
		if err := tx.QueryRow(ctx, `SELECT coalesce(sum(amount),0)::bigint FROM investor_star_grants WHERE user_id=$1 AND utc_day=$2 AND status<>'failed'`, req.UserID, day).Scan(&spent); err != nil {
			writeAPIError(w, 503, "База временно недоступна")
			return
		}
		if spent+req.Amount > investorDailyStars {
			writeAPIError(w, 409, "Дневной лимит 150 000 звёзд исчерпан")
			return
		}
		if _, err := tx.Exec(ctx, `INSERT INTO investor_star_grants(user_id,request_key,utc_day,amount) VALUES($1,$2,$3,$4)`, req.UserID, req.Key, day, req.Amount); err != nil {
			writeAPIError(w, 503, "База временно недоступна")
			return
		}
	}
	if err := tx.Commit(ctx); err != nil {
		writeAPIError(w, 503, "База временно недоступна")
		return
	}
	command := admin.GrantStarsRequest{CommandMeta: admin.CommandMeta{CommandID: investorCommandID("stars", req.UserID, req.Key), Actor: fmt.Sprintf("investor:%d", req.UserID), Reason: "Investor daily Stars self-grant"}, UserID: req.UserID, Amount: req.Amount}
	result, err := s.callAdminAPI(ctx, "/v1/accounts/grant-stars", command)
	if err != nil || result.Status != "completed" {
		writeAPIError(w, 502, "Начисление ожидает проверки. Квота зарезервирована; повторите запрос с тем же ключом.")
		return
	}
	if _, err := s.read.pool.Exec(ctx, `UPDATE investor_star_grants SET status='completed' WHERE user_id=$1 AND request_key=$2`, req.UserID, req.Key); err != nil {
		writeAPIError(w, 502, "Начисление выполнено, но обновление статуса задержано. Повторите запрос с тем же ключом.")
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "amount": req.Amount})
}

func (s *server) investorMint(w http.ResponseWriter, r *http.Request, req investorBridgeRequest) {
	name := strings.ToLower(strings.TrimPrefix(strings.TrimSpace(req.Username), "@"))
	if !investorNamePattern.MatchString(name) || !investorKeyPattern.MatchString(req.Key) {
		writeAPIError(w, 400, "Свободный юзернейм должен содержать 4–32 латинских символа")
		return
	}
	ctx := r.Context()
	tx, err := s.read.pool.Begin(ctx)
	if err != nil {
		writeAPIError(w, 503, "База временно недоступна")
		return
	}
	defer tx.Rollback(ctx)
	var active bool
	err = tx.QueryRow(ctx, `SELECT active FROM investor_memberships WHERE user_id=$1 FOR UPDATE`, req.UserID).Scan(&active)
	if err != nil || !active {
		writeAPIError(w, 403, "Доступ инвестора неактивен")
		return
	}
	var oldName, status string
	var createdAt time.Time
	err = tx.QueryRow(ctx, `SELECT username_lower,status,created_at FROM investor_username_mints WHERE user_id=$1 AND request_key=$2`, req.UserID, req.Key).Scan(&oldName, &status, &createdAt)
	switch {
	case err == nil:
		if oldName != name {
			writeAPIError(w, 409, "Ключ операции уже использован")
			return
		}
		if status == "completed" {
			writeJSON(w, 200, map[string]any{"ok": true, "replayed": true, "username": name})
			return
		}
		if status == "failed" {
			writeAPIError(w, 409, "Операция отклонена, используйте новый запрос")
			return
		}
	case !errors.Is(err, pgx.ErrNoRows):
		writeAPIError(w, 503, "База временно недоступна")
		return
	default:
		var owned, pending int64
		err = tx.QueryRow(ctx, `SELECT count(*) FROM collectible_usernames WHERE owner_peer_type='user' AND owner_peer_id=$1 AND status='owned'`, req.UserID).Scan(&owned)
		if err != nil {
			writeAPIError(w, 503, "Проверка лимита недоступна")
			return
		}
		err = tx.QueryRow(ctx, `SELECT count(*) FROM investor_username_mints WHERE user_id=$1 AND status='pending'`, req.UserID).Scan(&pending)
		if err != nil {
			writeAPIError(w, 503, "Проверка лимита недоступна")
			return
		}
		if owned+pending >= investorUsernameLimit {
			writeAPIError(w, 409, "Лимит: не более 7 NFT-имён на аккаунте")
			return
		}
		var taken bool
		err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM peer_usernames WHERE username_lower=$1 UNION ALL SELECT 1 FROM collectible_usernames WHERE username_lower=$1)`, name).Scan(&taken)
		if err != nil {
			writeAPIError(w, 503, "Проверка имени недоступна")
			return
		}
		if taken {
			writeAPIError(w, 409, "Юзернейм уже занят")
			return
		}
		err = tx.QueryRow(ctx, `INSERT INTO investor_username_mints(user_id,request_key,username_lower) VALUES($1,$2,$3) RETURNING created_at`, req.UserID, req.Key, name).Scan(&createdAt)
		if err != nil {
			var pg *pgconn.PgError
			if errors.As(err, &pg) && pg.Code == "23505" {
				writeAPIError(w, 409, "Юзернейм уже занят")
				return
			}
			writeAPIError(w, 503, "База временно недоступна")
			return
		}
	}
	if err := tx.Commit(ctx); err != nil {
		writeAPIError(w, 503, "База временно недоступна")
		return
	}
	command := admin.MintCollectibleUsernameRequest{CommandMeta: admin.CommandMeta{CommandID: investorCommandID("mint", req.UserID, req.Key), Actor: fmt.Sprintf("investor:%d", req.UserID), Reason: "Investor self-service NFT username"}, Username: name, OwnerUserID: req.UserID, Currency: "TON", Amount: 0, PurchaseDate: createdAt.Unix()}
	result, err := s.callAdminAPI(ctx, "/v1/collectible-usernames/mint", command)
	if err != nil || result.Status != "completed" {
		// An occupied name is definitely rejected by the authoritative registry.
		// Ambiguous network failures keep the slot reserved, failing closed.
		if result.Code == admin.CodeUsernameOccupied && result.Status == "failed" {
			_, _ = s.read.pool.Exec(ctx, `UPDATE investor_username_mints SET status='failed' WHERE user_id=$1 AND request_key=$2`, req.UserID, req.Key)
			writeAPIError(w, 409, "Юзернейм уже занят")
			return
		}
		writeAPIError(w, 502, "Выдача ожидает проверки. Не создавайте повторный запрос.")
		return
	}
	if _, err := s.read.pool.Exec(ctx, `UPDATE investor_username_mints SET status='completed' WHERE user_id=$1 AND request_key=$2`, req.UserID, req.Key); err != nil {
		writeAPIError(w, 502, "Имя выдано; обновление статуса задержано. Повторите тот же запрос.")
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "username": name})
}

func (s *server) investorVerification(w http.ResponseWriter, r *http.Request, req investorBridgeRequest) {
	if !investorKeyPattern.MatchString(req.Key) {
		writeAPIError(w, 400, "Некорректный ключ операции")
		return
	}
	command := admin.SetVerifiedRequest{CommandMeta: admin.CommandMeta{CommandID: investorCommandID("verify", req.UserID, req.Key), Actor: fmt.Sprintf("investor:%d", req.UserID), Reason: "Investor self-service verification"}, UserID: req.UserID, Verified: req.Verified}
	result, err := s.callAdminAPI(r.Context(), "/v1/accounts/set-verified", command)
	if err != nil || result.Status != "completed" {
		writeAPIError(w, 502, "Не удалось обновить верификацию")
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "verified": req.Verified})
}
