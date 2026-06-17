package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

const jfrCacheRoot = "/tmp/profile-analyzer"

// resolveInput returns the path to a folded-stack file ready for parsing.
// If path ends in .jfr, it is converted via jfrconv and the cache path is returned.
// Otherwise path is returned unchanged.
func resolveInput(path string) (string, error) {
	if !strings.HasSuffix(path, ".jfr") {
		return path, nil
	}
	return convertJFR(path, jfrCacheRoot)
}

// convertJFR converts a .jfr file to folded-stack format using jfrconv,
// caching the result under cacheRoot keyed by filename, mtime, and size.
// The cache file shares the stem of the original file so ShortName works naturally.
func convertJFR(jfrPath, cacheRoot string) (string, error) {
	jfrconv, err := exec.LookPath("jfrconv")
	if err != nil {
		return "", fmt.Errorf("jfrconv not found on $PATH — install async-profiler to process .jfr files")
	}

	info, err := os.Stat(jfrPath)
	if err != nil {
		return "", err
	}

	stem := strings.TrimSuffix(filepath.Base(jfrPath), ".jfr")
	cacheKey := fmt.Sprintf("%s_%d_%d", filepath.Base(jfrPath), info.ModTime().Unix(), info.Size())
	cacheDir := filepath.Join(cacheRoot, cacheKey)
	cachePath := filepath.Join(cacheDir, stem+".txt")

	if _, err := os.Stat(cachePath); err == nil {
		return cachePath, nil
	}

	if err := os.MkdirAll(cacheDir, 0o755); err != nil {
		return "", fmt.Errorf("creating cache directory: %w", err)
	}

	fmt.Fprintf(os.Stderr, "converting %s...\n", filepath.Base(jfrPath))

	cmd := exec.Command(jfrconv, "--cpu", "-o", "collapsed", jfrPath, cachePath)
	cmd.Stderr = os.Stderr
	if err := cmd.Run(); err != nil {
		_ = os.Remove(cachePath)
		return "", fmt.Errorf("jfrconv: %w", err)
	}

	return cachePath, nil
}
