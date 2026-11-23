/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.iplocation.api;

import org.elasticsearch.core.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Performs IP data lookups against a resolved database.
 * <p>
 * Each invocation of {@link #lookup} obtains the latest database from the
 * internal databases map, so a single instance remains valid across database
 * updates and re-downloads without replacement.
 */
public interface IpDataLookup {

    /**
     * The number of entries to leave room for in a result map, beyond the properties that the lookup itself puts there. Consumers of the
     * result, ingest pipelines in particular, commonly add a field or two to the map that they get back (for example, an integration
     * might add a {@code name} or {@code postal_code} next to the looked-up geo fields). Leaving room for those avoids a resize of the
     * map, which would otherwise copy every entry. A survey of the Elastic integrations pipelines found that the map grew by at most
     * one entry, so two is comfortable headroom without being wasteful.
     */
    int EXPECTED_EXTRA_FIELDS = 2;

    /**
     * Look up IP data and push results to the collector.
     * <p>
     * Return value semantics (three-way):
     * <ul>
     *   <li>{@code null} -- the database is currently unavailable (e.g. not yet
     *       downloaded or removed).</li>
     *   <li>{@code false} -- the IP address was not found in the database. No collector
     *       methods are called.</li>
     *   <li>{@code true} -- data was found and pushed to the collector.</li>
     * </ul>
     */
    @Nullable
    Boolean lookup(String ip, IpLocationInfoCollector collector) throws IOException;

    /**
     * Convenience method for consumers that want a simple Map result.
     * Uses {@link IpLocationInfoMapCollector} internally. Preserves three-way
     * semantics: null (unavailable), empty map (not found), non-empty map (data).
     */
    @Nullable
    default Map<String, Object> lookup(String ip) throws IOException {
        Map<String, Object> map = HashMap.newHashMap(getInfo().getFields().size() + EXPECTED_EXTRA_FIELDS);
        Boolean result = lookup(ip, new IpLocationInfoMapCollector(map));
        if (result == null) {
            return null;
        }
        if (result == false) {
            return Map.of();
        }
        return map;
    }

    /**
     * Whether the underlying database is still valid (not expired).
     */
    boolean isValid();

    /**
     * Returns the metadata/field info for this lookup.
     */
    IpDataLookupInfo getInfo();
}
