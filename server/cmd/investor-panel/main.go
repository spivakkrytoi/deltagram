package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"io/fs"
	"log"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"golang.org/x/crypto/bcrypt"
)

//go:embed web/* web/assets/*
var assets embed.FS

const base = "/investor"

// Fixed bcrypt work for unknown IDs prevents timing from confirming whether
// an account has been invited.
const dummyHash = "$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"

type panel struct {
	db        *pgxpool.Pool
	bridgeURL string
	bridgeKey string
	client    *http.Client
	mu        sync.Mutex
	attempts  map[string][]time.Time
}

func main() {
	if len(os.Args) == 2 && os.Args[1] == "-hash-password" {
		password, err := io.ReadAll(io.LimitReader(os.Stdin, 73))
		if err != nil || len(password) == 0 || len(password) > 72 {
			log.Fatal("password must be 1–72 bytes on stdin")
		}
		password = []byte(strings.TrimRight(string(password), "\r\n"))
		if len(password) < 12 {
			log.Fatal("password must have at least 12 bytes")
		}
		hash, err := bcrypt.GenerateFromPassword(password, 12)
		if err != nil {
			log.Fatal(err)
		}
		fmt.Println(string(hash))
		return
	}
	dsn := os.Getenv("INVESTOR_POSTGRES_DSN")
	if dsn == "" {
		log.Fatal("INVESTOR_POSTGRES_DSN is required")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	db, err := pgxpool.New(ctx, dsn)
	if err != nil {
		log.Fatal(err)
	}
	defer db.Close()
	if err := db.Ping(ctx); err != nil {
		log.Fatal(err)
	}
	bridgeKey := os.Getenv("INVESTOR_BRIDGE_KEY")
	if len(bridgeKey) < 32 {
		log.Fatal("INVESTOR_BRIDGE_KEY must be configured")
	}
	p := &panel{db: db, bridgeURL: "http://127.0.0.1:2600/api/investor-bridge", bridgeKey: bridgeKey, client: &http.Client{Timeout: 10 * time.Second}, attempts: make(map[string][]time.Time)}
	addr := os.Getenv("INVESTOR_LISTEN")
	if addr == "" {
		addr = "127.0.0.1:2802"
	}
	log.Printf("investor panel listening on %s", addr)
	log.Fatal((&http.Server{Addr: addr, Handler: p.routes(), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 12 * time.Second, WriteTimeout: 15 * time.Second}).ListenAndServe())
}

func (p *panel) routes() http.Handler {
	mux := http.NewServeMux()
	web, _ := fs.Sub(assets, "web")
	mux.HandleFunc("GET "+base, func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, base+"/", http.StatusPermanentRedirect)
	})
	mux.Handle("GET "+base+"/", http.StripPrefix(base+"/", http.FileServer(http.FS(web))))
	mux.HandleFunc("POST "+base+"/api/login", p.login)
	mux.HandleFunc("POST "+base+"/api/logout", p.logout)
	mux.HandleFunc("GET "+base+"/api/me", p.me)
	mux.HandleFunc("GET "+base+"/api/state", p.state)
	mux.HandleFunc("GET "+base+"/api/username-availability", p.availability)
	mux.HandleFunc("POST "+base+"/api/stars", p.stars)
	mux.HandleFunc("POST "+base+"/api/mint", p.mint)
	mux.HandleFunc("POST "+base+"/api/verification", p.verification)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
		w.Header().Set("Cache-Control", "no-store")
		if strings.Contains(r.URL.Path, "/api/") {
			w.Header().Set("Content-Type", "application/json; charset=utf-8")
		}
		mux.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, code int, value any) {
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(value)
}

func decode(w http.ResponseWriter, r *http.Request, out any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, 1024)
	d := json.NewDecoder(r.Body)
	d.DisallowUnknownFields()
	if err := d.Decode(out); err != nil {
		writeJSON(w, 400, map[string]string{"error": "Некорректные данные"})
		return false
	}
	var extra any
	if d.Decode(&extra) != io.EOF {
		writeJSON(w, 400, map[string]string{"error": "Некорректные данные"})
		return false
	}
	return true
}

func safePost(r *http.Request) bool {
	// JSON + a custom header are never sent by a plain cross-site HTML form.
	if r.Header.Get("X-Investor-Request") != "1" || !strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
		return false
	}
	if r.Header.Get("Sec-Fetch-Site") == "cross-site" {
		return false
	}
	origin := r.Header.Get("Origin")
	if origin == "" {
		return false
	}
	host := r.Host
	return origin == "https://"+host || origin == "http://"+host
}

func (p *panel) allow(ip string) bool {
	if host, _, err := net.SplitHostPort(ip); err == nil {
		ip = host
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	now := time.Now()
	// Avoid unbounded memory if arbitrary remote addresses try the login.
	if len(p.attempts) > 10000 {
		p.attempts = make(map[string][]time.Time)
	}
	var recent []time.Time
	for _, t := range p.attempts[ip] {
		if now.Sub(t) < 10*time.Minute {
			recent = append(recent, t)
		}
	}
	if len(recent) >= 5 {
		p.attempts[ip] = recent
		return false
	}
	p.attempts[ip] = append(recent, now)
	return true
}

func loginAddress(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	// A reverse proxy on the same host supplies the *actual* client IP.
	// Do not accept forwarding headers from external direct connections.
	if ip := net.ParseIP(host); ip != nil && ip.IsLoopback() {
		forwarded := strings.TrimSpace(r.Header.Get("X-Forwarded-For"))
		if client := net.ParseIP(forwarded); client != nil {
			return client.String()
		}
	}
	return host
}

func (p *panel) login(w http.ResponseWriter, r *http.Request) {
	if !safePost(r) {
		writeJSON(w, 403, map[string]string{"error": "Запрос отклонён"})
		return
	}
	if !p.allow(loginAddress(r)) {
		writeJSON(w, 429, map[string]string{"error": "Слишком много попыток. Попробуйте позже."})
		return
	}
	var req struct {
		Login    string `json:"login"`
		Password string `json:"password"`
	}
	if !decode(w, r, &req) {
		return
	}
	if !validLogin(req.Login) || len(req.Password) > 72 {
		writeJSON(w, 401, map[string]string{"error": "Неверный логин или пароль"})
		return
	}
	var hash string
	var id int64
	// Never authenticate using a password from an admin console.
	err := p.db.QueryRow(r.Context(), `SELECT user_id,password_hash FROM investor_memberships WHERE lower(login)=lower($1) AND active`, req.Login).Scan(&id, &hash)
	if err != nil {
		hash = dummyHash
	}
	if bcrypt.CompareHashAndPassword([]byte(hash), []byte(req.Password)) != nil || err != nil {
		writeJSON(w, 401, map[string]string{"error": "Неверный логин или пароль"})
		return
	}
	var token [32]byte
	if _, err := rand.Read(token[:]); err != nil {
		writeJSON(w, 500, map[string]string{"error": "Ошибка входа"})
		return
	}
	key := sha256.Sum256(token[:])
	_, err = p.db.Exec(r.Context(), `INSERT INTO investor_sessions(token_hash,user_id,expires_at) VALUES($1,$2,now()+interval '7 days')`, key[:], id)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": "Ошибка входа"})
		return
	}
	http.SetCookie(w, &http.Cookie{Name: "investor_session", Value: hex.EncodeToString(token[:]), Path: base, Secure: true, HttpOnly: true, SameSite: http.SameSiteStrictMode, MaxAge: 7 * 24 * 3600})
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (p *panel) userID(r *http.Request) (int64, []byte, bool) {
	cookie, err := r.Cookie("investor_session")
	if err != nil {
		return 0, nil, false
	}
	raw, err := hex.DecodeString(cookie.Value)
	if err != nil || len(raw) != 32 {
		return 0, nil, false
	}
	key := sha256.Sum256(raw)
	var id int64
	err = p.db.QueryRow(r.Context(), `SELECT s.user_id FROM investor_sessions s JOIN investor_memberships m ON m.user_id=s.user_id WHERE s.token_hash=$1 AND s.expires_at>now() AND m.active`, key[:]).Scan(&id)
	return id, key[:], err == nil
}

func (p *panel) logout(w http.ResponseWriter, r *http.Request) {
	if !safePost(r) {
		writeJSON(w, 403, map[string]string{"error": "Запрос отклонён"})
		return
	}
	_, key, ok := p.userID(r)
	if ok {
		_, _ = p.db.Exec(r.Context(), `DELETE FROM investor_sessions WHERE token_hash=$1`, key)
	}
	http.SetCookie(w, &http.Cookie{Name: "investor_session", Value: "", Path: base, Secure: true, HttpOnly: true, SameSite: http.SameSiteStrictMode, MaxAge: -1})
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (p *panel) me(w http.ResponseWriter, r *http.Request) {
	id, _, ok := p.userID(r)
	if !ok {
		writeJSON(w, 401, map[string]string{"error": "Войдите в личный кабинет"})
		return
	}
	var name, username, login string
	err := p.db.QueryRow(r.Context(), `SELECT u.first_name,u.username,m.login FROM users u JOIN investor_memberships m ON m.user_id=u.id WHERE u.id=$1`, id).Scan(&name, &username, &login)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": "Не удалось загрузить профиль"})
		return
	}
	writeJSON(w, 200, map[string]any{"id": id, "name": name, "username": username, "login": login})
}

func (p *panel) bridge(w http.ResponseWriter, r *http.Request, id int64, action string, input map[string]any) {
	body := map[string]any{}
	for k, v := range input {
		body[k] = v
	}
	// Never allow forwarded fields to choose the target account or action.
	body["user_id"] = id
	body["action"] = action
	data, err := json.Marshal(body)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": "Ошибка запроса"})
		return
	}
	req, err := http.NewRequestWithContext(r.Context(), http.MethodPost, p.bridgeURL, bytes.NewReader(data))
	if err != nil {
		writeJSON(w, 503, map[string]string{"error": "Сервис недоступен"})
		return
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Investor-Bridge-Key", p.bridgeKey)
	resp, err := p.client.Do(req)
	if err != nil {
		writeJSON(w, 503, map[string]string{"error": "Сервис временно недоступен"})
		return
	}
	defer resp.Body.Close()
	response, err := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if err != nil {
		writeJSON(w, 502, map[string]string{"error": "Ошибка сервиса"})
		return
	}
	if resp.StatusCode >= 400 {
		var value map[string]any
		_ = json.Unmarshal(response, &value)
		text, _ := value["error"].(string)
		if text == "" {
			text = "Операция не выполнена"
		}
		writeJSON(w, resp.StatusCode, map[string]string{"error": text})
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(resp.StatusCode)
	_, _ = w.Write(response)
}

func (p *panel) state(w http.ResponseWriter, r *http.Request) {
	id, _, ok := p.userID(r)
	if !ok {
		writeJSON(w, 401, map[string]string{"error": "Войдите в личный кабинет"})
		return
	}
	p.bridge(w, r, id, "status", nil)
}

func (p *panel) availability(w http.ResponseWriter, r *http.Request) {
	id, _, ok := p.userID(r)
	if !ok {
		writeJSON(w, 401, map[string]string{"error": "Войдите в личный кабинет"})
		return
	}
	p.bridge(w, r, id, "availability", map[string]any{"username": r.URL.Query().Get("name")})
}

func (p *panel) stars(w http.ResponseWriter, r *http.Request) {
	if !safePost(r) {
		writeJSON(w, 403, map[string]string{"error": "Запрос отклонён"})
		return
	}
	id, _, ok := p.userID(r)
	if !ok {
		writeJSON(w, 401, map[string]string{"error": "Войдите в личный кабинет"})
		return
	}
	var input struct {
		Amount int64  `json:"amount"`
		Key    string `json:"key"`
	}
	if !decode(w, r, &input) {
		return
	}
	if input.Amount < 1 || input.Amount > 150000 || !validKey(input.Key) {
		writeJSON(w, 400, map[string]string{"error": "Некорректная сумма или ключ операции"})
		return
	}
	p.bridge(w, r, id, "stars", map[string]any{"amount": input.Amount, "key": input.Key})
}

func (p *panel) mint(w http.ResponseWriter, r *http.Request) {
	if !safePost(r) {
		writeJSON(w, 403, map[string]string{"error": "Запрос отклонён"})
		return
	}
	id, _, ok := p.userID(r)
	if !ok {
		writeJSON(w, 401, map[string]string{"error": "Войдите в личный кабинет"})
		return
	}
	var input struct {
		Username string `json:"username"`
		Key      string `json:"key"`
	}
	if !decode(w, r, &input) {
		return
	}
	if !validKey(input.Key) || len(input.Username) > 33 {
		writeJSON(w, 400, map[string]string{"error": "Некорректное имя или ключ операции"})
		return
	}
	p.bridge(w, r, id, "mint", map[string]any{"username": input.Username, "key": input.Key})
}

func (p *panel) verification(w http.ResponseWriter, r *http.Request) {
	if !safePost(r) {
		writeJSON(w, 403, map[string]string{"error": "Запрос отклонён"})
		return
	}
	id, _, ok := p.userID(r)
	if !ok {
		writeJSON(w, 401, map[string]string{"error": "Войдите в личный кабинет"})
		return
	}
	var input struct {
		Verified bool   `json:"verified"`
		Key      string `json:"key"`
	}
	if !decode(w, r, &input) {
		return
	}
	if !validKey(input.Key) {
		writeJSON(w, 400, map[string]string{"error": "Некорректный ключ операции"})
		return
	}
	p.bridge(w, r, id, "verification", map[string]any{"verified": input.Verified, "key": input.Key})
}

func validKey(key string) bool {
	if len(key) < 16 || len(key) > 64 {
		return false
	}
	for _, c := range key {
		if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' {
			continue
		}
		return false
	}
	return true
}

func validLogin(login string) bool {
	if len(login) < 3 || len(login) > 48 {
		return false
	}
	for i, c := range login {
		if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
			(i > 0 && ((c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-')) {
			continue
		}
		return false
	}
	return true
}
