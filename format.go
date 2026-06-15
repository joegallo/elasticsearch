package main

import (
	"fmt"
	"io"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
)

// FmtPct formats count/total as a right-aligned percentage string (e.g. " 52.6%").
func FmtPct(count, total int) string {
	if total == 0 {
		return "  N/A"
	}
	return fmt.Sprintf("%5.1f%%", 100.0*float64(count)/float64(total))
}

// ShortName returns the filename without directory or extension.
func ShortName(path string) string {
	base := filepath.Base(path)
	return strings.TrimSuffix(base, filepath.Ext(base))
}

// Commify formats an integer with thousands separators (e.g. 1234567 → "1,234,567").
func Commify(n int) string {
	s := strconv.Itoa(n)
	var b strings.Builder
	for i, c := range s {
		if i > 0 && (len(s)-i)%3 == 0 {
			b.WriteByte(',')
		}
		b.WriteRune(c)
	}
	return b.String()
}

type entry struct {
	key   string
	count int
}

// sortedEntries returns map entries sorted by count descending, then key ascending.
func sortedEntries(m map[string]int) []entry {
	entries := make([]entry, 0, len(m))
	for k, v := range m {
		entries = append(entries, entry{k, v})
	}
	slices.SortFunc(entries, func(a, b entry) int {
		if b.count != a.count {
			return b.count - a.count
		}
		return strings.Compare(a.key, b.key)
	})
	return entries
}

// printTopN prints up to topN entries from m, formatted with count, percentage, and label.
func printTopN(w io.Writer, m map[string]int, topN, total int) {
	entries := sortedEntries(m)
	if topN > 0 && len(entries) > topN {
		entries = entries[:topN]
	}
	for _, e := range entries {
		fmt.Fprintf(w, "  %10s (%s)  %s\n", Commify(e.count), FmtPct(e.count, total), e.key)
	}
}
