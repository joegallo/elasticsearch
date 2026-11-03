/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.ingest.common;

import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.ingest.IngestDocument;
import org.elasticsearch.ingest.TestIngestDocument;
import org.elasticsearch.ingest.TestTemplateService;
import org.elasticsearch.test.ESTestCase;
import org.junit.Before;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.ingest.common.NetworkDirectionProcessor.Factory.DEFAULT_DEST_IP;
import static org.elasticsearch.ingest.common.NetworkDirectionProcessor.Factory.DEFAULT_SOURCE_IP;
import static org.elasticsearch.ingest.common.NetworkDirectionProcessor.Factory.DEFAULT_TARGET;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasToString;

public class NetworkDirectionProcessorFactoryTests extends ESTestCase {

    private NetworkDirectionProcessor.Factory factory;

    @Before
    public void init() {
        factory = new NetworkDirectionProcessor.Factory(TestTemplateService.instance());
    }

    public void testCreate() throws Exception {
        Map<String, Object> config = new HashMap<>();

        String sourceIpField = randomAlphaOfLength(6);
        config.put("source_ip", sourceIpField);
        String destIpField = randomAlphaOfLength(6);
        config.put("destination_ip", destIpField);
        String targetField = randomAlphaOfLength(6);
        config.put("target_field", targetField);
        List<String> internalNetworks = new ArrayList<>();
        internalNetworks.add("10.0.0.0/8");
        config.put("internal_networks", internalNetworks);
        boolean ignoreMissing = randomBoolean();
        config.put("ignore_missing", ignoreMissing);

        String processorTag = randomAlphaOfLength(10);
        NetworkDirectionProcessor networkProcessor = factory.create(null, processorTag, null, config, null);
        assertThat(networkProcessor.getTag(), equalTo(processorTag));
        assertThat(networkProcessor.getSourceIpField(), hasToString(sourceIpField));
        assertThat(networkProcessor.getDestinationIpField(), hasToString(destIpField));
        assertThat(networkProcessor.getTargetField(), hasToString(targetField));
        assertThat(networkProcessor.getInternalNetworks().size(), greaterThan(0));
        assertThat(networkProcessor.getInternalNetworks().get(0), hasToString("10.0.0.0/8"));
        assertThat(networkProcessor.getIgnoreMissing(), equalTo(ignoreMissing));
    }

    public void testCreateInternalNetworksField() throws Exception {
        Map<String, Object> config = new HashMap<>();

        String sourceIpField = randomAlphaOfLength(6);
        config.put("source_ip", sourceIpField);
        String destIpField = randomAlphaOfLength(6);
        config.put("destination_ip", destIpField);
        String targetField = randomAlphaOfLength(6);
        config.put("target_field", targetField);
        String internalNetworksField = randomAlphaOfLength(6);
        config.put("internal_networks_field", internalNetworksField);
        boolean ignoreMissing = randomBoolean();
        config.put("ignore_missing", ignoreMissing);

        String processorTag = randomAlphaOfLength(10);
        NetworkDirectionProcessor networkProcessor = factory.create(null, processorTag, null, config, null);
        assertThat(networkProcessor.getTag(), equalTo(processorTag));
        assertThat(networkProcessor.getSourceIpField(), hasToString(sourceIpField));
        assertThat(networkProcessor.getDestinationIpField(), hasToString(destIpField));
        assertThat(networkProcessor.getTargetField(), hasToString(targetField));
        assertThat(networkProcessor.getInternalNetworksField(), hasToString(internalNetworksField));
        assertThat(networkProcessor.getIgnoreMissing(), equalTo(ignoreMissing));
    }

    public void testRequiredFields() throws Exception {
        HashMap<String, Object> config = new HashMap<>();
        String processorTag = randomAlphaOfLength(10);
        try {
            factory.create(null, processorTag, null, config, null);
            fail("factory create should have failed");
        } catch (ElasticsearchParseException e) {
            assertThat(e.getMessage(), equalTo("[internal_networks] or [internal_networks_field] must be specified"));
        }
    }

    public void testDefaultFields() throws Exception {
        HashMap<String, Object> config = new HashMap<>();
        String processorTag = randomAlphaOfLength(10);
        List<String> internalNetworks = new ArrayList<>();
        internalNetworks.add("10.0.0.0/8");
        config.put("internal_networks", internalNetworks);

        NetworkDirectionProcessor networkProcessor = factory.create(null, processorTag, null, config, null);
        assertThat(networkProcessor.getTag(), equalTo(processorTag));
        assertThat(networkProcessor.getSourceIpField(), hasToString(DEFAULT_SOURCE_IP));
        assertThat(networkProcessor.getDestinationIpField(), hasToString(DEFAULT_DEST_IP));
        assertThat(networkProcessor.getTargetField(), hasToString(DEFAULT_TARGET));
        assertThat(networkProcessor.getIgnoreMissing(), equalTo(true));
    }

    /**
     * Pipelines are stored, so a malformed path in one has to be accepted when the processor is created, with the failure only coming
     * when a document is run through it. This goes through the factory, as creating a processor from a stored pipeline does.
     */
    public void testCreateWithMalformedFields() throws Exception {
        for (String malformed : List.of("source_ip", "destination_ip", "target_field", "internal_networks_field")) {
            Map<String, Object> config = new HashMap<>();
            config.put("source_ip", "source.ip");
            config.put("destination_ip", "destination.ip");
            config.put("target_field", "network.direction");
            if (malformed.equals("internal_networks_field")) {
                config.put("internal_networks_field", "internal");
            } else {
                config.put("internal_networks", List.of("10.0.0.0/8"));
            }
            config.put(malformed, "a..b");
            NetworkDirectionProcessor processor = factory.create(null, randomAlphaOfLength(10), null, config, null);

            // everything else is fine, so it is the malformed one that causes the failure
            IngestDocument ingestDocument = TestIngestDocument.emptyIngestDocument();
            ingestDocument.setFieldValue("source.ip", "10.0.0.1");
            ingestDocument.setFieldValue("destination.ip", "8.8.8.8");
            ingestDocument.setFieldValue("internal", List.of("10.0.0.0/8"));
            IllegalArgumentException e = expectThrows(
                IllegalArgumentException.class,
                "malformed " + malformed,
                () -> processor.execute(ingestDocument)
            );
            assertThat(malformed, e.getMessage(), equalTo("path [a..b] is not valid"));
        }
    }
}
