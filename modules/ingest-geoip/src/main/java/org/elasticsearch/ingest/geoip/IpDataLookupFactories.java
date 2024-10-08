/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.ingest.geoip;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.Strings;
import org.elasticsearch.core.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

final class IpDataLookupFactories {

    private IpDataLookupFactories() {
        // utility class
    }

    private static final Logger logger = LogManager.getLogger(IpDataLookupFactories.class);

    interface IpDataLookupFactory {
        IpDataLookup create(List<String> properties);
    }

    private static final String IPINFO_PREFIX = "ipinfo";

    // subsequent dispatch is case-sensitive suffix matching
    private static final String CITY_DB_SUFFIX = "-City";
    private static final String COUNTRY_DB_SUFFIX = "-Country";
    private static final String ASN_DB_SUFFIX = "-ASN";
    private static final String ANONYMOUS_IP_DB_SUFFIX = "-Anonymous-IP";
    private static final String CONNECTION_TYPE_DB_SUFFIX = "-Connection-Type";
    private static final String DOMAIN_DB_SUFFIX = "-Domain";
    private static final String ENTERPRISE_DB_SUFFIX = "-Enterprise";
    private static final String ISP_DB_SUFFIX = "-ISP";

    private static final Set<String> IPINFO_TYPE_STOP_WORDS = Set.of(
        "ipinfo",
        "extended",
        "free",
        "generic",
        "ip",
        "sample",
        "standard",
        "mmdb"
    );

    static String ipinfoTypeCleanup(String type) {
        List<String> parts = Arrays.asList(type.split("[ _.]"));
        return parts.stream().filter((s) -> IPINFO_TYPE_STOP_WORDS.contains(s) == false).collect(Collectors.joining("_"));
    }

    @Nullable
    private static Database getIpinfoDatabase(final String databaseType) {
        // for ipinfo the database selection is more along the lines of user-agent sniffing than
        // string-based dispatch. the specific database_type strings could change in the future,
        // hence the somewhat loose nature of this checking.

        final String cleanedType = ipinfoTypeCleanup(databaseType);

        // early detection on any of the 'extended' types
        if (databaseType.contains("extended")) {
            // which are not currently supported, so log and return null
            logger.trace("returning null for unsupported database_type [{}]", databaseType);
            return null;
        }

        // early detection on 'country_asn' so the 'country' and 'asn' checks don't get faked out
        if (cleanedType.contains("country_asn")) {
            // which are not currently supported, so log and return null
            logger.trace("returning null for unsupported database_type [{}]", databaseType);
            // but it's not currently supported, so return null
            return null;
        }

        if (cleanedType.contains("asn")) {
            return Database.Asn;
        } else if (cleanedType.contains("country")) {
            return Database.Country;
        } else if (cleanedType.contains("location")) { // note: catches 'location' and 'geolocation' ;)
            return Database.City;
        } else if (cleanedType.contains("privacy")) {
            return null; // TODO yikes: Database.Privacy will need to exist
        } else {
            // no match was found, so log and return null
            logger.trace("returning null for unsupported database_type [{}]", databaseType);
            return null;
        }
    }

    @Nullable
    private static Database getMaxmindDatabase(final String databaseType) {
        if (databaseType.endsWith(CITY_DB_SUFFIX)) {
            return Database.City;
        } else if (databaseType.endsWith(COUNTRY_DB_SUFFIX)) {
            return Database.Country;
        } else if (databaseType.endsWith(ASN_DB_SUFFIX)) {
            return Database.Asn;
        } else if (databaseType.endsWith(ANONYMOUS_IP_DB_SUFFIX)) {
            return Database.AnonymousIp;
        } else if (databaseType.endsWith(CONNECTION_TYPE_DB_SUFFIX)) {
            return Database.ConnectionType;
        } else if (databaseType.endsWith(DOMAIN_DB_SUFFIX)) {
            return Database.Domain;
        } else if (databaseType.endsWith(ENTERPRISE_DB_SUFFIX)) {
            return Database.Enterprise;
        } else if (databaseType.endsWith(ISP_DB_SUFFIX)) {
            return Database.Isp;
        } else {
            return null; // no match was found
        }
    }

    /**
     * Parses the passed-in databaseType and return the Database instance that is
     * associated with that databaseType.
     *
     * @param databaseType the database type String from the metadata of the database file
     * @return the Database instance that is associated with the databaseType
     */
    @Nullable
    static Database getDatabase(final String databaseType) {
        Database database = null;

        if (Strings.hasText(databaseType)) {
            final String databaseTypeLowerCase = databaseType.toLowerCase(Locale.ROOT);
            if (databaseTypeLowerCase.startsWith(IPINFO_PREFIX)) {
                database = getIpinfoDatabase(databaseTypeLowerCase); // all lower case!
            } else {
                // for historical reasons, fall back to assuming maxmind-like type parsing
                database = getMaxmindDatabase(databaseType);
            }
        }

        return database;
    }

    @Nullable
    static Function<Set<Database.Property>, IpDataLookup> getIpinfoLookup(final Database database) {
        return switch (database) {
            // TODO yikes:
            // free asn :check:
            // free country :check:
            // non-free asn :meh: // TODO yikes
            // non-free geolocation :meh: // TODO yikes
            // non-free privacy // TODO yikes

            case Database.Asn -> IpinfoIpDataLookups.Asn::new;
            case Database.Country -> IpinfoIpDataLookups.Country::new;
            case Database.City -> IpinfoIpDataLookups.Geolocation::new;
            default -> null;
        };
    }

    @Nullable
    static Function<Set<Database.Property>, IpDataLookup> getMaxmindLookup(final Database database) {
        return switch (database) {
            case City -> MaxmindIpDataLookups.City::new;
            case Country -> MaxmindIpDataLookups.Country::new;
            case Asn -> MaxmindIpDataLookups.Asn::new;
            case AnonymousIp -> MaxmindIpDataLookups.AnonymousIp::new;
            case ConnectionType -> MaxmindIpDataLookups.ConnectionType::new;
            case Domain -> MaxmindIpDataLookups.Domain::new;
            case Enterprise -> MaxmindIpDataLookups.Enterprise::new;
            case Isp -> MaxmindIpDataLookups.Isp::new;
            default -> null;
        };
    }

    static IpDataLookupFactory get(final String databaseType, final String databaseFile) {
        final Database database = getDatabase(databaseType);
        if (database == null) {
            throw new IllegalArgumentException("Unsupported database type [" + databaseType + "] for file [" + databaseFile + "]");
        }

        final Function<Set<Database.Property>, IpDataLookup> factoryMethod;
        final String databaseTypeLowerCase = databaseType.toLowerCase(Locale.ROOT);
        if (databaseTypeLowerCase.startsWith(IPINFO_PREFIX)) {
            factoryMethod = getIpinfoLookup(database);
        } else {
            // for historical reasons, fall back to assuming maxmind-like types
            factoryMethod = getMaxmindLookup(database);
        }

        if (factoryMethod == null) {
            throw new IllegalArgumentException("Unsupported database type [" + databaseType + "] for file [" + databaseFile + "]");
        }

        return (properties) -> factoryMethod.apply(database.parseProperties(properties));
    }
}
