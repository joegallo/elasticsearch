/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.ingest.attachment;

import org.apache.lucene.util.SetOnce;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.ingest.IngestDocument;
import org.elasticsearch.ingest.TestIngestDocument;
import org.elasticsearch.test.ESTestCase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.core.Is.is;

public class AttachmentProcessorFactoryTests extends ESTestCase {

    private final AttachmentProcessor.Factory factory = new AttachmentProcessor.Factory(Settings.EMPTY, new SetOnce<>());

    public void testBuildDefaults() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("field", "_field");

        String processorTag = randomAlphaOfLength(10);

        AttachmentProcessor processor = factory.create(null, processorTag, null, config, null);
        assertThat(processor.getTag(), equalTo(processorTag));
        assertThat(processor.getField(), hasToString("_field"));
        assertThat(processor.getTargetField(), hasToString("attachment"));
        assertThat(processor.getProperties(), sameInstance(AttachmentProcessor.Factory.DEFAULT_PROPERTIES));
        assertFalse(processor.isIgnoreMissing());

        assertWarnings(
            "The default [remove_binary] value of 'false' is deprecated "
                + "and will be set to 'true' in a future release. Set [remove_binary] explicitly to 'true'"
                + " or 'false' to ensure no behavior change."
        );
    }

    public void testConfigureIndexedChars() throws Exception {
        int indexedChars = randomIntBetween(1, 100000);
        Map<String, Object> config = new HashMap<>();
        config.put("field", "_field");
        config.put("indexed_chars", indexedChars);

        String processorTag = randomAlphaOfLength(10);
        AttachmentProcessor processor = factory.create(null, processorTag, null, config, null);
        assertThat(processor.getTag(), equalTo(processorTag));
        assertThat(processor.getIndexedChars(), is(indexedChars));
        assertFalse(processor.isIgnoreMissing());

        assertWarnings(
            "The default [remove_binary] value of 'false' is deprecated "
                + "and will be set to 'true' in a future release. Set [remove_binary] explicitly to 'true'"
                + " or 'false' to ensure no behavior change."
        );
    }

    public void testBuildTargetField() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("field", "_field");
        config.put("target_field", "_field");
        AttachmentProcessor processor = factory.create(null, null, null, config, null);
        assertThat(processor.getField(), hasToString("_field"));
        assertThat(processor.getTargetField(), hasToString("_field"));
        assertFalse(processor.isIgnoreMissing());

        assertWarnings(
            "The default [remove_binary] value of 'false' is deprecated "
                + "and will be set to 'true' in a future release. Set [remove_binary] explicitly to 'true'"
                + " or 'false' to ensure no behavior change."
        );
    }

    public void testBuildFields() throws Exception {
        Set<AttachmentProcessor.Property> properties = EnumSet.noneOf(AttachmentProcessor.Property.class);
        List<String> fieldNames = new ArrayList<>();
        int numFields = scaledRandomIntBetween(1, AttachmentProcessor.Property.values().length);
        for (int i = 0; i < numFields; i++) {
            AttachmentProcessor.Property property = AttachmentProcessor.Property.values()[i];
            properties.add(property);
            fieldNames.add(property.name().toLowerCase(Locale.ROOT));
        }
        Map<String, Object> config = new HashMap<>();
        config.put("field", "_field");
        config.put("properties", fieldNames);
        AttachmentProcessor processor = factory.create(null, null, null, config, null);
        assertThat(processor.getField(), hasToString("_field"));
        assertThat(processor.getProperties(), equalTo(properties));
        assertFalse(processor.isIgnoreMissing());

        assertWarnings(
            "The default [remove_binary] value of 'false' is deprecated "
                + "and will be set to 'true' in a future release. Set [remove_binary] explicitly to 'true'"
                + " or 'false' to ensure no behavior change."
        );
    }

    public void testBuildIllegalFieldOption() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("field", "_field");
        config.put("properties", Collections.singletonList("invalid"));
        try {
            factory.create(null, null, null, config, null);
            fail("exception expected");
        } catch (ElasticsearchParseException e) {
            assertThat(e.getMessage(), containsString("[properties] illegal field option [invalid]"));
            // ensure allowed fields are mentioned
            for (AttachmentProcessor.Property property : AttachmentProcessor.Property.values()) {
                assertThat(e.getMessage(), containsString(property.name()));
            }
        }

        config = new HashMap<>();
        config.put("field", "_field");
        config.put("properties", "invalid");
        try {
            factory.create(null, null, null, config, null);
            fail("exception expected");
        } catch (ElasticsearchParseException e) {
            assertThat(e.getMessage(), equalTo("[properties] property isn't a list, but of type [java.lang.String]"));
        }

        assertWarnings(
            "The default [remove_binary] value of 'false' is deprecated "
                + "and will be set to 'true' in a future release. Set [remove_binary] explicitly to 'true'"
                + " or 'false' to ensure no behavior change."
        );
    }

    public void testIgnoreMissing() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("field", "_field");
        config.put("ignore_missing", true);

        String processorTag = randomAlphaOfLength(10);

        AttachmentProcessor processor = factory.create(null, processorTag, null, config, null);
        assertThat(processor.getTag(), equalTo(processorTag));
        assertThat(processor.getField(), hasToString("_field"));
        assertThat(processor.getTargetField(), hasToString("attachment"));
        assertThat(processor.getProperties(), sameInstance(AttachmentProcessor.Factory.DEFAULT_PROPERTIES));
        assertTrue(processor.isIgnoreMissing());

        assertWarnings(
            "The default [remove_binary] value of 'false' is deprecated "
                + "and will be set to 'true' in a future release. Set [remove_binary] explicitly to 'true'"
                + " or 'false' to ensure no behavior change."
        );
    }

    public void testRemoveBinary() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("field", "_field");
        config.put("remove_binary", true);

        String processorTag = randomAlphaOfLength(10);

        AttachmentProcessor processor = factory.create(null, processorTag, null, config, null);
        assertThat(processor.getTag(), equalTo(processorTag));
        assertThat(processor.getField(), hasToString("_field"));
        assertThat(processor.getTargetField(), hasToString("attachment"));
        assertThat(processor.getProperties(), sameInstance(AttachmentProcessor.Factory.DEFAULT_PROPERTIES));
        assertTrue(processor.isRemoveBinary());
    }

    public void testMaxFieldBytesInvalid() {
        Map<String, Object> config = new HashMap<>();
        config.put("field", "_field");
        config.put("remove_binary", true);
        config.put("max_field_bytes", randomFrom("not-a-number", String.valueOf(randomLongBetween(-10L, -2L))));
        expectThrows(ElasticsearchParseException.class, () -> factory.create(null, "t", null, config, null));
    }

    public void testMaxFieldBytesMissing() {
        Map<String, Object> config = new HashMap<>();
        config.put("field", "_field");
        config.put("remove_binary", true);
        AttachmentProcessor processor = factory.create(null, "t", null, config, null);
        assertThat(processor.getMaxFieldBytesFromProcessor(), equalTo(-1));
        assertThat(processor.getMaxFieldSizeFromNode().getAbsolute(), equalTo(ByteSizeValue.MINUS_ONE));
    }

    /**
     * Pipelines are stored, so a malformed path in one has to be accepted when the processor is created, with the failure only coming
     * when a document is run through it. This goes through the factory, as creating a processor from a stored pipeline does.
     */
    public void testBuildWithMalformedFields() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("field", "a..b");
        config.put("target_field", "c..d");
        config.put("indexed_chars_field", "e..f");
        config.put("resource_name", "g..h");
        config.put("remove_binary", randomBoolean());
        AttachmentProcessor processor = factory.create(null, randomAlphaOfLength(10), null, config, null);
        assertThat(processor.getField(), hasToString("a..b"));
        assertThat(processor.getTargetField(), hasToString("c..d"));

        // it is the field that is looked at first, so that is the one that is reported
        IngestDocument ingestDocument = TestIngestDocument.emptyIngestDocument();
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> processor.execute(ingestDocument));
        assertThat(e.getMessage(), equalTo("path [a..b] is not valid"));
    }
}
