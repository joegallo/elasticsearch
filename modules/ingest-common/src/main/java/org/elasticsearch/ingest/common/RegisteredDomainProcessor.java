/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.ingest.common;

import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.ingest.AbstractProcessor;
import org.elasticsearch.ingest.ConfigurationUtils;
import org.elasticsearch.ingest.FieldPath;
import org.elasticsearch.ingest.IngestDocument;
import org.elasticsearch.ingest.Processor;
import org.elasticsearch.web.RegisteredDomain;

import java.util.Map;

public class RegisteredDomainProcessor extends AbstractProcessor {

    public static final String TYPE = "registered_domain";

    private final FieldPath field;
    // note that this is a prefix for the fields that the results are written to (and can be empty), rather than a field itself
    private final String targetField;
    private final boolean ignoreMissing;
    // the fields that the results are written to, which are the same for every document
    private final DomainInfoFields domainInfoFields;

    RegisteredDomainProcessor(String tag, String description, String field, String targetField, boolean ignoreMissing) {
        super(tag, description);
        this.field = FieldPath.of(field);
        this.targetField = targetField;
        this.ignoreMissing = ignoreMissing;
        String fieldPrefix = targetField;
        if (fieldPrefix.isEmpty() == false) {
            fieldPrefix += ".";
        }
        this.domainInfoFields = new DomainInfoFields(
            FieldPath.of(fieldPrefix + RegisteredDomain.DOMAIN),
            FieldPath.of(fieldPrefix + RegisteredDomain.REGISTERED_DOMAIN),
            FieldPath.of(fieldPrefix + RegisteredDomain.eTLD),
            FieldPath.of(fieldPrefix + RegisteredDomain.SUBDOMAIN)
        );
    }

    public FieldPath getField() {
        return field;
    }

    public String getTargetField() {
        return targetField;
    }

    public boolean getIgnoreMissing() {
        return ignoreMissing;
    }

    @Override
    public IngestDocument execute(IngestDocument document) throws Exception {
        final String fqdn = document.getFieldValue(field.resolve(document), String.class, ignoreMissing);
        boolean infoFound = RegisteredDomain.parseRegisteredDomainInfo(
            fqdn,
            new IngestDocumentRegisteredDomainInfoCollector(document, domainInfoFields)
        );
        if (infoFound == false && ignoreMissing == false) {
            throw new IllegalArgumentException("unable to set domain information for document");
        }
        return document;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    public static final class Factory implements Processor.Factory {

        static final String DEFAULT_TARGET_FIELD = "";

        @Override
        public RegisteredDomainProcessor create(
            Map<String, Processor.Factory> registry,
            String tag,
            String description,
            Map<String, Object> config,
            ProjectId projectId
        ) throws Exception {
            String field = ConfigurationUtils.readStringProperty(TYPE, tag, config, "field");
            String targetField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "target_field", DEFAULT_TARGET_FIELD);
            boolean ignoreMissing = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "ignore_missing", true);

            return new RegisteredDomainProcessor(tag, description, field, targetField, ignoreMissing);
        }
    }

    /**
     * The fields that the different parts of the domain information are written to.
     */
    private record DomainInfoFields(FieldPath domain, FieldPath registeredDomain, FieldPath topLevelDomain, FieldPath subdomain) {}

    private static class IngestDocumentRegisteredDomainInfoCollector implements RegisteredDomain.RegisteredDomainInfoCollector {
        private final IngestDocument document;
        private final DomainInfoFields fields;

        IngestDocumentRegisteredDomainInfoCollector(IngestDocument document, DomainInfoFields fields) {
            this.document = document;
            this.fields = fields;
        }

        @Override
        public void domain(String domain) {
            document.setFieldValue(fields.domain().resolve(document), domain);
        }

        @Override
        public void registeredDomain(String registeredDomain) {
            document.setFieldValue(fields.registeredDomain().resolve(document), registeredDomain);
        }

        @Override
        public void topLevelDomain(String topLevelDomain) {
            document.setFieldValue(fields.topLevelDomain().resolve(document), topLevelDomain);
        }

        @Override
        public void subdomain(String subdomain) {
            document.setFieldValue(fields.subdomain().resolve(document), subdomain);
        }
    }
}
