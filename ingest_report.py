#!/usr/bin/env python3

import click
import datetime
import json
import math
import numpy as np
import pandas as pd
import os
import sys

def title(str):
    return str + "\n" + ("=" * len(str))

def nanos_per(millis, count):
    return math.ceil(millis * 1000000 / count)

def _ingests(data):
    ingests = []
    for n in data["nodes"].keys():
        ingest = data["nodes"][n]["ingest"]
        if ingest["total"]["count"] > 0:
            ingests.append(ingest)
    return ingests

def _processors(pipeline):
    return [next(iter(processor.keys())) for processor in pipeline["processors"]]

def version_and_date(version, data):
    timestamp = 0
    for n in data["nodes"].keys():
        ts = data["nodes"][n]["timestamp"]
        if ts > timestamp:
            timestamp = ts

    return [version["version"]["number"], datetime.datetime.utcfromtimestamp(timestamp / 1000).strftime("%Y-%m-%d %H:%M:%S")];


def pipelines_summary(data):
    ingests = _ingests(data)

    pipelines = set()
    for ingest in ingests:
        for pipeline in ingest["pipelines"].keys():
            if ingest["pipelines"][pipeline]["count"] > 0 and ingest["pipelines"][pipeline]["time_in_millis"] > 0:
                pipelines.add(pipeline)

    pipelines = sorted(pipelines)
    arr = np.zeros((len(pipelines), 2), dtype=np.int64)

    for i, pipeline in enumerate(pipelines):
        p_count = 0
        p_time_in_millis = 0
        for ingest in ingests:
            p_count += ingest["pipelines"][pipeline]["count"]
            p_time_in_millis += ingest["pipelines"][pipeline]["time_in_millis"]
            arr[i, 0] = p_count
            arr[i, 1] = p_time_in_millis

    df = pd.DataFrame(arr, index=pipelines, columns=["count", "time_in_millis"])
    df["time_in_nanos"] = ((df["time_in_millis"] * 1000000) / (df["count"] + 1)).apply(np.ceil).astype(np.int64)
    df["percent"] = (df["time_in_millis"] * 100 / df["time_in_millis"].sum()).astype(np.float32)

    return df

def total(data):
    ingests = _ingests(data)

    arr = np.zeros((1, 2), dtype=np.int64)

    t_count = 0
    t_ingest_time_in_millis = 0
    for ingest in ingests:
        t_count += ingest["total"]["count"]
        t_ingest_time_in_millis += ingest["total"]["time_in_millis"]

    t_index_time_in_millis = 0
    nodes = data["nodes"]
    for node in nodes:
        if "indices" not in nodes[node]:
            break

        t_index_time_in_millis = t_index_time_in_millis + nodes[node]["indices"]["indexing"]["index_time_in_millis"]

    arr[0, 0] = t_count
    arr[0, 1] = t_ingest_time_in_millis

    df = pd.DataFrame(arr, index=["total"], columns=["count", "time_in_millis"])
    df["time_in_nanos"] = ((df["time_in_millis"] * 1000000) / (df["count"] + 1)).apply(np.ceil).astype(np.int64)

    if t_index_time_in_millis == 0:
        df["ingest_time_%"] = "N/A"
    else:
        df["ingest_time_%"] = 100 * t_ingest_time_in_millis / (t_ingest_time_in_millis + t_index_time_in_millis)
        df["index_time_in_millis"] = t_index_time_in_millis

    return df

def processor(data, pipeline):
    ingests = _ingests(data)
    processors = _processors(ingests[0]["pipelines"][pipeline])
    for ingest in ingests:
        for i, processor in enumerate(_processors(ingest["pipelines"][pipeline])):
            if processors[i] != processor:
                print(f"Found {processor} but expected {processors[i]}")
                sys.exit(1)

    arr = np.zeros((len(processors), 3), dtype=np.int64)

    for i, processor in enumerate(processors):
        p_count = 0
        p_time_in_millis = 0
        for ingest in ingests:
            stats = next(iter(ingest["pipelines"][pipeline]["processors"][i].values()))["stats"]
            p_count += stats["count"]
            p_time_in_millis += stats["time_in_millis"]
            arr[i, 0] = i
            arr[i, 1] = p_count
            arr[i, 2] = p_time_in_millis

    df = pd.DataFrame(arr, index=processors, columns=["index", "count", "time_in_millis"])
    df["time_in_nanos"] = ((df["time_in_millis"] * 1000000) / (df["count"] + 1)).apply(np.ceil).astype(np.int64)
    df["percent"] = (df["time_in_millis"] * 100 / df["time_in_millis"].sum()).astype(np.float32)
    return df

def validate(diagnostic_directory):
    version_file = os.path.join(diagnostic_directory, "version.json")
    if not os.path.exists(version_file):
        print("Missing the file `version.json` in the directory")
        sys.exit(1)

    with open(version_file) as f:
        version_info = json.load(f)["version"]
        print(title("ES version: %s (%s)" % (version_info["number"], version_info["build_hash"])))
        print()

    nodes_stats = os.path.join(diagnostic_directory, "nodes_stats.json")
    if not os.path.exists(nodes_stats):
        print("Missing the file `nodes_stats.json` in the directory")
        sys.exit(1)

@click.command()
@click.argument("diagnostic_directory")
@click.option('--full', is_flag=True, default=False)
@click.option('--top-processors', is_flag=True, default=False)
def command(full, top_processors, diagnostic_directory):
    validate(diagnostic_directory)

    pd.set_option('display.max_rows', None)
    pd.set_option('display.max_colwidth', None)
    pd.set_option('display.float_format', '{:,.1f}%'.format)
    pd.set_option('display.multi_sparse', False)

    with open(os.path.join(diagnostic_directory, "version.json")) as f:
        version = json.load(f)

    with open(os.path.join(diagnostic_directory, "nodes_stats.json")) as f:
        data = json.load(f)

    p = pipelines_summary(data)
    p = p.sort_values("time_in_millis", ascending=False)
    pt = p.sum().drop(["time_in_nanos","percent"])
    t = total(data)

    v, d = version_and_date(version, data)
    print(title("Ingest & Index Summary: ({} / {})".format(v, d)))
    print(t)
    print()

    print(title("Pipeline Summary:"))
    if full:
        print(p)
    else:
        print(p.head(5))

    print("{0:.0%}".format((pt / t.loc["total"])["time_in_millis"]))
    print()

    pipelines = sorted(p.index) if full else p.index[0:5]

    if top_processors:
        tpr = pd.concat(
            [processor(data, pipeline) for pipeline in pipelines],
            axis=0,
            join="outer",
            ignore_index=False,
            keys=pipelines,
            levels=None,
            names=["pipeline", "processor"],
            verify_integrity=False,
            copy=True,
        )
        # but drop the pipeline processors because they aren't interesting
        pipeline_processors = [p for p in tpr.index.get_level_values(1).tolist() if p.startswith("pipeline")]
        tpr = tpr.drop(pipeline_processors, level=1) # pipeline processors aren't interesting

        tpr["percent"] = (tpr["time_in_millis"] * 100 / tpr["time_in_millis"].sum()).astype(np.float32)
        tpr = tpr.sort_values("time_in_millis", ascending=False)
        print(title("Top Processors:"))
        print(tpr.head(10))
        print()

        tpr = tpr.groupby("processor").sum()
        tpr.index = tpr.index.str.replace(r":.*$", "", regex=True)
        tpr = tpr.groupby("processor").sum() # gross, need to re-group-by now that e.g. "date:foo" has become "date"
        tpr = tpr.drop(["index"], axis=1)
        tpr = tpr[tpr['count'] > 0] # drop rows where count = 0
        tpr["time_in_nanos"] = ((tpr["time_in_millis"] * 1000000) / (tpr["count"] + 1)).apply(np.ceil).astype(np.int64)
        tpr["percent"] = (tpr["time_in_millis"] * 100 / tpr["time_in_millis"].sum()).astype(np.float32)
        tpr = tpr.sort_values("time_in_millis", ascending=False)
        print(title("Top Processors by Type:"))
        print(tpr.head(10))

    else:
        for pipeline in pipelines:
            pr = processor(data, pipeline)
            pr = pr.sort_values("time_in_millis", ascending=False)

            print(title(f"Pipeline '{pipeline}' processors:"))
            print(pr)
            print("{0:.0%}".format(pr['time_in_millis'].sum() / p.loc[pipeline]["time_in_millis"]))
            print()


if __name__ == "__main__":
    command()
