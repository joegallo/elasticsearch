/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.ingest.geoip;

import java.util.Set;

interface IpDataLookupFactory {
    IpDataLookup create(Set<Database.Property> properties);

    static IpDataLookupFactory get(final String databaseType, final String databaseFile) {
        IpDataLookupFactory factory = null;
        if (databaseType != null) {
            // yikes
            if (databaseType.contains("ipinfo ") && databaseType.contains("asn_free")) {
                factory = IPinfoIpDataLookups.Asn::new;
            } else if (databaseType.contains("ipinfo ") && databaseType.contains("country_free")) {
                factory = IPinfoIpDataLookups.Country::new;
            } else if (databaseType.contains("ipinfo ") && databaseType.contains("ip_geolocation")) {
                factory = IPinfoIpDataLookups.City::new;
            }
            // yikes
            else if (databaseType.endsWith(Database.CITY_DB_SUFFIX)) {
                factory = MaxMindIpDataLookups.City::new;
            } else if (databaseType.endsWith(Database.COUNTRY_DB_SUFFIX)) {
                factory = MaxMindIpDataLookups.Country::new;
            } else if (databaseType.endsWith(Database.ASN_DB_SUFFIX)) {
                factory = MaxMindIpDataLookups.Asn::new;
            } else if (databaseType.endsWith(Database.ANONYMOUS_IP_DB_SUFFIX)) {
                factory = MaxMindIpDataLookups.AnonymousIp::new;
            } else if (databaseType.endsWith(Database.CONNECTION_TYPE_DB_SUFFIX)) {
                factory = MaxMindIpDataLookups.ConnectionType::new;
            } else if (databaseType.endsWith(Database.DOMAIN_DB_SUFFIX)) {
                factory = MaxMindIpDataLookups.Domain::new;
            } else if (databaseType.endsWith(Database.ENTERPRISE_DB_SUFFIX)) {
                factory = MaxMindIpDataLookups.Enterprise::new;
            } else if (databaseType.endsWith(Database.ISP_DB_SUFFIX)) {
                factory = MaxMindIpDataLookups.Isp::new;
            }
        }

        if (factory == null) {
            throw new IllegalArgumentException("Unsupported database type [" + databaseType + "] for file [" + databaseFile + "]");
        }

        return factory;
    }
}
