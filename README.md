# profile-analyzer

A CLI toolkit for analyzing JFR (Java Flight Recorder) CPU profiling snapshots in folded-stack format.

## What it does

When profiling a Java application with JFR, you can export CPU samples as folded stack traces — one stack per line, with a sample count at the end:

```
frame1;frame2;...;leafFrame 42
```

This tool provides a set of commands for slicing and aggregating those stacks to answer questions like:

- Where is CPU time going under a specific method?
- What are the hottest leaf frames (where CPU actually burns)?
- Who calls a particular method, and what does it call?
- Which call sites are megamorphic (vtable/itable dispatch)?
- What are the heaviest call chains when viewed bottom-up from the leaf?

All commands support a `-m`/`--marker` option to filter stacks to only those containing a specific method (e.g. `IndexShard.prepareIndex`), so you can focus analysis on a particular code path.

## Setup

Requires Python 3.12+ and [uv](https://docs.astral.sh/uv/).

```sh
uv sync
```

## Usage

```sh
uv run main.py <command> [options] <files...>
```

### Commands

#### `summary`

High-level overview: total vs marker samples, and the first callees after the marker method.

```sh
uv run main.py summary -m IndexShard.prepareIndex *.txt
```

#### `leaves`

Top leaf frames (where CPU is actually spent) under the marker. These are the innermost frames on the stack — the methods that were executing when the sample was taken.

```sh
uv run main.py leaves -m IndexShard.prepareIndex *.txt
```

#### `callers`

Find who calls a target frame. Useful for answering "why is this method hot — who's calling it?"

```sh
uv run main.py callers -t 'MapN.probe' -m IndexShard.prepareIndex *.txt
```

The `-m` marker is optional for this command — omit it to search across all stacks.

#### `callees`

Find what a target frame calls. The inverse of `callers`.

```sh
uv run main.py callees -t 'DocumentParser.parseValue' -m IndexShard.prepareIndex *.txt
```

The `-m` marker is optional for this command.

#### `megamorphic`

Find megamorphic call sites — methods whose virtual calls go through vtable/itable stubs because the JVM sees too many receiver types to devirtualize.

```sh
uv run main.py megamorphic -m IndexShard.prepareIndex *.txt
```

#### `subtrees`

Top sub-paths of a given depth below the marker (top-down view). Shows the first N callees after the marker method.

```sh
uv run main.py subtrees -m IndexShard.prepareIndex --depth 2 *.txt
```

#### `bottomup`

Group stacks by their last N frames from the leaf. This aggregates call chains that arrive at the same hot path from different callers. For example, with `--depth 3`, the stacks `a;x;y;z 50` and `a;b;x;y;z 25` both contribute to `x -> y -> z` with 75 total samples.

```sh
uv run main.py bottomup -m IndexShard.prepareIndex --depth 3 *.txt
```

#### `categorize`

Bucket leaf frames into user-defined categories using a categories file.

```sh
uv run main.py categorize -m IndexShard.prepareIndex -c categories.txt *.txt
```

### Common options

| Option | Description |
|--------|-------------|
| `-m`, `--marker` | Filter to stacks containing this method substring (required for most commands, optional for `callers`/`callees`) |
| `-n`, `--top` | Number of results to show (default: 20) |
| `-d`, `--depth` | Depth for `subtrees` (default: 2) and `bottomup` (default: 3) |

## Categories file format

The `categorize` command uses a simple text file to define buckets. Each category starts with a `# Name` header, followed by keyword lines. A leaf frame matches a category if it contains any of that category's keywords.

```
# JSON parsing
jackson
xcontent
ESUTF8Stream

# HashMap operations
HashMap.put
HashMap.getNode
HashMap.resize
```

See `categories.txt` for a full example targeting Elasticsearch indexing paths.

## Input file format

Folded stack traces, one per line:

```
package/Class.method_[j];package/Other.method_[i];leaf_[j] 42
```

The suffix annotations (`_[j]`, `_[i]`, `_[k]`, `_[0]`, `_[1]`) indicate frame type:
- `_[j]` — JIT-compiled Java
- `_[i]` — Interpreted Java
- `_[k]` — Kernel
- `_[0]`, `_[1]` — Other (native, etc.)

These files can be generated from `.jfr` recordings using tools like [async-profiler](https://github.com/async-profiler/async-profiler)'s converter.
