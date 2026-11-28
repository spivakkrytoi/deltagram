package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestInvestorWorkspaceIsSeparateFromLogin(t *testing.T) {
	page, err := assets.ReadFile("web/index.html")
	if err != nil {
		t.Fatal(err)
	}
	html := string(page)
	login := strings.Index(html, `id="login-view"`)
	cardEnd := strings.Index(html, `id="login-notice"`)
	workspace := strings.Index(html, `id="account-view"`)
	if login < 0 || cardEnd <= login || workspace <= cardEnd {
		t.Fatal("authenticated workspace must follow the login card")
	}
	for _, tab := range []string{"stars", "names", "verified"} {
		if !strings.Contains(html[workspace:], `id="tab-`+tab+`"`) ||
			!strings.Contains(html[workspace:], `id="pane-`+tab+`"`) {
			t.Fatalf("missing investor workspace tab %q", tab)
		}
	}
	script, err := assets.ReadFile("web/app.js")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(script), `classList.add("authenticated")`) ||
		!strings.Contains(string(script), `classList.remove("authenticated")`) {
		t.Fatal("login and logout must switch the full-page layout")
	}
}

func TestPostRequiresSameOriginJSONAndHeader(t *testing.T) {
	for _, tc := range []struct {
		name, origin, fetch, contentType, header string
		want                                     bool
	}{
		{"valid", "https://dltagram.duckdns.org", "same-origin", "application/json", "1", true},
		{"cross site", "https://evil.example", "cross-site", "application/json", "1", false},
		{"cross site fetch", "https://dltagram.duckdns.org", "cross-site", "application/json", "1", false},
		{"form", "https://dltagram.duckdns.org", "same-origin", "text/plain", "1", false},
		{"missing header", "https://dltagram.duckdns.org", "same-origin", "application/json", "", false},
		{"origin prefix", "https://dltagram.duckdns.org.evil.example", "same-origin", "application/json", "1", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r := httptest.NewRequest("POST", "https://dltagram.duckdns.org/investor/api/stars", strings.NewReader("{}"))
			r.Header.Set("Origin", tc.origin)
			r.Header.Set("Sec-Fetch-Site", tc.fetch)
			r.Header.Set("Content-Type", tc.contentType)
			r.Header.Set("X-Investor-Request", tc.header)
			if got := safePost(r); got != tc.want {
				t.Fatalf("safePost=%v, want %v", got, tc.want)
			}
		})
	}
}

func TestLoginRateLimitCannotBypassWithSourcePort(t *testing.T) {
	p := &panel{attempts: make(map[string][]time.Time)}
	for i := 0; i < 5; i++ {
		if !p.allow("192.0.2.1:" + strconv.Itoa(1000+i)) {
			t.Fatal("early limit")
		}
	}
	if p.allow("192.0.2.1:9999") {
		t.Fatal("different source port bypasses limit")
	}
	if !p.allow("192.0.2.2:9999") {
		t.Fatal("other address limited")
	}
}

func TestForwardedAddressOnlyFromLoopbackProxy(t *testing.T) {
	r := httptest.NewRequest("POST", "https://dltagram.duckdns.org/investor/api/login", nil)
	r.Header.Set("X-Forwarded-For", "192.0.2.7")
	r.RemoteAddr = "127.0.0.1:50000"
	if got := loginAddress(r); got != "192.0.2.7" {
		t.Fatalf("trusted proxy address=%q", got)
	}
	r.RemoteAddr = "198.51.100.1:50000"
	if got := loginAddress(r); got != "198.51.100.1" {
		t.Fatalf("untrusted forwarded address=%q", got)
	}
	r.RemoteAddr = "127.0.0.1:50001"
	r.Header.Set("X-Forwarded-For", "192.0.2.7, 203.0.113.2")
	if got := loginAddress(r); got != "127.0.0.1" {
		t.Fatalf("ambiguous forwarded address=%q", got)
	}
}

func TestOperationKeyGrammar(t *testing.T) {
	for _, value := range []string{"0123456789abcdef", "6e153e51-4f85-46d3-a8be-2d65c03a73f0"} {
		if !validKey(value) {
			t.Errorf("valid key %q rejected", value)
		}
	}
	for _, value := range []string{"short", strings.Repeat("a", 65), "0123456789abcde_", "0123456789abcde\n"} {
		if validKey(value) {
			t.Errorf("invalid key %q admitted", value)
		}
	}
}

func TestBridgeNeverForwardsBrowserUserID(t *testing.T) {
	var got map[string]any
	stub := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Investor-Bridge-Key") != "test-bridge-key" {
			t.Error("missing bridge key")
		}
		if err := json.NewDecoder(r.Body).Decode(&got); err != nil {
			t.Error(err)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"ok":true}`))
	}))
	defer stub.Close()
	p := &panel{bridgeURL: stub.URL, bridgeKey: "test-bridge-key", client: stub.Client()}
	w := httptest.NewRecorder()
	p.bridge(w, httptest.NewRequest("POST", "/investor/api/stars", nil), 123, "stars",
		map[string]any{"user_id": 456, "amount": 1, "key": "0123456789abcdef"})
	if w.Code != 200 {
		t.Fatalf("bridge status: %d", w.Code)
	}
	if got["user_id"] != float64(123) {
		t.Fatalf("browser supplied user id escaped: %v", got)
	}
}

func TestProtectedActionsRequireInvestorSession(t *testing.T) {
	p := &panel{}
	for _, action := range []struct{ method, path string }{
		{"GET", "/investor/api/state"},
		{"GET", "/investor/api/username-availability?name=abcd"},
		{"POST", "/investor/api/stars"},
		{"POST", "/investor/api/mint"},
		{"POST", "/investor/api/verification"},
	} {
		r := httptest.NewRequest(action.method, "https://dltagram.duckdns.org"+action.path, strings.NewReader(`{"user_id":42}`))
		if action.method == "POST" {
			r.Header.Set("Origin", "https://dltagram.duckdns.org")
			r.Header.Set("Content-Type", "application/json")
			r.Header.Set("X-Investor-Request", "1")
		}
		w := httptest.NewRecorder()
		p.routes().ServeHTTP(w, r)
		if w.Code != http.StatusUnauthorized {
			t.Errorf("%s %s: got status %d, want 401", action.method, action.path, w.Code)
		}
	}
}

func TestLoginGrammar(t *testing.T) {
	for _, login := range []string{"investor", "Anna_01", "x.y", "Owner-1"} {
		if !validLogin(login) {
			t.Errorf("valid login %q rejected", login)
		}
	}
	for _, login := range []string{"ab", "1owner", "юзер", "with space", "a/b", strings.Repeat("a", 49)} {
		if validLogin(login) {
			t.Errorf("invalid login %q accepted", login)
		}
	}
}
