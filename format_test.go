package main

import "testing"

func TestFmtPct(t *testing.T) {
	tests := []struct {
		count, total int
		want         string
	}{
		{50, 200, " 25.0%"},
		{50, 0, "  N/A"},
		{100, 100, "100.0%"},
	}
	for _, tc := range tests {
		if got := FmtPct(tc.count, tc.total); got != tc.want {
			t.Errorf("FmtPct(%d, %d) = %q, want %q", tc.count, tc.total, got, tc.want)
		}
	}
}

func TestShortName(t *testing.T) {
	if got := ShortName("/some/path/my-profile.txt"); got != "my-profile" {
		t.Errorf("ShortName = %q, want %q", got, "my-profile")
	}
}

func TestCommify(t *testing.T) {
	tests := []struct {
		n    int
		want string
	}{
		{0, "0"},
		{100, "100"},
		{430, "430"},
		{1000, "1,000"},
		{1234567, "1,234,567"},
	}
	for _, tc := range tests {
		if got := Commify(tc.n); got != tc.want {
			t.Errorf("Commify(%d) = %q, want %q", tc.n, got, tc.want)
		}
	}
}
