#!/usr/bin/env bash

count=100;
if [ ! -z "$1" ]; then
    count="$1"
fi

pipeline="";
if [ ! -z "$2" ]; then
    pipeline="?pipeline=$2"
fi

if [ -z "$ES_URL" ]; then
    ES_URL="http://localhost:9200"
fi

# count is ignored, this is the verbose version

curl -s $ES_AUTH -XPOST "$ES_URL/_bulk$pipeline" -H 'Content-Type: application/json' -d'
{ "index" : { "_index" : "index-1" } }
{ "message" : "12 apple 1 2022-10-01"}
'
