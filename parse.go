package main

import (
	"bufio"
	"io"
	"os"
	"strconv"
	"strings"
)

// Stack is a single entry from a folded-stack file.
type Stack struct {
	Frames []string
	Count  int
}

// ParseReader reads folded-stack lines from r and returns the parsed stacks.
// Blank lines and lines that don't parse cleanly are silently skipped.
func ParseReader(r io.Reader) []Stack {
	var stacks []Stack
	scanner := bufio.NewScanner(r)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}
		idx := strings.LastIndex(line, " ")
		if idx < 0 {
			continue
		}
		count, err := strconv.Atoi(line[idx+1:])
		if err != nil {
			continue
		}
		stacks = append(stacks, Stack{
			Frames: strings.Split(line[:idx], ";"),
			Count:  count,
		})
	}
	return stacks
}

// ParseFile reads a folded-stack file from disk.
func ParseFile(path string) ([]Stack, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	return ParseReader(f), nil
}
