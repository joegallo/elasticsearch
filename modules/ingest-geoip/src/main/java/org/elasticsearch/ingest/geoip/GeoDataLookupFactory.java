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

    static GeoDataLookupFactory get(final String databaseType, final String databaseFile) {
        GeoDataLookupFactory factory = null;
        if (databaseType != null) {
            // yikes
            if (databaseType.contains("ipinfo ") && databaseType.contains("asn_free")) {
                factory = IPinfoGeoDataLookups.Asn::new;
            } else if (databaseType.contains("ipinfo ") && databaseType.contains("country_free")) {
                factory = IPinfoGeoDataLookups.Country::new;
            } else if (databaseType.contains("ipinfo ") && databaseType.contains("ip_geolocation")) {
                factory = IPinfoGeoDataLookups.City::new;
            }
            // yikes
            else if (databaseType.endsWith(Database.CITY_DB_SUFFIX)) {
                factory = MaxMindGeoDataLookups.City::new;
            } else if (databaseType.endsWith(Database.COUNTRY_DB_SUFFIX)) {
                factory = MaxMindGeoDataLookups.Country::new;
            } else if (databaseType.endsWith(Database.ASN_DB_SUFFIX)) {
                factory = MaxMindGeoDataLookups.Asn::new;
            } else if (databaseType.endsWith(Database.ANONYMOUS_IP_DB_SUFFIX)) {
                factory = MaxMindGeoDataLookups.AnonymousIp::new;
            } else if (databaseType.endsWith(Database.CONNECTION_TYPE_DB_SUFFIX)) {
                factory = MaxMindGeoDataLookups.ConnectionType::new;
            } else if (databaseType.endsWith(Database.DOMAIN_DB_SUFFIX)) {
                factory = MaxMindGeoDataLookups.Domain::new;
            } else if (databaseType.endsWith(Database.ENTERPRISE_DB_SUFFIX)) {
                factory = MaxMindGeoDataLookups.Enterprise::new;
            } else if (databaseType.endsWith(Database.ISP_DB_SUFFIX)) {
                factory = MaxMindGeoDataLookups.Isp::new;
            }
        }

        if (factory == null) {
            throw new IllegalArgumentException("Unsupported database type [" + databaseType + "] for file [" + databaseFile + "]");
        }

        return factory;
    }
}
