package main

import (
	"strings"
	"testing"
)

// sampleCategories is shared with commands_test.go.
const sampleCategories = `# Leaves
leaf1
leaf2

# Stubs
vtable stub
itable stub
`

func TestLoadCategoriesReader(t *testing.T) {
	t.Run("parses categories", func(t *testing.T) {
		cats := LoadCategoriesReader(strings.NewReader(sampleCategories))
		if len(cats) != 2 {
			t.Fatalf("got %d categories, want 2", len(cats))
		}
		if cats[0].Name != "Leaves" {
			t.Errorf("cats[0].Name = %q, want %q", cats[0].Name, "Leaves")
		}
		if !equalSlice(cats[0].Keywords, []string{"leaf1", "leaf2"}) {
			t.Errorf("cats[0].Keywords = %v", cats[0].Keywords)
		}
		if cats[1].Name != "Stubs" {
			t.Errorf("cats[1].Name = %q, want %q", cats[1].Name, "Stubs")
		}
		if !equalSlice(cats[1].Keywords, []string{"vtable stub", "itable stub"}) {
			t.Errorf("cats[1].Keywords = %v", cats[1].Keywords)
		}
	})

	t.Run("empty input", func(t *testing.T) {
		cats := LoadCategoriesReader(strings.NewReader(""))
		if len(cats) != 0 {
			t.Errorf("expected empty, got %d", len(cats))
		}
	})

	t.Run("single category", func(t *testing.T) {
		cats := LoadCategoriesReader(strings.NewReader("# Only one\nkeyword1\nkeyword2\n"))
		if len(cats) != 1 {
			t.Fatalf("got %d categories, want 1", len(cats))
		}
		if cats[0].Name != "Only one" {
			t.Errorf("name = %q, want %q", cats[0].Name, "Only one")
		}
		if !equalSlice(cats[0].Keywords, []string{"keyword1", "keyword2"}) {
			t.Errorf("keywords = %v", cats[0].Keywords)
		}
	})
}
