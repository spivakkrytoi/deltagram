package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestInvestorBridgeRejectsMissingOrWrongKeyBeforeDB(t *testing.T) {
	s := &server{cfg: uiConfig{InvestorBridgeKey: strings.Repeat("x", 32)}}
	for _, secret := range []string{"", "wrong", strings.Repeat("y", 32)} {
		r := httptest.NewRequest(http.MethodPost, "/api/investor-bridge", strings.NewReader(`{"user_id":123,"action":"stars"}`))
		r.Header.Set("X-Investor-Bridge-Key", secret)
		w := httptest.NewRecorder()
		s.handleInvestorBridge(w, r)
		if w.Code != http.StatusUnauthorized {
			t.Fatalf("key %q: status %d, want 401", secret, w.Code)
		}
	}
}

func TestInvestorSelfServiceConstraints(t *testing.T) {
	for _, name := range []string{"abcd", "a_12", "a" + strings.Repeat("b", 31)} {
		if !investorNamePattern.MatchString(name) {
			t.Errorf("valid name rejected: %q", name)
		}
	}
	for _, name := range []string{"ab", "abc", "1abc", "a-bc", strings.Repeat("b", 33), "юзер"} {
		if investorNamePattern.MatchString(name) {
			t.Errorf("invalid name admitted: %q", name)
		}
	}
	if investorCommandID("stars", 1, "0123456789abcdef") == investorCommandID("stars", 2, "0123456789abcdef") {
		t.Fatal("command ID must be scoped to investor ID")
	}
	if investorCommandID("stars", 1, "0123456789abcdef") == investorCommandID("mint", 1, "0123456789abcdef") {
		t.Fatal("command ID must be scoped to action")
	}
}
