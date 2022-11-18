#!/usr/bin/env python3
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

def pipelines(data):
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
    t_time_in_millis = 0
    for ingest in ingests:
        t_count += ingest["total"]["count"]
        t_time_in_millis += ingest["total"]["time_in_millis"]

    arr[0, 0] = t_count
    arr[0, 1] = t_time_in_millis

    df = pd.DataFrame(arr, index=["total"], columns=["count", "time_in_millis"])
    df["time_in_nanos"] = ((df["time_in_millis"] * 1000000) / (df["count"] + 1)).apply(np.ceil).astype(np.int64)
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

def main(diagnostic):
    pd.set_option('display.max_rows', None)
    pd.set_option('display.max_colwidth', None)
    pd.set_option('display.float_format', '{:,.1f}%'.format)

    with open(os.path.join(diagnostic, "nodes_stats.json")) as f:
        data = json.load(f)

    p = pipelines(data)
    p = p.sort_values("time_in_millis", ascending=False)
    pt = p.sum().drop(["time_in_nanos","percent"])
    t = total(data)

    print(title("Ingest Summary:"))
    print(t)
    print()

    print(title("Pipeline Summary:"))
    print(p.head(5))
    print("{0:.0%}".format((pt / t.loc["total"])["time_in_millis"]))
    print()

    for pipeline in p.index[0:5]:
        pr = processor(data, pipeline)
        pr = pr.sort_values("time_in_millis", ascending=False)
        prt = pr.sum().drop(["index","time_in_nanos","percent"])

        print(title(f"Pipeline '{pipeline}' processors:"))
        print(pr)
        print("{0:.0%}".format(pr['time_in_nanos'].sum() / p.loc[pipeline]["time_in_nanos"]))
        print()


if __name__ == "__main__":
    if len(sys.argv) == 1:
        print("Run as ingest_report.py <path to some diagnostic>")
        sys.exit(1)

    diagnostic = sys.argv[1]
    version = os.path.join(diagnostic, "version.json")
    if not os.path.exists(version):
        print("Run as ingest_report.py <path to some diagnostic>")
        sys.exit(1)

    main(diagnostic)
