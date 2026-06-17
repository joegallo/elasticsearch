package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestResolveInput(t *testing.T) {
	t.Run("non-jfr passes through unchanged", func(t *testing.T) {
		f := writeTemp(t, "profile.txt", sampleStacks)
		got, err := resolveInput(f)
		if err != nil {
			t.Fatal(err)
		}
		if got != f {
			t.Errorf("got %q, want %q", got, f)
		}
	})
}

func TestConvertJFR(t *testing.T) {
	t.Run("cache hit skips conversion", func(t *testing.T) {
		jfrPath := writeTemp(t, "profile.jfr", "fake jfr content")
		cacheRoot := t.TempDir()

		// Pre-populate the cache so convertJFR finds a hit without invoking jfrconv.
		info, err := os.Stat(jfrPath)
		if err != nil {
			t.Fatal(err)
		}
		cacheKey := fmt.Sprintf("profile.jfr_%d_%d", info.ModTime().Unix(), info.Size())
		cacheDir := filepath.Join(cacheRoot, cacheKey)
		if err := os.MkdirAll(cacheDir, 0o755); err != nil {
			t.Fatal(err)
		}
		cachePath := filepath.Join(cacheDir, "profile.txt")
		if err := os.WriteFile(cachePath, []byte(sampleStacks), 0600); err != nil {
			t.Fatal(err)
		}

		got, err := convertJFR(jfrPath, cacheRoot)
		if err != nil {
			t.Fatal(err)
		}
		if got != cachePath {
			t.Errorf("got %q, want %q", got, cachePath)
		}
		// ShortName of the cache path should give the original file stem.
		if name := ShortName(got); name != "profile" {
			t.Errorf("ShortName = %q, want %q", name, "profile")
		}
	})

	t.Run("jfrconv not in PATH returns helpful error", func(t *testing.T) {
		jfrPath := writeTemp(t, "profile.jfr", "fake jfr content")

		origPath := os.Getenv("PATH")
		os.Setenv("PATH", "/nonexistent")
		defer os.Setenv("PATH", origPath)

		_, err := convertJFR(jfrPath, t.TempDir())
		if err == nil {
			t.Fatal("expected error when jfrconv not in PATH")
		}
		if !strings.Contains(err.Error(), "jfrconv") {
			t.Errorf("error should mention jfrconv, got: %v", err)
		}
	})
}
