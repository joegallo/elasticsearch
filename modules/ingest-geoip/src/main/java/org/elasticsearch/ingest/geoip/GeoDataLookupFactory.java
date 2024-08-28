/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.ingest.geoip;

import java.util.Set;

interface GeoDataLookupFactory {
    GeoDataLookup create(Set<Database.Property> properties);

    static GeoDataLookupFactory get(final Database database) {
        return switch (database) {
            case City -> MaxMindGeoDataLookups.City::new;
            case Country -> MaxMindGeoDataLookups.Country::new;
            case Asn -> MaxMindGeoDataLookups.Asn::new;
            case AnonymousIp -> MaxMindGeoDataLookups.AnonymousIp::new;
            case ConnectionType -> MaxMindGeoDataLookups.ConnectionType::new;
            case Domain -> MaxMindGeoDataLookups.Domain::new;
            case Enterprise -> MaxMindGeoDataLookups.Enterprise::new;
            case Isp -> MaxMindGeoDataLookups.Isp::new;
        };
    }
}
