package main

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func writeTemp(t *testing.T, name, content string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), name)
	if err := os.WriteFile(path, []byte(content), 0600); err != nil {
		t.Fatal(err)
	}
	return path
}

func runCLI(t *testing.T, args ...string) (string, error) {
	t.Helper()
	buf := &bytes.Buffer{}
	root := newRootCmd()
	root.SetOut(buf)
	root.SetErr(buf)
	root.SetArgs(args)
	err := root.Execute()
	return buf.String(), err
}

// -- summary -----------------------------------------------------------------

func TestSummaryCommand(t *testing.T) {
	f := writeTemp(t, "sample.txt", sampleStacks)

	t.Run("basic", func(t *testing.T) {
		out, err := runCLI(t, "summary", "-m", "X.target", f)
		if err != nil {
			t.Fatal(err)
		}
		for _, want := range []string{"Total samples:", "Marker samples:", "190", "430"} {
			if !strings.Contains(out, want) {
				t.Errorf("missing %q in output", want)
			}
		}
	})

	t.Run("shows first callees", func(t *testing.T) {
		out, err := runCLI(t, "summary", "-m", "X.target", f)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(out, "First callees after marker:") {
			t.Error("missing callee section header")
		}
		if !strings.Contains(out, "d") {
			t.Error("missing callee 'd'")
		}
	})

	t.Run("with filter", func(t *testing.T) {
		out, err := runCLI(t, "summary", "-m", "X.target", "-f", "leaf1", f)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(out, "100") {
			t.Error("missing count 100")
		}
	})

	t.Run("with exclude", func(t *testing.T) {
		out, err := runCLI(t, "summary", "-m", "X.target", "-x", "leaf1", f)
		if err != nil {
			t.Fatal(err)
		}
		// Marker samples = 50 + 30 + 10 = 90
		if !strings.Contains(out, "90") {
			t.Error("missing count 90")
		}
	})

	t.Run("multiple markers", func(t *testing.T) {
		out, err := runCLI(t, "summary", "-m", "X.target", "-m", "other", f)
		if err != nil {
			t.Fatal(err)
		}
		// 190 + 200 = 390
		if !strings.Contains(out, "390") {
			t.Error("missing count 390")
		}
	})

	t.Run("marker required", func(t *testing.T) {
		_, err := runCLI(t, "summary", f)
		if err == nil {
			t.Error("expected error when --marker is missing")
		}
	})
}

// -- leaves ------------------------------------------------------------------

func TestLeavesCommand(t *testing.T) {
	f := writeTemp(t, "sample.txt", sampleStacks)

	t.Run("basic", func(t *testing.T) {
		out, err := runCLI(t, "leaves", "-m", "X.target", f)
		if err != nil {
			t.Fatal(err)
		}
		for _, leaf := range []string{"leaf1_[j]", "leaf2_[i]", "leaf3_[k]"} {
			if !strings.Contains(out, leaf) {
				t.Errorf("missing leaf %q", leaf)
			}
		}
	})

	t.Run("with filter", func(t *testing.T) {
		out, err := runCLI(t, "leaves", "-m", "X.target", "-f", "leaf2", f)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(out, "leaf2_[i]") {
			t.Error("missing leaf2")
		}
		if strings.Contains(out, "leaf1_[j]") {
			t.Error("unexpected leaf1")
		}
		if strings.Contains(out, "leaf3_[k]") {
			t.Error("unexpected leaf3")
		}
	})

	t.Run("with exclude", func(t *testing.T) {
		out, err := runCLI(t, "leaves", "-m", "X.target", "-x", "leaf1", "-x", "leaf2", f)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(out, "leaf3_[k]") {
			t.Error("missing leaf3")
		}
		if strings.Contains(out, "leaf1_[j]") || strings.Contains(out, "leaf2_[i]") {
			t.Error("unexpected leaf1 or leaf2")
		}
	})

	t.Run("top limits output", func(t *testing.T) {
		out, err := runCLI(t, "leaves", "-m", "X.target", "-n", "1", f)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(out, "leaf1_[j]") {
			t.Error("missing top leaf")
		}
		if strings.Contains(out, "leaf2_[i]") {
			t.Error("leaf2 should be cut off by -n 1")
		}
	})
}

// -- callers -----------------------------------------------------------------

func TestCallersCommand(t *testing.T) {
	f := writeTemp(t, "sample.txt", sampleStacks)

	t.Run("with marker", func(t *testing.T) {
		out, err := runCLI(t, "callers", "-t", "leaf1", "-m", "X.target", f)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(out, "e") {
			t.Error("missing caller 'e'")
		}
	})

	t.Run("without marker", func(t *testing.T) {
		out, err := runCLI(t, "callers", "-t", "leaf1", f)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(out, "e") {
			t.Error("missing caller 'e'")
		}
	})

	t.Run("multiple markers", func(t *testing.T) {
		out, err := runCLI(t, "callers", "-t", "d", "-m", "leaf1", "-m", "leaf2", f)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(out, "X.target") {
			t.Error("missing caller 'X.target'")
		}
	})

	t.Run("with filter", func(t *testing.T) {
		out, err := runCLI(t, "callers", "-t", "leaf1", "-m", "X.target", "-f", "leaf1", f)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(out, "e") {
			t.Error("missing caller 'e'")
		}
	})

	t.Run("with exclude", func(t *testing.T) {
		out, err := runCLI(t, "callers", "-t", "d", "-m", "X.target", "-x", "leaf1", f)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(out, "X.target") {
			t.Error("missing caller 'X.target'")
		}
	})

	t.Run("target required", func(t *testing.T) {
		_, err := runCLI(t, "callers", "-m", "X.target", f)
		if err == nil {
			t.Error("expected error when --target is missing")
		}
	})
}

// -- callees -----------------------------------------------------------------

func TestCalleesCommand(t *testing.T) {
	f := writeTemp(t, "sample.txt", sampleStacks)

	t.Run("basic", func(t *testing.T) {
		out, err := runCLI(t, "callees", "-t", "X.target", f)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(out, "d") || !strings.Contains(out, "g") {
			t.Error("missing callees 'd' and 'g'")
		}
	})

	t.Run("with filter", func(t *testing.T) {
		out, err := runCLI(t, "callees", "-t", "X.target", "-m", "X.target", "-f", "leaf3", f)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(out, "g") {
			t.Error("missing callee 'g'")
		}
	})

	t.Run("without marker", func(t *testing.T) {
		_, err := runCLI(t, "callees", "-t", "X.target", f)
		if err != nil {
			t.Fatal(err)
		}
	})

	t.Run("target required", func(t *testing.T) {
		_, err := runCLI(t, "callees", "-m", "X.target", f)
		if err == nil {
			t.Error("expected error when --target is missing")
		}
	})
}

// -- megamorphic -------------------------------------------------------------

func TestMegamorphicCommand(t *testing.T) {
	f := writeTemp(t, "sample.txt", sampleStacks)

	t.Run("finds itable stubs", func(t *testing.T) {
		out, err := runCLI(t, "megamorphic", "-m", "X.target", f)
		if err != nil {
			t.Fatal(err)
		}
		// a;X.target_[j];b;itable stub 10 -> caller is 'b'
		if !strings.Contains(out, "b") {
			t.Error("missing megamorphic caller 'b'")
		}
	})

	t.Run("no stubs", func(t *testing.T) {
		f2 := writeTemp(t, "nostubs.txt", "a;MARKER;b;c 100\n")
		out, err := runCLI(t, "megamorphic", "-m", "MARKER", f2)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(out, "100 samples") {
			t.Error("expected '100 samples' in header")
		}
	})
}

// -- subtrees ----------------------------------------------------------------

func TestSubtreesCommand(t *testing.T) {
	f := writeTemp(t, "sample.txt", sampleStacks)

	t.Run("depth 1", func(t *testing.T) {
		out, err := runCLI(t, "subtrees", "-m", "X.target", "-d", "1", f)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(out, "d") {
			t.Error("missing subtree 'd'")
		}
	})

	t.Run("depth 2", func(t *testing.T) {
		out, err := runCLI(t, "subtrees", "-m", "X.target", "-d", "2", f)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(out, "d -> e") && !strings.Contains(out, "d -> f") {
			t.Error("missing depth-2 subtrees")
		}
	})
}

// -- bottomup ----------------------------------------------------------------

func TestBottomupCommand(t *testing.T) {
	t.Run("aggregates common tails", func(t *testing.T) {
		f := writeTemp(t, "tails.txt", "MARKER;a;x;y;z 50\nMARKER;a;b;x;y;z 25\nMARKER;a;b;c;x;y;z 12\n")
		out, err := runCLI(t, "bottomup", "-m", "MARKER", "-d", "3", f)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(out, "x -> y -> z") {
			t.Error("missing chain 'x -> y -> z'")
		}
		if !strings.Contains(out, "87") {
			t.Error("missing total 87 (50+25+12)")
		}
	})

	t.Run("short stacks use all frames", func(t *testing.T) {
		f := writeTemp(t, "short.txt", "MARKER;a 10\n")
		out, err := runCLI(t, "bottomup", "-m", "MARKER", "-d", "3", f)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(out, "a") {
			t.Error("short stack frames should appear")
		}
	})
}

// -- categorize --------------------------------------------------------------

func TestCategorizeCommand(t *testing.T) {
	f := writeTemp(t, "sample.txt", sampleStacks)
	cf := writeTemp(t, "cats.txt", sampleCategories)

	t.Run("basic", func(t *testing.T) {
		out, err := runCLI(t, "categorize", "-m", "X.target", "-c", cf, f)
		if err != nil {
			t.Fatal(err)
		}
		for _, want := range []string{"Leaves", "Stubs", "(uncategorized)"} {
			if !strings.Contains(out, want) {
				t.Errorf("missing %q in output", want)
			}
		}
	})

	t.Run("counts correct", func(t *testing.T) {
		out, err := runCLI(t, "categorize", "-m", "X.target", "-c", cf, f)
		if err != nil {
			t.Fatal(err)
		}
		// leaf1 (100) + leaf2 (50) = 150 in Leaves
		if !strings.Contains(out, "150") {
			t.Error("missing Leaves count 150")
		}
		// itable stub (10) in Stubs
		if !strings.Contains(out, "10") {
			t.Error("missing Stubs count 10")
		}
	})

	t.Run("categories file required", func(t *testing.T) {
		_, err := runCLI(t, "categorize", "-m", "X.target", f)
		if err == nil {
			t.Error("expected error when --categories is missing")
		}
	})
}

// -- edge cases --------------------------------------------------------------

func TestEdgeCases(t *testing.T) {
	t.Run("empty file", func(t *testing.T) {
		f := writeTemp(t, "empty.txt", "")
		out, err := runCLI(t, "summary", "-m", "anything", f)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(out, "0") {
			t.Error("expected zeros in output")
		}
	})

	t.Run("single frame stack", func(t *testing.T) {
		f := writeTemp(t, "single.txt", "MARKER_[j] 42\n")
		out, err := runCLI(t, "leaves", "-m", "MARKER", f)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(out, "MARKER_[j]") || !strings.Contains(out, "42") {
			t.Error("missing frame or count in output")
		}
	})

	t.Run("multiple files", func(t *testing.T) {
		f1 := writeTemp(t, "one.txt", "a;MARKER;b 10\n")
		f2 := writeTemp(t, "two.txt", "a;MARKER;c 20\n")
		out, err := runCLI(t, "summary", "-m", "MARKER", f1, f2)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(out, "one") || !strings.Contains(out, "two") {
			t.Error("missing file names in output")
		}
	})
}
