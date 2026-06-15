package main

import (
	"fmt"
	"io"
	"strings"

	"github.com/spf13/cobra"
)

type sharedOpts struct {
	markers  []string
	topN     int
	filters  []string
	excludes []string
}

func newRootCmd() *cobra.Command {
	opts := &sharedOpts{}

	root := &cobra.Command{
		Use:   "profile-analyzer <command>",
		Short: "Toolkit for analyzing JFR folded-stack CPU profiles",
		Long: `Toolkit for analyzing JFR folded-stack CPU profiles.

Input files are folded stack traces (one per line):
    frame1;frame2;...;leafFrame sampleCount

Example usage:

    # Summary: where does CPU go under a marker method?
    profile-analyzer summary -m IndexShard.prepareIndex *.txt

    # Top leaf frames under a marker
    profile-analyzer leaves -m IndexShard.prepareIndex *.txt

    # Who calls a specific frame? (walk up the stack)
    profile-analyzer callers -t 'MapN.probe' -m IndexShard.prepareIndex *.txt

    # Megamorphic call sites (callers of vtable/itable stubs)
    profile-analyzer megamorphic -m IndexShard.prepareIndex *.txt

    # Categorize leaf frames into user-defined buckets
    profile-analyzer categorize -m IndexShard.prepareIndex -c categories.txt *.txt

    # Show the first N distinct callees after the marker method
    profile-analyzer subtrees -m IndexShard.prepareIndex --depth 2 *.txt

    # Bottom-up view: group by last N frames from the leaf
    profile-analyzer bottomup -m IndexShard.prepareIndex --depth 3 *.txt`,
		SilenceUsage: true,
	}

	pf := root.PersistentFlags()
	pf.StringArrayVarP(&opts.markers, "marker", "m", nil, "filter to stacks containing this method substring (repeatable, ORed)")
	pf.IntVarP(&opts.topN, "top", "n", 20, "number of results to show")
	pf.StringArrayVarP(&opts.filters, "filter", "f", nil, "keep only stacks containing this substring (repeatable, ANDed)")
	pf.StringArrayVarP(&opts.excludes, "exclude", "x", nil, "drop stacks containing this substring (repeatable, ORed)")

	root.AddCommand(
		newSummaryCmd(opts),
		newLeavesCmd(opts),
		newCallersCmd(opts),
		newCalleesCmd(opts),
		newMegamorphicCmd(opts),
		newSubtreesCmd(opts),
		newBottomupCmd(opts),
		newCategorizeCmd(opts),
	)

	return root
}

func requireMarker(opts *sharedOpts) error {
	if len(opts.markers) == 0 {
		return fmt.Errorf("at least one --marker (-m) is required")
	}
	return nil
}

func getMarkerStacks(path string, opts *sharedOpts) ([]Stack, []FilteredStack, error) {
	all, err := ParseFile(path)
	if err != nil {
		return nil, nil, err
	}
	filtered := FilterByMarker(all, opts.markers)
	if len(opts.filters) > 0 || len(opts.excludes) > 0 {
		filtered = ApplyStackFilters(filtered, opts.filters, opts.excludes)
	}
	return all, filtered, nil
}

func sumStacks(stacks []Stack) int {
	n := 0
	for _, s := range stacks {
		n += s.Count
	}
	return n
}

func sumFiltered(stacks []FilteredStack) int {
	n := 0
	for _, s := range stacks {
		n += s.Count
	}
	return n
}

// -- summary -----------------------------------------------------------------

func newSummaryCmd(opts *sharedOpts) *cobra.Command {
	return &cobra.Command{
		Use:   "summary <files...>",
		Short: "High-level summary: total samples, marker samples, top-level callees",
		Args:  cobra.MinimumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if err := requireMarker(opts); err != nil {
				return err
			}
			w := cmd.OutOrStdout()
			for _, path := range args {
				if err := runSummary(w, path, opts); err != nil {
					return err
				}
			}
			return nil
		},
	}
}

func runSummary(w io.Writer, path string, opts *sharedOpts) error {
	all, filtered, err := getMarkerStacks(path, opts)
	if err != nil {
		return err
	}
	total := sumStacks(all)
	mt := sumFiltered(filtered)

	calleeCounts := make(map[string]int)
	for _, s := range filtered {
		if s.MarkerIndex+1 < len(s.Frames) {
			calleeCounts[s.Frames[s.MarkerIndex+1]] += s.Count
		}
	}

	fmt.Fprintf(w, "=== %s ===\n", ShortName(path))
	fmt.Fprintf(w, "Total samples:  %10s\n", Commify(total))
	fmt.Fprintf(w, "Marker samples: %10s (%s)\n", Commify(mt), FmtPct(mt, total))
	fmt.Fprintln(w)
	if len(calleeCounts) > 0 {
		fmt.Fprintln(w, "First callees after marker:")
		for _, e := range sortedEntries(calleeCounts) {
			fmt.Fprintf(w, "  %10s (%s)  %s\n", Commify(e.count), FmtPct(e.count, mt), e.key)
		}
	}
	fmt.Fprintln(w)
	return nil
}

// -- leaves ------------------------------------------------------------------

func newLeavesCmd(opts *sharedOpts) *cobra.Command {
	return &cobra.Command{
		Use:   "leaves <files...>",
		Short: "Top leaf frames (where CPU is actually spent) under the marker",
		Args:  cobra.MinimumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if err := requireMarker(opts); err != nil {
				return err
			}
			w := cmd.OutOrStdout()
			for _, path := range args {
				if err := runLeaves(w, path, opts); err != nil {
					return err
				}
			}
			return nil
		},
	}
}

func runLeaves(w io.Writer, path string, opts *sharedOpts) error {
	_, filtered, err := getMarkerStacks(path, opts)
	if err != nil {
		return err
	}
	mt := sumFiltered(filtered)

	leafCounts := make(map[string]int)
	for _, s := range filtered {
		leafCounts[s.Frames[len(s.Frames)-1]] += s.Count
	}

	fmt.Fprintf(w, "=== %s (%s samples) ===\n", ShortName(path), Commify(mt))
	printTopN(w, leafCounts, opts.topN, mt)
	fmt.Fprintln(w)
	return nil
}

// -- callers -----------------------------------------------------------------

func newCallersCmd(opts *sharedOpts) *cobra.Command {
	var target string
	cmd := &cobra.Command{
		Use:   "callers <files...>",
		Short: "Find who calls a target frame (within stacks that contain the marker)",
		Args:  cobra.MinimumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			w := cmd.OutOrStdout()
			for _, path := range args {
				if err := runCallers(w, path, opts, target); err != nil {
					return err
				}
			}
			return nil
		},
	}
	cmd.Flags().StringVarP(&target, "target", "t", "", "target frame substring to find callers of")
	_ = cmd.MarkFlagRequired("target")
	return cmd
}

func runCallers(w io.Writer, path string, opts *sharedOpts, target string) error {
	all, err := ParseFile(path)
	if err != nil {
		return err
	}

	type pair struct {
		frames []string
		count  int
	}
	var pairs []pair
	var total int

	if len(opts.markers) > 0 {
		filtered := FilterByMarker(all, opts.markers)
		if len(opts.filters) > 0 || len(opts.excludes) > 0 {
			filtered = ApplyStackFilters(filtered, opts.filters, opts.excludes)
		}
		for _, s := range filtered {
			pairs = append(pairs, pair{s.Frames, s.Count})
			total += s.Count
		}
	} else {
		for _, s := range all {
			pairs = append(pairs, pair{s.Frames, s.Count})
			total += s.Count
		}
	}

	callerCounts := make(map[string]int)
	for _, p := range pairs {
		for i, frame := range p.frames {
			if strings.Contains(frame, target) && i > 0 {
				callerCounts[p.frames[i-1]] += p.count
				break
			}
		}
	}

	fmt.Fprintf(w, "=== %s: callers of '%s' (%s samples) ===\n", ShortName(path), target, Commify(total))
	printTopN(w, callerCounts, opts.topN, total)
	fmt.Fprintln(w)
	return nil
}

// -- callees -----------------------------------------------------------------

func newCalleesCmd(opts *sharedOpts) *cobra.Command {
	var target string
	cmd := &cobra.Command{
		Use:   "callees <files...>",
		Short: "Find what a target frame calls (within stacks that contain the marker)",
		Args:  cobra.MinimumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			w := cmd.OutOrStdout()
			for _, path := range args {
				if err := runCallees(w, path, opts, target); err != nil {
					return err
				}
			}
			return nil
		},
	}
	cmd.Flags().StringVarP(&target, "target", "t", "", "target frame substring to find callees of")
	_ = cmd.MarkFlagRequired("target")
	return cmd
}

func runCallees(w io.Writer, path string, opts *sharedOpts, target string) error {
	all, err := ParseFile(path)
	if err != nil {
		return err
	}

	type pair struct {
		frames []string
		count  int
	}
	var pairs []pair
	var total int

	if len(opts.markers) > 0 {
		filtered := FilterByMarker(all, opts.markers)
		if len(opts.filters) > 0 || len(opts.excludes) > 0 {
			filtered = ApplyStackFilters(filtered, opts.filters, opts.excludes)
		}
		for _, s := range filtered {
			pairs = append(pairs, pair{s.Frames, s.Count})
			total += s.Count
		}
	} else {
		for _, s := range all {
			pairs = append(pairs, pair{s.Frames, s.Count})
			total += s.Count
		}
	}

	calleeCounts := make(map[string]int)
	for _, p := range pairs {
		for i, frame := range p.frames {
			if strings.Contains(frame, target) && i+1 < len(p.frames) {
				calleeCounts[p.frames[i+1]] += p.count
				break
			}
		}
	}

	fmt.Fprintf(w, "=== %s: callees of '%s' (%s samples) ===\n", ShortName(path), target, Commify(total))
	printTopN(w, calleeCounts, opts.topN, total)
	fmt.Fprintln(w)
	return nil
}

// -- megamorphic -------------------------------------------------------------

func newMegamorphicCmd(opts *sharedOpts) *cobra.Command {
	return &cobra.Command{
		Use:   "megamorphic <files...>",
		Short: "Find megamorphic call sites (callers of vtable/itable stubs)",
		Args:  cobra.MinimumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if err := requireMarker(opts); err != nil {
				return err
			}
			w := cmd.OutOrStdout()
			for _, path := range args {
				if err := runMegamorphic(w, path, opts); err != nil {
					return err
				}
			}
			return nil
		},
	}
}

func runMegamorphic(w io.Writer, path string, opts *sharedOpts) error {
	_, filtered, err := getMarkerStacks(path, opts)
	if err != nil {
		return err
	}
	mt := sumFiltered(filtered)

	callerCounts := make(map[string]int)
	for _, s := range filtered {
		leaf := s.Frames[len(s.Frames)-1]
		if (strings.Contains(leaf, "vtable stub") || strings.Contains(leaf, "itable stub")) && len(s.Frames) >= 2 {
			callerCounts[s.Frames[len(s.Frames)-2]] += s.Count
		}
	}

	fmt.Fprintf(w, "=== %s: megamorphic sites (%s samples) ===\n", ShortName(path), Commify(mt))
	printTopN(w, callerCounts, opts.topN, mt)
	fmt.Fprintln(w)
	return nil
}

// -- subtrees ----------------------------------------------------------------

func newSubtreesCmd(opts *sharedOpts) *cobra.Command {
	var depth int
	cmd := &cobra.Command{
		Use:   "subtrees <files...>",
		Short: "Show top sub-paths of a given depth below the marker",
		Args:  cobra.MinimumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if err := requireMarker(opts); err != nil {
				return err
			}
			w := cmd.OutOrStdout()
			for _, path := range args {
				if err := runSubtrees(w, path, opts, depth); err != nil {
					return err
				}
			}
			return nil
		},
	}
	cmd.Flags().IntVarP(&depth, "depth", "d", 2, "depth of sub-path")
	return cmd
}

func runSubtrees(w io.Writer, path string, opts *sharedOpts, depth int) error {
	_, filtered, err := getMarkerStacks(path, opts)
	if err != nil {
		return err
	}
	mt := sumFiltered(filtered)

	subtreeCounts := make(map[string]int)
	for _, s := range filtered {
		after := s.Frames[s.MarkerIndex+1:]
		if len(after) >= depth {
			subtreeCounts[strings.Join(after[:depth], " -> ")] += s.Count
		}
	}

	fmt.Fprintf(w, "=== %s: subtrees depth=%d (%s samples) ===\n", ShortName(path), depth, Commify(mt))
	printTopN(w, subtreeCounts, opts.topN, mt)
	fmt.Fprintln(w)
	return nil
}

// -- bottomup ----------------------------------------------------------------

func newBottomupCmd(opts *sharedOpts) *cobra.Command {
	var depth int
	cmd := &cobra.Command{
		Use:   "bottomup <files...>",
		Short: "Bottom-up view: group stacks by their last N frames (leaf chain)",
		Long: `Bottom-up view: group stacks by their last N frames from the leaf.

This aggregates call chains that arrive at the same hot path from different
callers. For example, with --depth 3 and stacks:

    a;x;y;z 50
    a;b;x;y;z 25

both contribute to the chain "x -> y -> z" with 75 total samples.`,
		Args: cobra.MinimumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if err := requireMarker(opts); err != nil {
				return err
			}
			w := cmd.OutOrStdout()
			for _, path := range args {
				if err := runBottomup(w, path, opts, depth); err != nil {
					return err
				}
			}
			return nil
		},
	}
	cmd.Flags().IntVarP(&depth, "depth", "d", 3, "number of frames from the leaf to group by")
	return cmd
}

func runBottomup(w io.Writer, path string, opts *sharedOpts, depth int) error {
	_, filtered, err := getMarkerStacks(path, opts)
	if err != nil {
		return err
	}
	mt := sumFiltered(filtered)

	chainCounts := make(map[string]int)
	for _, s := range filtered {
		tail := s.Frames
		if len(s.Frames) >= depth {
			tail = s.Frames[len(s.Frames)-depth:]
		}
		chainCounts[strings.Join(tail, " -> ")] += s.Count
	}

	fmt.Fprintf(w, "=== %s: bottom-up depth=%d (%s samples) ===\n", ShortName(path), depth, Commify(mt))
	printTopN(w, chainCounts, opts.topN, mt)
	fmt.Fprintln(w)
	return nil
}

// -- categorize --------------------------------------------------------------

func newCategorizeCmd(opts *sharedOpts) *cobra.Command {
	var catFile string
	cmd := &cobra.Command{
		Use:   "categorize <files...>",
		Short: "Categorize leaf frames into buckets defined in a categories file",
		Args:  cobra.MinimumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if err := requireMarker(opts); err != nil {
				return err
			}
			cats, err := LoadCategories(catFile)
			if err != nil {
				return err
			}
			w := cmd.OutOrStdout()
			for _, path := range args {
				if err := runCategorize(w, path, opts, cats); err != nil {
					return err
				}
			}
			return nil
		},
	}
	cmd.Flags().StringVarP(&catFile, "categories", "c", "", "path to categories file")
	_ = cmd.MarkFlagRequired("categories")
	return cmd
}

func runCategorize(w io.Writer, path string, opts *sharedOpts, cats []Category) error {
	_, filtered, err := getMarkerStacks(path, opts)
	if err != nil {
		return err
	}
	mt := sumFiltered(filtered)

	leafCounts := make(map[string]int)
	for _, s := range filtered {
		leafCounts[s.Frames[len(s.Frames)-1]] += s.Count
	}

	catTotals := make(map[string]int)
	categorized := 0
	for _, cat := range cats {
		total := 0
		for leaf, cnt := range leafCounts {
			if containsAny(leaf, cat.Keywords) {
				total += cnt
			}
		}
		if total > 0 {
			catTotals[cat.Name] = total
			categorized += total
		}
	}
	uncategorized := mt - categorized

	fmt.Fprintf(w, "=== %s (%s samples) ===\n", ShortName(path), Commify(mt))
	for _, e := range sortedEntries(catTotals) {
		fmt.Fprintf(w, "  %10s (%s)  %s\n", Commify(e.count), FmtPct(e.count, mt), e.key)
	}
	fmt.Fprintf(w, "  %10s (%s)  (uncategorized)\n", Commify(uncategorized), FmtPct(uncategorized, mt))
	fmt.Fprintln(w)
	return nil
}
