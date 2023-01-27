GET _ingest/pipeline

PUT _ingest/pipeline/test-pipeline-1
{
  "processors" : [
    {
      "set": {
        "field": "event.version",
        "value": "1.2.3"
      }
    },
    {
      "set": {
        "field": "event.ingested",
        "value": "{{_ingest.timestamp}}"
      }
    },
    {
      "rename": {
        "field": "message",
        "target_field": "event.original"
      }
    },
    {
      "dissect": {
        "field": "event.original",
        "pattern": "%{event.quantity} %{event.type} %{event.product_id} %{event.date}"
      }
    },
    {
      "set": {
        "field": "event.types",
        "copy_from": "event.type"
      }
    },
    {
      "convert": {
        "field": "event.quantity",
        "type": "long"
      }
    },
    {
      "convert": {
        "field": "event.product_id",
        "type": "long"
      }
    },
    {
      "date": {
        "field": "event.date",
        "target_field": "event.date",
        "formats": ["ISO8601"],
        "timezone" : "Europe/Amsterdam",
        "locale": "en"
      }
    },
    {
      "script": {
        "lang": "painless",
        "params": {
          "apple": {
            "1": "macintosh",
            "2": "fuji",
            "3": "golden delicious"
          },
          "orange": {
            "1": "navel",
            "2": "cara cara",
            "3": "sweet valencia"
          },
          "peach": {
            "1": "red haven",
            "2": "elberta",
            "3": "allstar"
          },
          "blah": [1,2,3,4,"foo"]
        },
        "source": "ctx.event.sub_type = params.get(Processors.lowercase(ctx.event.type)).get(ctx.event.product_id.toString());"
      }
    },
    {
      "append": {
        "field": "event.types",
        "value": "{{event.sub_type}}"
      }
    },
    {
      "remove": {
        "field": "event.product_id"
      }
    }
  ]
}


POST /_ingest/pipeline/test-pipeline-1/_simulate
{
  "docs": [
    {
      "_index": "index",
      "_id": "id",
      "_source": {
        "message": "12 apple 1 2022-10-01"
      }
    }
  ]
}

POST _bulk?pipeline=test-pipeline-1
{ "index" : { "_index" : "index-1" } }
{ "message" : "12 apple 1 2022-10-01"}


PUT index-1

DELETE index-1

GET index-1/_search

GET index-1/_doc/w8uoGYQBHnrow7QPmJqF

GET _cluster/stats?filter_path=nodes.ingest

GET _nodes/stats?filter_path=nodes.*.ingest&human=true

GET _nodes/stats?human=true

GET /
