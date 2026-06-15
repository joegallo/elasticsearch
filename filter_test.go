package main

import (
	"strings"
	"testing"
)

func allStacks() []Stack {
	return ParseReader(strings.NewReader(sampleStacks))
}

func markerStacks() []FilteredStack {
	return FilterByMarker(allStacks(), []string{"X.target"})
}

func TestFilterByMarker(t *testing.T) {
	t.Run("filters by marker", func(t *testing.T) {
		filtered := FilterByMarker(allStacks(), []string{"X.target"})
		if len(filtered) != 4 {
			t.Fatalf("got %d stacks, want 4", len(filtered))
		}
		total := 0
		for _, s := range filtered {
			total += s.Count
		}
		if total != 190 {
			t.Errorf("total = %d, want 190", total)
		}
	})

	t.Run("marker index is correct", func(t *testing.T) {
		filtered := FilterByMarker(allStacks(), []string{"X.target"})
		for i, wantIdx := range []int{3, 2, 1} {
			if filtered[i].MarkerIndex != wantIdx {
				t.Errorf("filtered[%d].MarkerIndex = %d, want %d", i, filtered[i].MarkerIndex, wantIdx)
			}
		}
	})

	t.Run("no matches returns empty", func(t *testing.T) {
		filtered := FilterByMarker(allStacks(), []string{"NoSuchMethod"})
		if len(filtered) != 0 {
			t.Errorf("expected empty, got %d results", len(filtered))
		}
	})

	t.Run("multiple markers are ORed", func(t *testing.T) {
		filtered := FilterByMarker(allStacks(), []string{"other", "vtable stub"})
		if len(filtered) != 2 {
			t.Fatalf("got %d stacks, want 2", len(filtered))
		}
		total := 0
		for _, s := range filtered {
			total += s.Count
		}
		if total != 240 {
			t.Errorf("total = %d, want 240", total)
		}
	})

	t.Run("first matching frame wins for marker index", func(t *testing.T) {
		filtered := FilterByMarker(allStacks(), []string{"X.target", "leaf1"})
		for _, s := range filtered {
			if s.Frames[len(s.Frames)-1] == "leaf1_[j]" {
				// X.target is at index 3, leaf1 is at index 6; first match wins
				if s.MarkerIndex != 3 {
					t.Errorf("marker index = %d, want 3", s.MarkerIndex)
				}
			}
		}
	})
}

func TestApplyStackFilters(t *testing.T) {
	t.Run("filter keeps matching", func(t *testing.T) {
		result := ApplyStackFilters(markerStacks(), []string{"leaf1"}, nil)
		if len(result) != 1 {
			t.Fatalf("got %d, want 1", len(result))
		}
		if result[0].Frames[len(result[0].Frames)-1] != "leaf1_[j]" {
			t.Errorf("unexpected leaf: %s", result[0].Frames[len(result[0].Frames)-1])
		}
	})

	t.Run("multiple filters are ANDed", func(t *testing.T) {
		result := ApplyStackFilters(markerStacks(), []string{"leaf1", "e"}, nil)
		if len(result) != 1 {
			t.Fatalf("got %d, want 1", len(result))
		}
		if result[0].Count != 100 {
			t.Errorf("count = %d, want 100", result[0].Count)
		}
	})

	t.Run("filter with no match returns empty", func(t *testing.T) {
		result := ApplyStackFilters(markerStacks(), []string{"NoSuch"}, nil)
		if len(result) != 0 {
			t.Errorf("expected empty, got %d", len(result))
		}
	})

	t.Run("exclude drops matching", func(t *testing.T) {
		result := ApplyStackFilters(markerStacks(), nil, []string{"leaf1"})
		if len(result) != 3 {
			t.Fatalf("got %d, want 3", len(result))
		}
	})

	t.Run("multiple excludes are ORed", func(t *testing.T) {
		result := ApplyStackFilters(markerStacks(), nil, []string{"leaf1", "leaf2"})
		if len(result) != 2 {
			t.Fatalf("got %d, want 2", len(result))
		}
	})

	t.Run("filter and exclude combined", func(t *testing.T) {
		result := ApplyStackFilters(markerStacks(), []string{"d"}, []string{"leaf2"})
		if len(result) != 1 {
			t.Fatalf("got %d, want 1", len(result))
		}
		if result[0].Frames[len(result[0].Frames)-1] != "leaf1_[j]" {
			t.Errorf("unexpected leaf: %s", result[0].Frames[len(result[0].Frames)-1])
		}
	})

	t.Run("empty filters and excludes passes all", func(t *testing.T) {
		ms := markerStacks()
		result := ApplyStackFilters(ms, nil, nil)
		if len(result) != len(ms) {
			t.Errorf("got %d, want %d", len(result), len(ms))
		}
	})

	t.Run("preserves marker index", func(t *testing.T) {
		result := ApplyStackFilters(markerStacks(), []string{"leaf3"}, nil)
		if len(result) != 1 {
			t.Fatalf("got %d, want 1", len(result))
		}
		// a;X.target_[j];g;leaf3_[k] -> X.target at index 1
		if result[0].MarkerIndex != 1 {
			t.Errorf("marker index = %d, want 1", result[0].MarkerIndex)
		}
	})
}
