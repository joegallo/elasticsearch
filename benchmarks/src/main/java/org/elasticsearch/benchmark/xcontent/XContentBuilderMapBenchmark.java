/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.xcontent;

import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentType;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Measures {@link XContentBuilder#map(Map)} on ingest-shaped documents: the work {@code IndexSource#source(Map, XContentType)}
 * does when an ingest pipeline has produced a {@code Map<String, Object>} that must be turned back into bytes.
 * <p>
 * In production the nested maps in an ingest document are a mixture of {@link HashMap} (parsed source),
 * {@link LinkedHashMap} (ordered parsing) and {@code java.util.ImmutableCollections$MapN} (processors that use {@code Map.of}
 * or {@code Map.copyOf}). The hot loop in {@code XContentBuilder.map} therefore sees several receiver types at the same
 * bytecode, which makes its {@code entrySet()}, {@code iterator()} and {@code Map.Entry} calls megamorphic. This benchmark
 * exists to quantify that cost and to evaluate specialising the loop per map type.
 * <ul>
 *   <li>{@code mapMix=hashmap}: every nested map is a {@link HashMap} and every list an {@link ArrayList}, which is what
 *       parsed source looks like. The monomorphic best case.</li>
 *   <li>{@code mapMix=mixed}: maps are roughly 60% {@link HashMap}, 25% {@link LinkedHashMap}, 15% immutable map; lists are
 *       60% {@link ArrayList}, 25% {@code List.copyOf}, 15% {@link Arrays#asList}. Both loops in {@code XContentBuilder}
 *       (the map entry loop and the {@code Iterable} loop) are megamorphic here.</li>
 * </ul>
 * The static initializer runs a self-test unless {@code -DskipSelfTest=true} is passed. It round-trips every
 * format/mix combination, and as a side effect loads every map type through the code under test so that type profiles
 * look more like production. That means even {@code mapMix=hashmap} runs with a polluted profile, which is the intent.
 * <p>
 * Compare a base checkout and a branch with interleaved forks, for example:
 * <pre>
 * ./gradlew -p benchmarks run --args 'XContentBuilderMapBenchmark -p format=SMILE -p mapMix=mixed'
 * </pre>
 */
@Fork(3)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class XContentBuilderMapBenchmark {

    private static final int DOC_COUNT = 64;

    static {
        if ("true".equals(System.getProperty("skipSelfTest")) == false) {
            try {
                selfTest();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
    }

    @Param({ "SMILE", "JSON" })
    public String format;

    @Param({ "hashmap", "mixed" })
    public String mapMix;

    private XContentType xContentType;
    private List<Map<String, Object>> docs;

    @Setup
    public void setup() {
        xContentType = XContentType.valueOf(format);
        docs = buildDocs(mapMix, DOC_COUNT);
    }

    @Benchmark
    @OperationsPerInvocation(DOC_COUNT)
    public void map(Blackhole blackhole) throws IOException {
        for (Map<String, Object> doc : docs) {
            blackhole.consume(serialize(doc, xContentType));
        }
    }

    private static BytesReference serialize(Map<String, Object> doc, XContentType type) throws IOException {
        try (XContentBuilder builder = XContentFactory.contentBuilder(type)) {
            builder.map(doc);
            return BytesReference.bytes(builder);
        }
    }

    private static void selfTest() throws IOException {
        for (XContentType type : new XContentType[] { XContentType.SMILE, XContentType.JSON }) {
            for (String mix : new String[] { "hashmap", "linked", "immutable", "mixed" }) {
                for (Map<String, Object> doc : buildDocs(mix, 8)) {
                    BytesReference bytes = serialize(doc, type);
                    Map<String, Object> roundTripped = XContentHelper.convertToMap(bytes, false, type).v2();
                    if (doc.equals(roundTripped) == false) {
                        throw new AssertionError("round trip mismatch for [" + type + "][" + mix + "]: " + doc + " vs " + roundTripped);
                    }
                }
            }
        }
    }

    private static List<Map<String, Object>> buildDocs(String mix, int count) {
        // Fixed seed: base and branch must serialize identical documents.
        Random random = new Random(42);
        List<Map<String, Object>> docs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            docs.add(object(random, mix, 0));
        }
        return docs;
    }

    /** Builds an object of ~12 leaf fields plus two nested objects per level, three levels deep. */
    private static Map<String, Object> object(Random random, String mix, int depth) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("@timestamp", "2026-11-16T12:51:36." + (100 + random.nextInt(900)) + "Z");
        fields.put("message", "request " + random.nextInt(1_000_000) + " handled in " + random.nextInt(500) + "ms");
        fields.put("level", random.nextBoolean() ? "info" : "warn");
        fields.put("host_name", "host-" + random.nextInt(64));
        fields.put("status", 200 + 100 * random.nextInt(4));
        fields.put("bytes", random.nextInt(1 << 20));
        fields.put("duration_ms", random.nextDouble() * 500);
        fields.put("ratio", random.nextDouble());
        fields.put("success", random.nextBoolean());
        fields.put("tags", newList(random, mix, List.of("a" + random.nextInt(8), "b" + random.nextInt(8), "c" + random.nextInt(8))));
        fields.put("ids", newList(random, mix, List.of(random.nextInt(1000), random.nextInt(1000))));
        if (depth < 3) {
            fields.put("labels", object(random, mix, depth + 1));
            fields.put("http", object(random, mix, depth + 1));
        }
        if (depth < 2) {
            // A list of objects, so that the list loop and the map loop recurse into each other.
            fields.put("events", newList(random, mix, List.of(object(random, mix, 3), object(random, mix, 3))));
        }
        return newMap(random, mix, fields);
    }

    /**
     * Wraps {@code items} in a list type chosen by {@code mix}, mirroring the maps: parsed source produces {@link ArrayList},
     * processors produce {@code List.of}/{@code List.copyOf} ({@code ListN}, {@code List12}) and {@link Arrays#asList}.
     */
    private static List<Object> newList(Random random, String mix, List<Object> items) {
        return switch (mix) {
            case "hashmap", "linked" -> new ArrayList<>(items);
            case "immutable" -> List.copyOf(items);
            case "mixed" -> {
                int roll = random.nextInt(100);
                if (roll < 60) {
                    yield new ArrayList<>(items);
                } else if (roll < 85) {
                    yield List.copyOf(items);
                } else {
                    yield Arrays.asList(items.toArray());
                }
            }
            default -> throw new IllegalArgumentException("unknown mapMix [" + mix + "]");
        };
    }

    private static Map<String, Object> newMap(Random random, String mix, Map<String, Object> fields) {
        return switch (mix) {
            case "hashmap" -> new HashMap<>(fields);
            case "linked" -> fields;
            case "immutable" -> Map.copyOf(fields);
            case "mixed" -> {
                int roll = random.nextInt(100);
                if (roll < 60) {
                    yield new HashMap<>(fields);
                } else if (roll < 85) {
                    yield fields;
                } else {
                    yield Map.copyOf(fields);
                }
            }
            default -> throw new IllegalArgumentException("unknown mapMix [" + mix + "]");
        };
    }
}
