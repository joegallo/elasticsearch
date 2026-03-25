"""Toolkit for analyzing JFR folded-stack CPU profiles.

Input files are folded stack traces (one per line):
    frame1;frame2;...;leafFrame sampleCount

Usage examples:

    # Summary: where does CPU go under a marker method?
    uv run main.py summary -m IndexShard.prepareIndex *.txt

    # Top leaf frames under a marker
    uv run main.py leaves -m IndexShard.prepareIndex *.txt

    # Who calls a specific frame? (walk up the stack)
    uv run main.py callers -t 'MapN.probe' -m IndexShard.prepareIndex *.txt

    # Megamorphic call sites (callers of vtable/itable stubs)
    uv run main.py megamorphic -m IndexShard.prepareIndex *.txt

    # Categorize leaf frames into user-defined buckets
    uv run main.py categorize -m IndexShard.prepareIndex -c categories.txt *.txt

    # Show the first N distinct callees after the marker method
    uv run main.py subtrees -m IndexShard.prepareIndex --depth 2 *.txt

    # Bottom-up view: group by last N frames from the leaf
    uv run main.py bottomup -m IndexShard.prepareIndex --depth 3 *.txt
"""

from collections import defaultdict
from pathlib import Path

import click


def parse_file(path):
    """Yield (frames_list, sample_count) for each line in a folded-stack file."""
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            parts = line.rsplit(" ", 1)
            if len(parts) != 2:
                continue
            stack_str, count_str = parts
            try:
                count = int(count_str)
            except ValueError:
                continue
            yield stack_str.split(";"), count


def filter_stacks(stacks, marker):
    """Yield (frames, count, marker_index) for stacks containing the marker."""
    for frames, count in stacks:
        for i, frame in enumerate(frames):
            if marker in frame:
                yield frames, count, i
                break


def fmt_pct(count, total):
    return f"{100 * count / total:5.1f}%" if total else "  N/A"


def short_name(path):
    return Path(path).stem


# -- Common click options ----------------------------------------------------

_files_arg = click.argument("files", nargs=-1, required=True, type=click.Path(exists=True))
_top_option = click.option("-n", "--top", default=20, show_default=True, help="Number of results to show.")


def marker_option(required=True):
    return click.option("-m", "--marker", required=required, help="Filter to stacks containing this method substring.")


# -- Commands ----------------------------------------------------------------


@click.group(help=__doc__)
def cli():
    pass


@cli.command()
@_files_arg
@marker_option()
@_top_option
def summary(files, marker, top):
    """High-level summary: total samples, marker samples, top-level callees."""
    for path in files:
        all_stacks = list(parse_file(path))
        total = sum(c for _, c in all_stacks)
        marker_stacks = list(filter_stacks(all_stacks, marker))
        marker_total = sum(c for _, c, _ in marker_stacks)

        callee_counts = defaultdict(int)
        for frames, count, mi in marker_stacks:
            if mi + 1 < len(frames):
                callee_counts[frames[mi + 1]] += count

        click.echo(f"=== {short_name(path)} ===")
        click.echo(f"Total samples:  {total:>10,}")
        click.echo(f"Marker samples: {marker_total:>10,} ({fmt_pct(marker_total, total)})")
        click.echo()
        if callee_counts:
            click.echo("First callees after marker:")
            for callee, cnt in sorted(callee_counts.items(), key=lambda x: -x[1]):
                click.echo(f"  {cnt:>10,} ({fmt_pct(cnt, marker_total)})  {callee}")
        click.echo()


@cli.command()
@_files_arg
@marker_option()
@_top_option
def leaves(files, marker, top):
    """Top leaf frames (where CPU is actually spent) under the marker."""
    for path in files:
        all_stacks = list(parse_file(path))
        marker_stacks = list(filter_stacks(all_stacks, marker))
        marker_total = sum(c for _, c, _ in marker_stacks)

        leaf_counts = defaultdict(int)
        for frames, count, _ in marker_stacks:
            leaf_counts[frames[-1]] += count

        click.echo(f"=== {short_name(path)} ({marker_total:,} samples) ===")
        for leaf, cnt in sorted(leaf_counts.items(), key=lambda x: -x[1])[:top]:
            click.echo(f"  {cnt:>10,} ({fmt_pct(cnt, marker_total)})  {leaf}")
        click.echo()


@cli.command()
@_files_arg
@marker_option(required=False)
@_top_option
@click.option("-t", "--target", required=True, help="Target frame substring to find callers of.")
def callers(files, marker, top, target):
    """Find who calls a target frame (within stacks that contain the marker)."""
    for path in files:
        all_stacks = list(parse_file(path))
        if marker:
            stacks = list(filter_stacks(all_stacks, marker))
            total = sum(c for _, c, _ in stacks)
            stack_iter = [(frames, count) for frames, count, _ in stacks]
        else:
            stack_iter = list(parse_file(path))
            total = sum(c for _, c in stack_iter)

        caller_counts = defaultdict(int)
        for frames, count in stack_iter:
            for i, frame in enumerate(frames):
                if target in frame and i > 0:
                    caller_counts[frames[i - 1]] += count
                    break

        click.echo(f"=== {short_name(path)}: callers of '{target}' ({total:,} samples) ===")
        for caller, cnt in sorted(caller_counts.items(), key=lambda x: -x[1])[:top]:
            click.echo(f"  {cnt:>10,} ({fmt_pct(cnt, total)})  {caller}")
        click.echo()


@cli.command()
@_files_arg
@marker_option(required=False)
@_top_option
@click.option("-t", "--target", required=True, help="Target frame substring to find callees of.")
def callees(files, marker, top, target):
    """Find what a target frame calls (within stacks that contain the marker)."""
    for path in files:
        all_stacks = list(parse_file(path))
        if marker:
            stacks = list(filter_stacks(all_stacks, marker))
            total = sum(c for _, c, _ in stacks)
            stack_iter = [(frames, count) for frames, count, _ in stacks]
        else:
            stack_iter = list(parse_file(path))
            total = sum(c for _, c in stack_iter)

        callee_counts = defaultdict(int)
        for frames, count in stack_iter:
            for i, frame in enumerate(frames):
                if target in frame and i + 1 < len(frames):
                    callee_counts[frames[i + 1]] += count
                    break

        click.echo(f"=== {short_name(path)}: callees of '{target}' ({total:,} samples) ===")
        for callee, cnt in sorted(callee_counts.items(), key=lambda x: -x[1])[:top]:
            click.echo(f"  {cnt:>10,} ({fmt_pct(cnt, total)})  {callee}")
        click.echo()


@cli.command()
@_files_arg
@marker_option()
@_top_option
def megamorphic(files, marker, top):
    """Find megamorphic call sites (callers of vtable/itable stubs)."""
    for path in files:
        all_stacks = list(parse_file(path))
        marker_stacks = list(filter_stacks(all_stacks, marker))
        marker_total = sum(c for _, c, _ in marker_stacks)

        caller_counts = defaultdict(int)
        for frames, count, _ in marker_stacks:
            leaf = frames[-1]
            if "vtable stub" in leaf or "itable stub" in leaf:
                if len(frames) >= 2:
                    caller_counts[frames[-2]] += count

        click.echo(f"=== {short_name(path)}: megamorphic sites ({marker_total:,} samples) ===")
        for caller, cnt in sorted(caller_counts.items(), key=lambda x: -x[1])[:top]:
            click.echo(f"  {cnt:>10,} ({fmt_pct(cnt, marker_total)})  {caller}")
        click.echo()


@cli.command()
@_files_arg
@marker_option()
@_top_option
@click.option("-d", "--depth", default=2, show_default=True, help="Depth of sub-path.")
def subtrees(files, marker, top, depth):
    """Show top sub-paths of a given depth below the marker."""
    for path in files:
        all_stacks = list(parse_file(path))
        marker_stacks = list(filter_stacks(all_stacks, marker))
        marker_total = sum(c for _, c, _ in marker_stacks)

        subtree_counts = defaultdict(int)
        for frames, count, mi in marker_stacks:
            after = frames[mi + 1 :]
            if len(after) >= depth:
                key = " -> ".join(after[:depth])
                subtree_counts[key] += count

        click.echo(f"=== {short_name(path)}: subtrees depth={depth} ({marker_total:,} samples) ===")
        for key, cnt in sorted(subtree_counts.items(), key=lambda x: -x[1])[:top]:
            click.echo(f"  {cnt:>10,} ({fmt_pct(cnt, marker_total)})  {key}")
        click.echo()


@cli.command()
@_files_arg
@marker_option()
@_top_option
@click.option("-d", "--depth", default=3, show_default=True, help="Number of frames from the leaf to group by.")
def bottomup(files, marker, top, depth):
    """Bottom-up view: group stacks by their last N frames (leaf chain).

    This aggregates call chains that arrive at the same hot path from different
    callers.  For example, with --depth 3 and stacks:

    \b
        a;x;y;z 50
        a;b;x;y;z 25

    both contribute to the chain "x -> y -> z" with 75 total samples.
    """
    for path in files:
        all_stacks = list(parse_file(path))
        marker_stacks = list(filter_stacks(all_stacks, marker))
        marker_total = sum(c for _, c, _ in marker_stacks)

        chain_counts = defaultdict(int)
        for frames, count, _ in marker_stacks:
            tail = frames[-depth:] if len(frames) >= depth else frames
            key = " -> ".join(tail)
            chain_counts[key] += count

        click.echo(f"=== {short_name(path)}: bottom-up depth={depth} ({marker_total:,} samples) ===")
        for key, cnt in sorted(chain_counts.items(), key=lambda x: -x[1])[:top]:
            click.echo(f"  {cnt:>10,} ({fmt_pct(cnt, marker_total)})  {key}")
        click.echo()


def load_categories(cat_file):
    """Load a categories file. Format: one category per block, header line then keywords.

    Example:
        # JSON parsing
        jackson
        xcontent
        ESUTF8Stream

        # HashMap operations
        HashMap.put
        HashMap.getNode
        HashMap.resize
    """
    categories = {}
    current_cat = None
    current_keywords = []

    with open(cat_file) as f:
        for line in f:
            line = line.rstrip("\n")
            if line.startswith("#"):
                if current_cat and current_keywords:
                    categories[current_cat] = current_keywords
                current_cat = line[1:].strip()
                current_keywords = []
            elif line.strip():
                current_keywords.append(line.strip())

    if current_cat and current_keywords:
        categories[current_cat] = current_keywords

    return categories


@cli.command()
@_files_arg
@marker_option()
@_top_option
@click.option("-c", "--categories", required=True, type=click.Path(exists=True), help="Path to categories file.")
def categorize(files, marker, top, categories):
    """Categorize leaf frames into buckets defined in a categories file."""
    cats = load_categories(categories)

    for path in files:
        all_stacks = list(parse_file(path))
        marker_stacks = list(filter_stacks(all_stacks, marker))
        marker_total = sum(c for _, c, _ in marker_stacks)

        leaf_counts = defaultdict(int)
        for frames, count, _ in marker_stacks:
            leaf_counts[frames[-1]] += count

        cat_totals = {}
        categorized = 0
        for cat, keywords in cats.items():
            cat_total = 0
            for leaf, cnt in leaf_counts.items():
                if any(kw in leaf for kw in keywords):
                    cat_total += cnt
            cat_totals[cat] = cat_total
            categorized += cat_total

        uncategorized = marker_total - categorized

        click.echo(f"=== {short_name(path)} ({marker_total:,} samples) ===")
        for cat, cnt in sorted(cat_totals.items(), key=lambda x: -x[1]):
            if cnt > 0:
                click.echo(f"  {cnt:>10,} ({fmt_pct(cnt, marker_total)})  {cat}")
        click.echo(f"  {uncategorized:>10,} ({fmt_pct(uncategorized, marker_total)})  (uncategorized)")
        click.echo()


if __name__ == "__main__":
    cli()
