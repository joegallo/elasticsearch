#!/usr/bin/env python3
import json
import math
import os
import sys

def nanos_per(millis, count):
    return math.ceil(millis * 1000000 / count)

def pipelines(data):
    ingests = []
    for n in data["nodes"].keys():
        ingest = data["nodes"][n]["ingest"]
        if ingest["total"]["count"] > 0:
            ingests.append(ingest)

    pipelines = set()
    for ingest in ingests:
        for pipeline in ingest["pipelines"].keys():
            if ingest["pipelines"][pipeline]["count"] > 0 and ingest["pipelines"][pipeline]["time_in_millis"] > 0:
                pipelines.add(pipeline)

    t_count = 0
    t_time_in_millis = 0
    for pipeline in sorted(pipelines):
        p_count = 0
        p_time_in_millis = 0
        for ingest in ingests:
            p_count += ingest["pipelines"][pipeline]["count"]
            p_time_in_millis += ingest["pipelines"][pipeline]["time_in_millis"]
        t_count += p_count
        t_time_in_millis += p_time_in_millis
        print(f'{pipeline} {p_count} {p_time_in_millis} {nanos_per(p_time_in_millis, p_count)}')

    print('===================')
    print(f'Pipeline Total {t_count} {t_time_in_millis} {nanos_per(t_time_in_millis, t_count)}')

def total(data):
    ingests = []
    for n in data["nodes"].keys():
        ingest = data["nodes"][n]["ingest"]
        if ingest["total"]["count"] > 0:
            ingests.append(ingest)

    t_count = 0
    t_time_in_millis = 0
    for ingest in ingests:
        t_count += ingest["total"]["count"]
        t_time_in_millis += ingest["total"]["time_in_millis"]

    print(f'Ingest Total {t_count} {t_time_in_millis} {nanos_per(t_time_in_millis, t_count)}')

def main(diagnostic):
    with open(os.path.join(diagnostic, "nodes_stats.json")) as f:
        data = json.load(f)

    pipelines(data)
    total(data)

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
