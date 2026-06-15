package main

import (
	"strings"
	"testing"
)

// sampleStacks is shared across all test files in this package.
const sampleStacks = `a;b;c;X.target_[j];d;e;leaf1_[j] 100
a;b;X.target_[j];d;f;leaf2_[i] 50
a;X.target_[j];g;leaf3_[k] 30
a;b;c;other_[j] 200
a;b;vtable stub 40
a;X.target_[j];b;itable stub 10
`

func equalSlice(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func TestParseReader(t *testing.T) {
	t.Run("parses stacks", func(t *testing.T) {
		stacks := ParseReader(strings.NewReader(sampleStacks))
		if len(stacks) != 6 {
			t.Fatalf("got %d stacks, want 6", len(stacks))
		}
		s := stacks[0]
		want := []string{"a", "b", "c", "X.target_[j]", "d", "e", "leaf1_[j]"}
		if !equalSlice(s.Frames, want) {
			t.Errorf("frames = %v, want %v", s.Frames, want)
		}
		if s.Count != 100 {
			t.Errorf("count = %d, want 100", s.Count)
		}
	})

	t.Run("skips blank lines", func(t *testing.T) {
		stacks := ParseReader(strings.NewReader("a;b 10\n\n\nc;d 20\n"))
		if len(stacks) != 2 {
			t.Fatalf("got %d stacks, want 2", len(stacks))
		}
	})

	t.Run("skips malformed lines", func(t *testing.T) {
		stacks := ParseReader(strings.NewReader("no_count_here\na;b notanumber\na;b 5\n"))
		if len(stacks) != 1 {
			t.Fatalf("got %d stacks, want 1", len(stacks))
		}
		if stacks[0].Count != 5 {
			t.Errorf("count = %d, want 5", stacks[0].Count)
		}
	})
}
