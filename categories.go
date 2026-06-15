package main

import (
	"bufio"
	"io"
	"os"
	"strings"
)

// Category groups a name with a set of keyword substrings.
// A leaf frame belongs to a category if it contains any of its keywords.
type Category struct {
	Name     string
	Keywords []string
}

// LoadCategoriesReader parses a categories file from r.
// "# Category Name" lines introduce a new category; subsequent non-empty lines are keywords.
func LoadCategoriesReader(r io.Reader) []Category {
	var cats []Category
	var cur *Category

	scanner := bufio.NewScanner(r)
	for scanner.Scan() {
		line := scanner.Text()
		if strings.HasPrefix(line, "#") {
			if cur != nil && len(cur.Keywords) > 0 {
				cats = append(cats, *cur)
			}
			cur = &Category{Name: strings.TrimSpace(line[1:])}
		} else if kw := strings.TrimSpace(line); kw != "" && cur != nil {
			cur.Keywords = append(cur.Keywords, kw)
		}
	}
	if cur != nil && len(cur.Keywords) > 0 {
		cats = append(cats, *cur)
	}
	return cats
}

// LoadCategories reads a categories file from disk.
func LoadCategories(path string) ([]Category, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	return LoadCategoriesReader(f), nil
}
