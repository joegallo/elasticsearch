package main

import "strings"

// FilteredStack is a Stack that matched a marker, with the index of the first matching frame.
type FilteredStack struct {
	Frames      []string
	Count       int
	MarkerIndex int
}

// FilterByMarker returns stacks that contain any of the marker substrings.
// MarkerIndex is set to the index of the first matching frame.
func FilterByMarker(stacks []Stack, markers []string) []FilteredStack {
	var result []FilteredStack
	for _, s := range stacks {
		for i, frame := range s.Frames {
			if containsAny(frame, markers) {
				result = append(result, FilteredStack{
					Frames:      s.Frames,
					Count:       s.Count,
					MarkerIndex: i,
				})
				break
			}
		}
	}
	return result
}

// ApplyStackFilters refines marker stacks using -f/--filter and -x/--exclude options.
// filters are ANDed: all must appear somewhere in the stack string.
// excludes are ORed: any match drops the stack.
func ApplyStackFilters(stacks []FilteredStack, filters, excludes []string) []FilteredStack {
	var result []FilteredStack
	for _, s := range stacks {
		stackStr := strings.Join(s.Frames, ";")
		if !allContained(stackStr, filters) {
			continue
		}
		if containsAny(stackStr, excludes) {
			continue
		}
		result = append(result, s)
	}
	return result
}

func containsAny(s string, subs []string) bool {
	for _, sub := range subs {
		if strings.Contains(s, sub) {
			return true
		}
	}
	return false
}

func allContained(s string, subs []string) bool {
	for _, sub := range subs {
		if !strings.Contains(s, sub) {
			return false
		}
	}
	return true
}
