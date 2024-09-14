/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.ingest.geoip;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

interface IpDataLookup {
    /**
     * Gets geodata from the provided {@code geoIpDatabase} for the provided {@code ip}
     * @param ipDatabase the database from which to lookup a result
     * @param ipAddress the ip address
     * @return a map of geodata corresponding to the configured properties
     * @throws IOException if the implementation encounters any problem while retrieving the response
     */
    Map<String, Object> get(IpDatabase ipDatabase, String ipAddress) throws IOException;

    /**
     * @return the set of properties this lookup will provide
     */
    Set<Database.Property> getProperties();
}
