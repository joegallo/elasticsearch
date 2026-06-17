# profile-analyzer

A CLI toolkit for analyzing JFR (Java Flight Recorder) CPU profiling snapshots.

## What it does

All commands accept either `.jfr` files directly or pre-converted folded-stack `.txt` files. When given a `.jfr` file, the tool converts it automatically using [async-profiler](https://github.com/async-profiler/async-profiler)'s `jfrconv` and caches the result in `/tmp/profile-analyzer/` so subsequent runs are instant.

The underlying data format is folded stack traces — one stack per line, with a sample count at the end:

```
frame1;frame2;...;leafFrame 42
```

The tool provides a set of commands for slicing and aggregating those stacks to answer questions like:

- Where is CPU time going under a specific method?
- What are the hottest leaf frames (where CPU actually burns)?
- Who calls a particular method, and what does it call?
- Which call sites are megamorphic (vtable/itable dispatch)?
- What are the heaviest call chains when viewed bottom-up from the leaf?

All commands support a `-m`/`--marker` option to filter stacks to only those containing a specific method (e.g. `IndexShard.prepareIndex`), so you can focus analysis on a particular code path. The `-m` option is repeatable — multiple markers are ORed, so `-m foo -m bar` matches stacks containing either substring.

## Setup

Requires Go 1.21+. To process `.jfr` files directly, `jfrconv` from [async-profiler](https://github.com/async-profiler/async-profiler) must be on your `$PATH`.

```sh
go build -o profile-analyzer .
```

## Usage

```sh
profile-analyzer <command> [options] <files...>
```

Both `.jfr` and `.txt` files are accepted, and can be mixed in the same invocation. When a `.jfr` file is passed, it is converted to folded-stack format via `jfrconv --cpu` and cached under `/tmp/profile-analyzer/` keyed by filename, modification time, and size. Subsequent runs against the same file skip conversion entirely.

### Commands

#### `summary`

High-level overview: total vs marker samples, and the first callees after the marker method.

```sh
profile-analyzer summary -m IndexShard.prepareIndex *.jfr
```

#### `leaves`

Top leaf frames (where CPU is actually spent) under the marker. These are the innermost frames on the stack — the methods that were executing when the sample was taken.

```sh
profile-analyzer leaves -m IndexShard.prepareIndex *.jfr
```

#### `callers`

Find who calls a target frame. Useful for answering "why is this method hot — who's calling it?"

```sh
profile-analyzer callers -t 'MapN.probe' -m IndexShard.prepareIndex *.jfr
```

The `-m` marker is optional for this command — omit it to search across all stacks.

#### `callees`

Find what a target frame calls. The inverse of `callers`.

```sh
profile-analyzer callees -t 'DocumentParser.parseValue' -m IndexShard.prepareIndex *.jfr
```

The `-m` marker is optional for this command.

#### `megamorphic`

Find megamorphic call sites — methods whose virtual calls go through vtable/itable stubs because the JVM sees too many receiver types to devirtualize.

```sh
profile-analyzer megamorphic -m IndexShard.prepareIndex *.jfr
```

#### `subtrees`

Top sub-paths of a given depth below the marker (top-down view). Shows the first N callees after the marker method.

```sh
profile-analyzer subtrees -m IndexShard.prepareIndex --depth 2 *.jfr
```

#### `bottomup`

Group stacks by their last N frames from the leaf. This aggregates call chains that arrive at the same hot path from different callers. For example, with `--depth 3`, the stacks `a;x;y;z 50` and `a;b;x;y;z 25` both contribute to `x -> y -> z` with 75 total samples.

```sh
profile-analyzer bottomup -m IndexShard.prepareIndex --depth 3 *.jfr
```

#### `categorize`

Bucket leaf frames into user-defined categories using a categories file.

```sh
profile-analyzer categorize -m IndexShard.prepareIndex -c categories.txt *.jfr
```

### Common options

| Option | Description |
|--------|-------------|
| `-m`, `--marker` | Filter to stacks containing this method substring (repeatable, ORed; required for most commands, optional for `callers`/`callees`) |
| `-n`, `--top` | Number of results to show (default: 20) |
| `-d`, `--depth` | Depth for `subtrees` (default: 2) and `bottomup` (default: 3) |
| `-f`, `--filter` | Keep only stacks where the full stack contains this substring (repeatable, ANDed) |
| `-x`, `--exclude` | Drop stacks where the full stack contains this substring (repeatable, ORed) |

### Stack filtering

The `-f`/`--filter` and `-x`/`--exclude` options are available on all commands. They apply after the `-m` marker filter, further narrowing which stacks are analyzed. This is useful for isolating specific code paths or removing noise.

Multiple `-f` filters are ANDed (all must match). Multiple `-x` excludes are ORed (any match causes exclusion).

```sh
# HashMap leaf frames, excluding Jackson's DupDetector
profile-analyzer leaves -m IndexShard.prepareIndex -f HashMap -x DupDetector -x HashSet *.jfr

# Bottom-up view only for stacks passing through MappingLookup
profile-analyzer bottomup -m IndexShard.prepareIndex -f MappingLookup -d 4 *.jfr

# Categorize excluding java.time internals
profile-analyzer categorize -m IndexShard.prepareIndex -x java/time -c categories.txt *.jfr
```

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

## Folded-stack format

When passing `.txt` files directly, they should be folded stack traces — one per line:

```
package/Class.method_[j];package/Other.method_[i];leaf_[j] 42
```

The suffix annotations indicate frame type, as defined by the `FRAME_SUFFIX` array in async-profiler's `FlameGraph.java`, indexed by the `TYPE_*` constants in `Frame.java`:

| Suffix | Type | Meaning |
|--------|------|---------|
| `_[j]` | `TYPE_JIT_COMPILED` | JIT-compiled (C2) Java |
| `_[i]` | `TYPE_INLINED` | Inlined Java |
| `_[k]` | `TYPE_KERNEL` | Kernel |
| `_[0]` | `TYPE_INTERPRETED` | Interpreted Java |
| `_[1]` | `TYPE_C1_COMPILED` | C1-compiled Java |
| *(none)* | `TYPE_NATIVE` / `TYPE_CPP` | Native or C++ |

Note: `_[i]` is **inlined**, not interpreted — `_[0]` is interpreted. The numeric suffixes correspond directly to the type constant values (0 = interpreted, 1 = C1-compiled).

These files can be produced from `.jfr` recordings manually with `jfrconv --cpu -o collapsed`, but passing `.jfr` files directly to any command is simpler.
