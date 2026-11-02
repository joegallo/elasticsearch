/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.ingest.common;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ingest.IngestDocument;
import org.elasticsearch.ingest.TestIngestDocument;
import org.elasticsearch.test.ESTestCase;
import org.junit.Before;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.ingest.common.CommunityIdProcessor.Factory.DEFAULT_DEST_IP;
import static org.elasticsearch.ingest.common.CommunityIdProcessor.Factory.DEFAULT_DEST_PORT;
import static org.elasticsearch.ingest.common.CommunityIdProcessor.Factory.DEFAULT_IANA_NUMBER;
import static org.elasticsearch.ingest.common.CommunityIdProcessor.Factory.DEFAULT_ICMP_CODE;
import static org.elasticsearch.ingest.common.CommunityIdProcessor.Factory.DEFAULT_ICMP_TYPE;
import static org.elasticsearch.ingest.common.CommunityIdProcessor.Factory.DEFAULT_SOURCE_IP;
import static org.elasticsearch.ingest.common.CommunityIdProcessor.Factory.DEFAULT_SOURCE_PORT;
import static org.elasticsearch.ingest.common.CommunityIdProcessor.Factory.DEFAULT_TARGET;
import static org.elasticsearch.ingest.common.CommunityIdProcessor.Factory.DEFAULT_TRANSPORT;
import static org.elasticsearch.ingest.common.CommunityIdProcessor.toUint16;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class CommunityIdProcessorFactoryTests extends ESTestCase {

    private CommunityIdProcessor.Factory factory;

    @Before
    public void init() {
        factory = new CommunityIdProcessor.Factory();
    }

    public void testCreate() throws Exception {
        Map<String, Object> config = new HashMap<>();

        String sourceIpField = randomAlphaOfLength(6);
        config.put("source_ip", sourceIpField);
        String sourcePortField = randomAlphaOfLength(6);
        config.put("source_port", sourcePortField);
        String destIpField = randomAlphaOfLength(6);
        config.put("destination_ip", destIpField);
        String destPortField = randomAlphaOfLength(6);
        config.put("destination_port", destPortField);
        String ianaNumberField = randomAlphaOfLength(6);
        config.put("iana_number", ianaNumberField);
        String transportField = randomAlphaOfLength(6);
        config.put("transport", transportField);
        String icmpTypeField = randomAlphaOfLength(6);
        config.put("icmp_type", icmpTypeField);
        String icmpCodeField = randomAlphaOfLength(6);
        config.put("icmp_code", icmpCodeField);
        String targetField = randomAlphaOfLength(6);
        config.put("target_field", targetField);
        int seedInt = randomIntBetween(0, 65535);
        config.put("seed", Integer.toString(seedInt));
        boolean ignoreMissing = randomBoolean();
        config.put("ignore_missing", ignoreMissing);

        String tag = randomAlphaOfLength(10);
        CommunityIdProcessor processor = factory.create(null, tag, null, config, null);
        assertThat(processor.getTag(), equalTo(tag));
        assertThat(processor.getSourceIpField().toString(), equalTo(sourceIpField));
        assertThat(processor.getSourcePortField().toString(), equalTo(sourcePortField));
        assertThat(processor.getDestinationIpField().toString(), equalTo(destIpField));
        assertThat(processor.getDestinationPortField().toString(), equalTo(destPortField));
        assertThat(processor.getIanaNumberField().toString(), equalTo(ianaNumberField));
        assertThat(processor.getTransportField().toString(), equalTo(transportField));
        assertThat(processor.getIcmpTypeField().toString(), equalTo(icmpTypeField));
        assertThat(processor.getIcmpCodeField().toString(), equalTo(icmpCodeField));
        assertThat(processor.getTargetField().toString(), equalTo(targetField));
        assertThat(processor.getSeed(), equalTo(toUint16(seedInt)));
        assertThat(processor.getIgnoreMissing(), equalTo(ignoreMissing));
    }

    public void testSeed() throws Exception {
        Map<String, Object> config = new HashMap<>();
        String tag = randomAlphaOfLength(10);

        // negative seeds are rejected
        int tooSmallSeed = randomIntBetween(Integer.MIN_VALUE, -1);
        config.put("seed", Integer.toString(tooSmallSeed));
        ElasticsearchException e = expectThrows(ElasticsearchException.class, () -> factory.create(null, tag, null, config, null));
        assertThat(e.getMessage(), containsString("must be a value between 0 and 65535"));

        // seeds >= 2^16 are rejected
        int tooBigSeed = randomIntBetween(65536, Integer.MAX_VALUE);
        config.put("seed", Integer.toString(tooBigSeed));
        e = expectThrows(ElasticsearchException.class, () -> factory.create(null, tag, null, config, null));
        assertThat(e.getMessage(), containsString("must be a value between 0 and 65535"));

        // seeds between 0 and 2^16-1 are accepted
        int justRightSeed = randomIntBetween(0, 65535);
        byte[] expectedSeed = new byte[] { (byte) (justRightSeed >> 8), (byte) justRightSeed };
        config.put("seed", Integer.toString(justRightSeed));
        CommunityIdProcessor processor = factory.create(null, tag, null, config, null);
        assertThat(processor.getSeed(), equalTo(expectedSeed));
    }

    public void testRequiredFields() throws Exception {
        String tag = randomAlphaOfLength(10);
        CommunityIdProcessor processor = factory.create(null, tag, null, new HashMap<>(), null);
        assertThat(processor.getTag(), equalTo(tag));
        assertThat(processor.getSourceIpField().toString(), equalTo(DEFAULT_SOURCE_IP));
        assertThat(processor.getSourcePortField().toString(), equalTo(DEFAULT_SOURCE_PORT));
        assertThat(processor.getDestinationIpField().toString(), equalTo(DEFAULT_DEST_IP));
        assertThat(processor.getDestinationPortField().toString(), equalTo(DEFAULT_DEST_PORT));
        assertThat(processor.getIanaNumberField().toString(), equalTo(DEFAULT_IANA_NUMBER));
        assertThat(processor.getTransportField().toString(), equalTo(DEFAULT_TRANSPORT));
        assertThat(processor.getIcmpTypeField().toString(), equalTo(DEFAULT_ICMP_TYPE));
        assertThat(processor.getIcmpCodeField().toString(), equalTo(DEFAULT_ICMP_CODE));
        assertThat(processor.getTargetField().toString(), equalTo(DEFAULT_TARGET));
        assertThat(processor.getSeed(), equalTo(toUint16(0)));
        assertThat(processor.getIgnoreMissing(), equalTo(true));
    }

    /**
     * Pipelines are stored, so a malformed path in one has to be accepted when the processor is created, with the failure only coming
     * when a document is run through it. This goes through the factory, as creating a processor from a stored pipeline does.
     */
    public void testCreateWithMalformedFields() throws Exception {
        for (String malformed : List.of(
            "source_ip",
            "source_port",
            "destination_ip",
            "destination_port",
            "iana_number",
            "transport",
            "icmp_type",
            "icmp_code",
            "target_field"
        )) {
            Map<String, Object> config = new HashMap<>();
            config.put(malformed, "a..b");
            CommunityIdProcessor processor = factory.create(null, randomAlphaOfLength(10), null, config, null);

            // everything else is fine, so it is the malformed one that causes the failure. That includes the fields that are only looked
            // at when they are needed, and they are needed here
            IngestDocument ingestDocument = TestIngestDocument.emptyIngestDocument();
            ingestDocument.setFieldValue(malformed.equals("source_ip") ? "other.source" : "source.ip", "66.35.250.204");
            ingestDocument.setFieldValue("source.port", 34855);
            ingestDocument.setFieldValue("destination.ip", "128.232.110.120");
            ingestDocument.setFieldValue("destination.port", 80);
            ingestDocument.setFieldValue("network.transport", "tcp");
            IllegalArgumentException e = expectThrows(
                IllegalArgumentException.class,
                "malformed " + malformed,
                () -> processor.execute(ingestDocument)
            );
            assertThat(malformed, e.getMessage(), equalTo("path [a..b] is not valid"));
        }
    }
}
