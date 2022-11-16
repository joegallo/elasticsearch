#!/usr/bin/env python3
import json
import os
import sys

from pygments import highlight, lexers, formatters

def main(diagnostic, pipeline):
    with open(os.path.join(diagnostic, "pipelines.json")) as f:
        data = json.load(f)

    pipeline = data[pipeline]
    formatted_json = json.dumps(pipeline, indent=2)

    colorful_json = highlight(formatted_json, lexers.JsonLexer(), formatters.TerminalFormatter())
    print(colorful_json)

if __name__ == "__main__":
    if len(sys.argv) == 1:
        print("Run as pipeline.py <pipeline> <path to some diagnostic>")
        sys.exit(1)

    diagnostic = sys.argv[2]
    version = os.path.join(diagnostic, "version.json")
    if not os.path.exists(version):
        print("Run as pipeline.py <pipeline> <path to some diagnostic>")
        sys.exit(1)

    pipeline = sys.argv[1]

    main(diagnostic, pipeline)
