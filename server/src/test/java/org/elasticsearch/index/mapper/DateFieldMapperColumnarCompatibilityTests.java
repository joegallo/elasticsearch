/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.mapper;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexMode;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.indices.recovery.RecoverySettings;

import java.io.IOException;

/**
 * Parity tests for {@link DateFieldMapper#mapColumnBatch} against the row path.
 * Only single-valued columnar date fields are tested; multi-valued and data stream
 * timestamp fields are out of scope and covered elsewhere.
 */
public class DateFieldMapperColumnarCompatibilityTests extends AbstractColumnarMapperCompatibilityTestCase {

    private static final String FIELD = "f";

    private static Settings columnarSettings() {
        return Settings.builder()
            .put(IndexSettings.MODE.getKey(), IndexMode.COLUMNAR.getName())
            .put(RecoverySettings.INDICES_RECOVERY_SOURCE_ENABLED_SETTING.getKey(), false)
            .put(FieldMapper.DOC_VALUES_MULTI_VALUE_SETTING.getKey(), false)
            .build();
    }

    public void testStringValue() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date").endObject()),
            columnarSettings(),
            batch("string value", 1L, doc("d1", 1L, "{\"f\":\"2024-01-15T12:00:00.000Z\"}"))
        );
    }

    public void testStringValueDateOnly() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date").endObject()),
            columnarSettings(),
            batch("string date-only value", 1L, doc("d1", 1L, "{\"f\":\"2024-06-01\"}"))
        );
    }

    public void testAbsentDoc() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date").endObject()),
            columnarSettings(),
            batch("absent doc", 1L, doc("d1", 1L, "{}"))
        );
    }

    public void testMixedAbsentPresent() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date").endObject()),
            columnarSettings(),
            batch(
                "mixed absent/present strings",
                1L,
                doc("d1", 1L, "{\"f\":\"2024-01-01T00:00:00.000Z\"}"),
                doc("d2", 2L, "{}"),
                doc("d3", 3L, "{\"f\":\"2024-03-15T08:30:00.000Z\"}")
            )
        );
    }

    public void testMultipleStringDocs() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date").endObject()),
            columnarSettings(),
            batch(
                "multiple string docs",
                1L,
                doc("d1", 1L, "{\"f\":\"2020-01-01T00:00:00.000Z\"}"),
                doc("d2", 2L, "{\"f\":\"2021-06-15T12:00:00.000Z\"}"),
                doc("d3", 3L, "{\"f\":\"2022-12-31T23:59:59.999Z\"}"),
                doc("d4", 4L, "{}")
            )
        );
    }

    public void testLongEpochMillis() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date").endObject()),
            columnarSettings(),
            batch("long epoch millis", 1L, doc("d1", 1L, "{\"f\":1705320000000}"))
        );
    }

    public void testLongEpochMillisZero() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date").endObject()),
            columnarSettings(),
            batch("long epoch millis zero", 1L, doc("d1", 1L, "{\"f\":0}"))
        );
    }

    public void testMixedAbsentPresentLong() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date").endObject()),
            columnarSettings(),
            batch(
                "mixed absent/present longs",
                1L,
                doc("d1", 1L, "{\"f\":1700000000000}"),
                doc("d2", 2L, "{}"),
                doc("d3", 3L, "{\"f\":1710000000000}")
            )
        );
    }

    public void testMultipleLongDocs() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date").endObject()),
            columnarSettings(),
            batch(
                "multiple long docs",
                1L,
                doc("d1", 1L, "{\"f\":1000000000000}"),
                doc("d2", 2L, "{\"f\":1500000000000}"),
                doc("d3", 3L, "{\"f\":1700000000000}"),
                doc("d4", 4L, "{}")
            )
        );
    }

    public void testCustomFormatString() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date").field("format", "yyyy-MM-dd").endObject()),
            columnarSettings(),
            batch(
                "custom format string values",
                1L,
                doc("d1", 1L, "{\"f\":\"2024-03-21\"}"),
                doc("d2", 2L, "{}"),
                doc("d3", 3L, "{\"f\":\"2024-12-31\"}")
            )
        );
    }

    public void testEpochMillisFormatWithLong() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date").field("format", "epoch_millis").endObject()),
            columnarSettings(),
            batch(
                "epoch_millis format with longs",
                1L,
                doc("d1", 1L, "{\"f\":1705320000000}"),
                doc("d2", 2L, "{\"f\":0}"),
                doc("d3", 3L, "{}")
            )
        );
    }

    /**
     * An absent doc activates {@code null_value} inside {@link DateFieldMapper#datesFromStrings}.
     * This is distinct from the {@link #testNullValue()} AwaitsFix scenario, which exercises
     * {@code {\"f\":null}} — a JSON-null that arrives as a UNION column and falls back to the row
     * path. Here the field is simply absent from the source, which yields a STRING column with a
     * validity-bit hole; the mapper substitutes {@code null_value}.
     */
    public void testNullValueSubstitutedForAbsentDoc() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date").field("null_value", "2024-01-01T00:00:00.000Z").endObject()),
            columnarSettings(),
            batch(
                "null_value for absent docs",
                1L,
                doc("d1", 1L, "{\"f\":\"2024-03-15T00:00:00.000Z\"}"),
                doc("d2", 2L, "{}"),
                doc("d3", 3L, "{\"f\":\"2024-06-01T00:00:00.000Z\"}")
            )
        );
    }

    /** Negative epoch millis (pre-1970 dates) in a string column. */
    public void testNegativeTimestamp_string() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date").endObject()),
            columnarSettings(),
            batch(
                "negative timestamp string",
                1L,
                doc("d1", 1L, "{\"f\":\"1960-01-01T00:00:00.000Z\"}"),
                doc("d2", 2L, "{}"),
                doc("d3", 3L, "{\"f\":\"1969-12-31T23:59:59.999Z\"}")
            )
        );
    }

    /** Negative epoch millis (pre-1970 dates) sent as long values. */
    public void testNegativeTimestamp_long() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date").endObject()),
            columnarSettings(),
            batch("negative timestamp long", 1L, doc("d1", 1L, "{\"f\":-315619200000}"), doc("d2", 2L, "{}"), doc("d3", 3L, "{\"f\":-1}"))
        );
    }

    public void testIndexedStringValue() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date").field("index", true).endObject()),
            columnarSettings(),
            batch("indexed string value", 1L, doc("d1", 1L, "{\"f\":\"2024-01-15T12:00:00.000Z\"}"))
        );
    }

    public void testIndexedLongValue() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date").field("index", true).endObject()),
            columnarSettings(),
            batch("indexed long value", 1L, doc("d1", 1L, "{\"f\":1705320000000}"))
        );
    }

    public void testIndexedWithAbsentDoc() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date").field("index", true).endObject()),
            columnarSettings(),
            batch(
                "indexed with absent doc",
                1L,
                doc("d1", 1L, "{\"f\":\"2024-01-01T00:00:00.000Z\"}"),
                doc("d2", 2L, "{}"),
                doc("d3", 3L, "{\"f\":\"2024-03-15T08:30:00.000Z\"}")
            )
        );
    }

    public void testIndexedMultipleDocs() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date").field("index", true).endObject()),
            columnarSettings(),
            batch(
                "indexed multiple docs",
                1L,
                doc("d1", 1L, "{\"f\":\"2020-01-01T00:00:00.000Z\"}"),
                doc("d2", 2L, "{\"f\":\"2021-06-15T12:00:00.000Z\"}"),
                doc("d3", 3L, "{\"f\":\"2022-12-31T23:59:59.999Z\"}"),
                doc("d4", 4L, "{}")
            )
        );
    }

    /** Negative epoch millis (pre-1970 dates) with {@code index=true} (emits {@code LongField}). */
    public void testIndexedNegativeTimestamp() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date").field("index", true).endObject()),
            columnarSettings(),
            batch(
                "indexed negative timestamp",
                1L,
                doc("d1", 1L, "{\"f\":\"1960-01-01T00:00:00.000Z\"}"),
                doc("d2", 2L, "{}"),
                doc("d3", 3L, "{\"f\":\"1969-12-31T23:59:59.999Z\"}")
            )
        );
    }

    /**
     * Explicit {@code index=false} mirrors the default in columnar mode (where
     * {@code index.mapping.index_disabled_by_default=true}), but is stated explicitly so the test
     * remains meaningful if the default ever changes. Emits a {@code SortedNumericDocValuesField}.
     */
    public void testExplicitlyNotIndexed_stringValue() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date").field("index", false).endObject()),
            columnarSettings(),
            batch(
                "explicitly not-indexed string",
                1L,
                doc("d1", 1L, "{\"f\":\"2024-01-15T12:00:00.000Z\"}"),
                doc("d2", 2L, "{}"),
                doc("d3", 3L, "{\"f\":\"2024-06-01T00:00:00.000Z\"}")
            )
        );
    }

    public void testExplicitlyNotIndexed_longValue() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date").field("index", false).endObject()),
            columnarSettings(),
            batch(
                "explicitly not-indexed long",
                1L,
                doc("d1", 1L, "{\"f\":1705320000000}"),
                doc("d2", 2L, "{}"),
                doc("d3", 3L, "{\"f\":1700000000000}")
            )
        );
    }

    // ---- date_nanos type -----------------------------------------------------------------------

    /**
     * The {@code date_nanos} type uses nanosecond resolution internally and the
     * {@code strict_date_optional_time_nanos||epoch_millis} default format. These tests mirror the
     * {@code date} suite to confirm that resolution and format differences are handled correctly by
     * both the row path and {@link DateFieldMapper#mapColumnBatch}.
     */
    public void testDateNanos_stringValue() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date_nanos").endObject()),
            columnarSettings(),
            batch(
                "date_nanos string value",
                1L,
                doc("d1", 1L, "{\"f\":\"2024-01-15T12:00:00.123456789Z\"}"),
                doc("d2", 2L, "{}"),
                doc("d3", 3L, "{\"f\":\"2024-06-01T00:00:00.000000001Z\"}")
            )
        );
    }

    public void testDateNanos_absentDocs() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date_nanos").endObject()),
            columnarSettings(),
            batch(
                "date_nanos absent docs",
                1L,
                doc("d1", 1L, "{\"f\":\"2024-01-01T00:00:00.000000000Z\"}"),
                doc("d2", 2L, "{}"),
                doc("d3", 3L, "{\"f\":\"2024-03-15T08:30:00.000000000Z\"}")
            )
        );
    }

    /**
     * Long values for {@code date_nanos} are interpreted as epoch millis (via the {@code epoch_millis}
     * alternative in the default format) and stored as nanoseconds internally. Both paths must apply
     * the same {@code resolution.convert()} multiplication.
     */
    public void testDateNanos_longEpochMillis() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date_nanos").endObject()),
            columnarSettings(),
            batch(
                "date_nanos long epoch millis",
                1L,
                doc("d1", 1L, "{\"f\":1705320000000}"),
                doc("d2", 2L, "{}"),
                doc("d3", 3L, "{\"f\":0}")
            )
        );
    }

    /** {@code date_nanos} with {@code index=true} emits a {@code LongField} (BKD + DV combined). */
    public void testDateNanos_indexed() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date_nanos").field("index", true).endObject()),
            columnarSettings(),
            batch(
                "date_nanos indexed",
                1L,
                doc("d1", 1L, "{\"f\":\"2024-01-15T12:00:00.123456789Z\"}"),
                doc("d2", 2L, "{}"),
                doc("d3", 3L, "{\"f\":\"2024-06-01T00:00:00.000000001Z\"}")
            )
        );
    }

    /**
     * Columnar-mode settings leaving {@code doc_values.multi_value} at its default of {@code true},
     * so array values reach the mapper instead of being rejected at parse time.
     */
    private static Settings multiValueColumnarSettings() {
        return Settings.builder()
            .put(IndexSettings.MODE.getKey(), IndexMode.COLUMNAR.getName())
            .put(RecoverySettings.INDICES_RECOVERY_SOURCE_ENABLED_SETTING.getKey(), false)
            .build();
    }

    /**
     * {@link DateFieldMapper#supportsColumnarParse} accepts {@code doc_values.multi_value=true} —
     * the setting defaults to {@code true}, so rejecting it would take every date field in a
     * columnar index off the columnar path. Multi-valued documents themselves are not implemented:
     * they arrive as an ESCF {@code ARRAY} column and the kind switch in
     * {@link DateFieldMapper#mapColumnBatch} throws, which makes {@code ShardBatchMapper} fall the
     * chunk back to the row path. This test pins the gap that fallback papers over.
     */
    @AwaitsFix(bugUrl = "columnar mapColumnBatch does not implement multi-valued date fields; ARRAY columns fall back to the row path")
    public void testMultiValue() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date").endObject()),
            multiValueColumnarSettings(),
            // Every present value is an array so the column is a plain ARRAY; mixing in a scalar
            // would make it a UNION and trip the same switch for a different reason.
            batch(
                "multi-value dates",
                1L,
                doc("d1", 1L, "{\"f\":[\"2024-01-01T00:00:00.000Z\",\"2024-02-01T00:00:00.000Z\"]}"),
                doc("d2", 2L, "{}"),
                doc("d3", 3L, "{\"f\":[\"2024-03-01T00:00:00.000Z\"]}")
            )
        );
    }

    /**
     * As {@link #testMultiValue}, for a null value. A null makes the column a UNION rather than a
     * plain STRING, which the same kind switch rejects.
     */
    @AwaitsFix(bugUrl = "columnar mapColumnBatch does not implement null date values; UNION columns fall back to the row path")
    public void testNullValue() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date").field("null_value", "2024-01-01T00:00:00.000Z").endObject()),
            columnarSettings(),
            batch(
                "null date value",
                1L,
                doc("d1", 1L, "{\"f\":\"2024-05-01T00:00:00.000Z\"}"),
                doc("d2", 2L, "{\"f\":null}"),
                doc("d3", 3L, "{}")
            )
        );
    }

    /**
     * {@link DateFieldMapper#supportsColumnarParse} accepts {@code ignore_malformed=true} — the
     * logsdb index modes default it to {@code true}. Per-value error handling
     * ({@code addIgnoredField} plus the ignored-source stored copy) is not implemented in
     * {@code mapColumnBatch}, so an unparseable value throws out of {@code fieldType().parse} in
     * {@code datesFromStrings} and the chunk falls back to the row path, which applies
     * {@code ignore_malformed} properly.
     */
    @AwaitsFix(bugUrl = "columnar mapColumnBatch does not implement ignore_malformed; malformed dates fall back to the row path")
    public void testIgnoreMalformed() throws IOException {
        assertColumnarMatchesXContent(
            mapping(b -> b.startObject(FIELD).field("type", "date").field("ignore_malformed", true).endObject()),
            columnarSettings(),
            batch(
                "ignore_malformed dates",
                1L,
                doc("d1", 1L, "{\"f\":\"2024-01-15T12:00:00.000Z\"}"),
                doc("d2", 2L, "{\"f\":\"not-a-date\"}"),
                doc("d3", 3L, "{}")
            )
        );
    }
}
