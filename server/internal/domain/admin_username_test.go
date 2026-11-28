package domain

import (
	"strings"
	"testing"
)

func TestValidAdminUsername(t *testing.T) {
	cases := []struct {
		name   string
		want   bool
		reason string
	}{
		{name: "abc", want: true, reason: "three letters"},
		{name: "ab_", want: true, reason: "underscore is allowed after the first character"},
		{name: "a1c", want: true, reason: "digit is allowed after the first character"},
		{name: "привет", want: true, reason: "Cyrillic letters are allowed"},
		{name: "абв", want: true, reason: "three Cyrillic letters"},
		{name: "ёжик", want: true, reason: "ё is a Cyrillic letter"},
		{name: "Ёжик", want: true, reason: "uppercase Cyrillic first letter"},
		{name: "тест_1", want: true, reason: "Cyrillic with underscore and digit"},
		{name: strings.Repeat("я", MaxCollectibleUsernameLength), want: true, reason: "32 Cyrillic runes"},
		{name: "ab", want: true, reason: "two Latin letters are allowed for operators"},
		{name: "ёж", want: true, reason: "two Cyrillic letters are allowed for operators"},
		{name: "a", want: false, reason: "one character is below the operator minimum"},
		{name: "я", want: false, reason: "one Cyrillic rune is below the operator minimum"},
		{name: "1ab", want: false, reason: "must start with a letter"},
		{name: "1тест", want: false, reason: "must start with a letter, Cyrillic included"},
		{name: "_ab", want: false, reason: "must start with a letter"},
		{name: "ab-c", want: false, reason: "dash is not part of the grammar"},
		{name: "тест-1", want: false, reason: "dash is not part of the grammar"},
		{name: "тест 1", want: false, reason: "space is not part of the grammar"},
		{name: "日本語", want: false, reason: "only ASCII and Cyrillic letters are supported"},
		{name: "test!", want: false, reason: "punctuation is not part of the grammar"},
		{name: strings.Repeat("я", MaxCollectibleUsernameLength+1), want: false, reason: "above the protocol maximum"},
		{name: "", want: false, reason: "empty"},
	}
	for _, tc := range cases {
		if got := ValidAdminUsername(tc.name); got != tc.want {
			t.Errorf("ValidAdminUsername(%q) = %v, want %v (%s)", tc.name, got, tc.want, tc.reason)
		}
	}
}

func TestValidAdminUsernameKeepsPublicCollectibleMinimum(t *testing.T) {
	if ValidCollectibleUsername("abc") {
		t.Fatal("public collectible minimum must stay at four characters")
	}
	if !ValidAdminUsername("ab") {
		t.Fatal("operator path must accept a two-character name")
	}
}

func TestPublicValidatorsRejectCyrillic(t *testing.T) {
	if ValidCollectibleUsername("привет") {
		t.Fatal("public collectible surface must keep rejecting Cyrillic names")
	}
	if !ValidResolvableUsername("привет") {
		t.Fatal("lookup path must accept an operator-issued Cyrillic name")
	}
}

func TestValidResolvableUsernameCoversAdminNames(t *testing.T) {
	for _, name := range []string{"ab", "ёж", "abc", "привет", "абв", "ёжик", "Ёжик"} {
		if !ValidResolvableUsername(name) {
			t.Errorf("ValidResolvableUsername(%q) = false, want true", name)
		}
	}
	for _, name := range []string{"a", "я", "1тест", "тест-1"} {
		if ValidResolvableUsername(name) {
			t.Errorf("ValidResolvableUsername(%q) = true, want false", name)
		}
	}
}
