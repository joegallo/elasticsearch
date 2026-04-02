import textwrap

import pytest
from click.testing import CliRunner

from main import apply_stack_filters, cli, filter_stacks, fmt_pct, load_categories, parse_file, short_name

# ---------------------------------------------------------------------------
# Test data
# ---------------------------------------------------------------------------

SAMPLE_STACKS = textwrap.dedent("""\
    a;b;c;X.target_[j];d;e;leaf1_[j] 100
    a;b;X.target_[j];d;f;leaf2_[i] 50
    a;X.target_[j];g;leaf3_[k] 30
    a;b;c;other_[j] 200
    a;b;vtable stub 40
    a;X.target_[j];b;itable stub 10
""")

CATEGORIES = textwrap.dedent("""\
    # Leaves
    leaf1
    leaf2

    # Stubs
    vtable stub
    itable stub
""")


@pytest.fixture
def sample_file(tmp_path):
    p = tmp_path / "sample.txt"
    p.write_text(SAMPLE_STACKS)
    return str(p)


@pytest.fixture
def categories_file(tmp_path):
    p = tmp_path / "cats.txt"
    p.write_text(CATEGORIES)
    return str(p)


@pytest.fixture
def runner():
    return CliRunner()


# ---------------------------------------------------------------------------
# Unit tests for helpers
# ---------------------------------------------------------------------------


class TestParseFile:
    def test_parses_stacks(self, sample_file):
        stacks = list(parse_file(sample_file))
        assert len(stacks) == 6
        frames, count = stacks[0]
        assert frames == ["a", "b", "c", "X.target_[j]", "d", "e", "leaf1_[j]"]
        assert count == 100

    def test_skips_blank_lines(self, tmp_path):
        p = tmp_path / "blanks.txt"
        p.write_text("a;b 10\n\n\nc;d 20\n")
        assert len(list(parse_file(str(p)))) == 2

    def test_skips_malformed_lines(self, tmp_path):
        p = tmp_path / "bad.txt"
        p.write_text("no_count_here\na;b notanumber\na;b 5\n")
        stacks = list(parse_file(str(p)))
        assert len(stacks) == 1
        assert stacks[0][1] == 5


class TestFilterStacks:
    def test_filters_by_marker(self, sample_file):
        all_stacks = list(parse_file(sample_file))
        filtered = list(filter_stacks(all_stacks, "X.target"))
        assert len(filtered) == 4  # 3 with X.target + 1 itable stub line
        total = sum(c for _, c, _ in filtered)
        assert total == 100 + 50 + 30 + 10

    def test_marker_index_is_correct(self, sample_file):
        all_stacks = list(parse_file(sample_file))
        filtered = list(filter_stacks(all_stacks, "X.target"))
        # First stack: a;b;c;X.target_[j];d;e;leaf1_[j] -> index 3
        assert filtered[0][2] == 3
        # Second stack: a;b;X.target_[j];d;f;leaf2_[i] -> index 2
        assert filtered[1][2] == 2
        # Third stack: a;X.target_[j];g;leaf3_[k] -> index 1
        assert filtered[2][2] == 1

    def test_no_matches(self, sample_file):
        all_stacks = list(parse_file(sample_file))
        filtered = list(filter_stacks(all_stacks, "NoSuchMethod"))
        assert filtered == []


class TestApplyStackFilters:
    def _marker_stacks(self, sample_file):
        all_stacks = list(parse_file(sample_file))
        return list(filter_stacks(all_stacks, "X.target"))

    def test_filter_keeps_matching(self, sample_file):
        ms = self._marker_stacks(sample_file)
        result = list(apply_stack_filters(ms, filters=("leaf1",), excludes=()))
        assert len(result) == 1
        assert result[0][0][-1] == "leaf1_[j]"

    def test_filter_ands_multiple(self, sample_file):
        ms = self._marker_stacks(sample_file)
        # Only first stack has both 'leaf1' and 'e' in its frames
        result = list(apply_stack_filters(ms, filters=("leaf1", "e"), excludes=()))
        assert len(result) == 1
        assert result[0][1] == 100

    def test_filter_no_match_returns_empty(self, sample_file):
        ms = self._marker_stacks(sample_file)
        result = list(apply_stack_filters(ms, filters=("NoSuch",), excludes=()))
        assert result == []

    def test_exclude_drops_matching(self, sample_file):
        ms = self._marker_stacks(sample_file)
        # Exclude stacks containing 'leaf1'
        result = list(apply_stack_filters(ms, filters=(), excludes=("leaf1",)))
        assert len(result) == 3
        assert all("leaf1" not in ";".join(f) for f, _, _ in result)

    def test_exclude_ors_multiple(self, sample_file):
        ms = self._marker_stacks(sample_file)
        # Exclude leaf1 or leaf2 -> only leaf3 and itable stub stacks remain
        result = list(apply_stack_filters(ms, filters=(), excludes=("leaf1", "leaf2")))
        assert len(result) == 2

    def test_filter_and_exclude_combined(self, sample_file):
        ms = self._marker_stacks(sample_file)
        # Filter to stacks with 'd', then exclude leaf2
        result = list(apply_stack_filters(ms, filters=("d",), excludes=("leaf2",)))
        assert len(result) == 1
        assert result[0][0][-1] == "leaf1_[j]"

    def test_empty_filters_and_excludes_passes_all(self, sample_file):
        ms = self._marker_stacks(sample_file)
        result = list(apply_stack_filters(ms, filters=(), excludes=()))
        assert len(result) == len(ms)

    def test_preserves_marker_index(self, sample_file):
        ms = self._marker_stacks(sample_file)
        result = list(apply_stack_filters(ms, filters=("leaf3",), excludes=()))
        assert len(result) == 1
        # a;X.target_[j];g;leaf3_[k] -> marker index should be 1
        assert result[0][2] == 1


class TestFmtPct:
    def test_basic(self):
        assert fmt_pct(50, 200) == " 25.0%"

    def test_zero_total(self):
        assert fmt_pct(50, 0) == "  N/A"

    def test_hundred_percent(self):
        assert fmt_pct(100, 100) == "100.0%"


class TestShortName:
    def test_strips_directory_and_extension(self):
        assert short_name("/some/path/my-profile.txt") == "my-profile"


class TestLoadCategories:
    def test_parses_categories(self, categories_file):
        cats = load_categories(categories_file)
        assert cats == {
            "Leaves": ["leaf1", "leaf2"],
            "Stubs": ["vtable stub", "itable stub"],
        }

    def test_empty_file(self, tmp_path):
        p = tmp_path / "empty.txt"
        p.write_text("")
        assert load_categories(str(p)) == {}

    def test_single_category(self, tmp_path):
        p = tmp_path / "single.txt"
        p.write_text("# Only one\nkeyword1\nkeyword2\n")
        cats = load_categories(str(p))
        assert cats == {"Only one": ["keyword1", "keyword2"]}


# ---------------------------------------------------------------------------
# CLI command tests
# ---------------------------------------------------------------------------


class TestSummaryCommand:
    def test_basic(self, runner, sample_file):
        result = runner.invoke(cli, ["summary", "-m", "X.target", sample_file])
        assert result.exit_code == 0
        assert "Total samples:" in result.output
        assert "Marker samples:" in result.output
        # 100 + 50 + 30 + 10 = 190 marker samples out of 430 total
        assert "190" in result.output
        assert "430" in result.output

    def test_shows_first_callees(self, runner, sample_file):
        result = runner.invoke(cli, ["summary", "-m", "X.target", sample_file])
        assert "First callees after marker:" in result.output
        # 'd' is callee from first two stacks (100+50=150), 'g' from third (30), 'b' from fourth (10)
        assert "d" in result.output

    def test_with_filter(self, runner, sample_file):
        result = runner.invoke(cli, ["summary", "-m", "X.target", "-f", "leaf1", sample_file])
        assert result.exit_code == 0
        # Only the leaf1 stack (100 samples) should remain as marker samples
        assert "100" in result.output

    def test_with_exclude(self, runner, sample_file):
        result = runner.invoke(cli, ["summary", "-m", "X.target", "-x", "leaf1", sample_file])
        assert result.exit_code == 0
        # Marker samples = 50 + 30 + 10 = 90 (leaf1 stack excluded)
        assert "90" in result.output

    def test_marker_required(self, runner, sample_file):
        result = runner.invoke(cli, ["summary", sample_file])
        assert result.exit_code != 0


class TestLeavesCommand:
    def test_basic(self, runner, sample_file):
        result = runner.invoke(cli, ["leaves", "-m", "X.target", sample_file])
        assert result.exit_code == 0
        assert "leaf1_[j]" in result.output
        assert "leaf2_[i]" in result.output
        assert "leaf3_[k]" in result.output

    def test_with_filter(self, runner, sample_file):
        result = runner.invoke(cli, ["leaves", "-m", "X.target", "-f", "leaf2", sample_file])
        assert result.exit_code == 0
        assert "leaf2_[i]" in result.output
        assert "leaf1_[j]" not in result.output
        assert "leaf3_[k]" not in result.output

    def test_with_exclude(self, runner, sample_file):
        result = runner.invoke(cli, ["leaves", "-m", "X.target", "-x", "leaf1", "-x", "leaf2", sample_file])
        assert result.exit_code == 0
        # Only leaf3 and itable stub stacks remain
        assert "leaf3_[k]" in result.output
        assert "leaf1_[j]" not in result.output
        assert "leaf2_[i]" not in result.output

    def test_top_limits_output(self, runner, sample_file):
        result = runner.invoke(cli, ["leaves", "-m", "X.target", "-n", "1", sample_file])
        assert result.exit_code == 0
        assert "leaf1_[j]" in result.output
        # leaf2 and leaf3 should be cut off
        assert "leaf2_[i]" not in result.output


class TestCallersCommand:
    def test_with_marker(self, runner, sample_file):
        result = runner.invoke(cli, ["callers", "-t", "leaf1", "-m", "X.target", sample_file])
        assert result.exit_code == 0
        assert "e" in result.output

    def test_without_marker(self, runner, sample_file):
        result = runner.invoke(cli, ["callers", "-t", "leaf1", sample_file])
        assert result.exit_code == 0
        assert "e" in result.output

    def test_with_filter(self, runner, sample_file):
        # Filter to stacks with 'leaf1', then look for callers of leaf1
        result = runner.invoke(cli, ["callers", "-t", "leaf1", "-m", "X.target", "-f", "leaf1", sample_file])
        assert result.exit_code == 0
        assert "e" in result.output

    def test_with_exclude(self, runner, sample_file):
        # Exclude leaf1 stacks; callers of 'd' should only come from the leaf2 stack
        result = runner.invoke(cli, ["callers", "-t", "d", "-m", "X.target", "-x", "leaf1", sample_file])
        assert result.exit_code == 0
        assert "X.target" in result.output

    def test_target_required(self, runner, sample_file):
        result = runner.invoke(cli, ["callers", "-m", "X.target", sample_file])
        assert result.exit_code != 0


class TestCalleesCommand:
    def test_basic(self, runner, sample_file):
        result = runner.invoke(cli, ["callees", "-t", "X.target", sample_file])
        assert result.exit_code == 0
        # X.target calls d (100+50), g (30), b (10)
        assert "d" in result.output
        assert "g" in result.output

    def test_with_filter(self, runner, sample_file):
        # Filter to leaf3 stacks, callees of X.target should only be 'g'
        result = runner.invoke(cli, ["callees", "-t", "X.target", "-m", "X.target", "-f", "leaf3", sample_file])
        assert result.exit_code == 0
        assert "g" in result.output

    def test_without_marker(self, runner, sample_file):
        result = runner.invoke(cli, ["callees", "-t", "X.target", sample_file])
        assert result.exit_code == 0


class TestMegamorphicCommand:
    def test_finds_vtable_stubs(self, runner, sample_file):
        result = runner.invoke(cli, ["megamorphic", "-m", "X.target", sample_file])
        assert result.exit_code == 0
        # a;X.target_[j];b;itable stub 10 -> caller is 'b'
        assert "b" in result.output

    def test_no_stubs(self, runner, tmp_path):
        p = tmp_path / "nostubs.txt"
        p.write_text("a;MARKER;b;c 100\n")
        result = runner.invoke(cli, ["megamorphic", "-m", "MARKER", str(p)])
        assert result.exit_code == 0
        assert "0 samples" in result.output


class TestSubtreesCommand:
    def test_depth_1(self, runner, sample_file):
        result = runner.invoke(cli, ["subtrees", "-m", "X.target", "-d", "1", sample_file])
        assert result.exit_code == 0
        # d appears as first callee in 2 stacks (150 samples)
        assert "d" in result.output

    def test_depth_2(self, runner, sample_file):
        result = runner.invoke(cli, ["subtrees", "-m", "X.target", "-d", "2", sample_file])
        assert result.exit_code == 0
        assert "d -> e" in result.output or "d -> f" in result.output


class TestBottomupCommand:
    def test_aggregates_common_tails(self, runner, tmp_path):
        # Three stacks that share the same x -> y -> z tail
        p = tmp_path / "tails.txt"
        p.write_text("MARKER;a;x;y;z 50\nMARKER;a;b;x;y;z 25\nMARKER;a;b;c;x;y;z 12\n")
        result = runner.invoke(cli, ["bottomup", "-m", "MARKER", "-d", "3", str(p)])
        assert result.exit_code == 0
        assert "x -> y -> z" in result.output
        assert "87" in result.output  # 50 + 25 + 12

    def test_short_stacks(self, runner, tmp_path):
        p = tmp_path / "short.txt"
        p.write_text("MARKER;a 10\n")
        result = runner.invoke(cli, ["bottomup", "-m", "MARKER", "-d", "3", str(p)])
        assert result.exit_code == 0
        # Stack is shorter than depth, should still appear
        assert "MARKER;a" in result.output or "a" in result.output


class TestCategorizeCommand:
    def test_basic(self, runner, sample_file, categories_file):
        result = runner.invoke(cli, ["categorize", "-m", "X.target", "-c", categories_file, sample_file])
        assert result.exit_code == 0
        assert "Leaves" in result.output
        assert "Stubs" in result.output
        assert "(uncategorized)" in result.output

    def test_counts_correct(self, runner, sample_file, categories_file):
        result = runner.invoke(cli, ["categorize", "-m", "X.target", "-c", categories_file, sample_file])
        # leaf1 (100) + leaf2 (50) = 150 in Leaves category
        assert "150" in result.output
        # itable stub (10) in Stubs category
        assert "10" in result.output

    def test_categories_file_required(self, runner, sample_file):
        result = runner.invoke(cli, ["categorize", "-m", "X.target", sample_file])
        assert result.exit_code != 0


# ---------------------------------------------------------------------------
# Edge cases
# ---------------------------------------------------------------------------


class TestEdgeCases:
    def test_empty_file(self, runner, tmp_path):
        p = tmp_path / "empty.txt"
        p.write_text("")
        result = runner.invoke(cli, ["summary", "-m", "anything", str(p)])
        assert result.exit_code == 0
        assert "0" in result.output

    def test_single_frame_stack(self, runner, tmp_path):
        p = tmp_path / "single.txt"
        p.write_text("MARKER_[j] 42\n")
        result = runner.invoke(cli, ["leaves", "-m", "MARKER", str(p)])
        assert result.exit_code == 0
        assert "MARKER_[j]" in result.output
        assert "42" in result.output

    def test_multiple_files(self, runner, tmp_path):
        p1 = tmp_path / "one.txt"
        p1.write_text("a;MARKER;b 10\n")
        p2 = tmp_path / "two.txt"
        p2.write_text("a;MARKER;c 20\n")
        result = runner.invoke(cli, ["summary", "-m", "MARKER", str(p1), str(p2)])
        assert result.exit_code == 0
        assert "one" in result.output
        assert "two" in result.output
