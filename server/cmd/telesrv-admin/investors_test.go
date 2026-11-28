package main

import (
	"strings"
	"testing"
)

func TestValidInvestorLogin(t *testing.T) {
	for _, login := range []string{"investor", "Anna_01", "x.y", "Owner-1"} {
		if !validInvestorLogin(login) {
			t.Errorf("valid login %q rejected", login)
		}
	}
	for _, login := range []string{"ab", "1owner", "юзер", "with space", "a/b", strings.Repeat("a", 49)} {
		if validInvestorLogin(login) {
			t.Errorf("invalid login %q accepted", login)
		}
	}
}
