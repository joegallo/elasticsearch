/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.ingest.geoip;

import com.maxmind.db.DatabaseRecord;
import com.maxmind.db.MaxMindDbConstructor;
import com.maxmind.db.MaxMindDbParameter;
import com.maxmind.db.Reader;

import org.elasticsearch.common.Strings;
import org.elasticsearch.common.network.InetAddresses;
import org.elasticsearch.common.network.NetworkAddress;
import org.elasticsearch.core.Nullable;

import java.io.IOException;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A collection of {@link IpDataLookup} implementations for IPinfo databases
 */
final class IPinfoIpDataLookups {

    private IPinfoIpDataLookups() {
        // utility class
    }

    static Long parseAsn(String asn) {
        if (asn == null || Strings.hasText(asn) == false) {
            return null;
        } else {
            String stripped = asn.toUpperCase(Locale.ROOT).replaceAll("AS", "").trim();
            try {
                return Long.parseLong(stripped);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    static Boolean parseBoolean(String bool) {
        if (bool == null) {
            return null;
        } else {
            String trimmed = bool.trim();
            return "true".equalsIgnoreCase(trimmed);
        }
    }

    static Double parseLocationDouble(String latlon) {
        if (latlon == null || Strings.hasText(latlon) == false) {
            return null;
        } else {
            String stripped = latlon.trim();
            try {
                return Double.parseDouble(stripped);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    public record IPinfoASN(
        Long asn, //
        @Nullable String country, // what do we want to do with this one?; // not present in the free asn database
        String domain, // what do we want to do with this one?
        String name, //
        @Nullable String type // what do we want to do with this one?; // not present in the free asn database
    ) {
        @SuppressWarnings("checkstyle:RedundantModifier")
        @MaxMindDbConstructor
        public IPinfoASN(
            @MaxMindDbParameter(name = "asn") String asn,
            @Nullable @MaxMindDbParameter(name = "country") String country,
            @MaxMindDbParameter(name = "domain") String domain,
            @MaxMindDbParameter(name = "name") String name,
            @Nullable @MaxMindDbParameter(name = "type") String type
        ) {
            this(parseAsn(asn), country, domain, name, type);
        }
    }

    public record IPinfoCountry(
        @MaxMindDbParameter(name = "continent") String continent,
        @MaxMindDbParameter(name = "continent_name") String continentName,
        @MaxMindDbParameter(name = "country") String country,
        @MaxMindDbParameter(name = "country_name") String countryName
    ) {
        @MaxMindDbConstructor
        public IPinfoCountry {}
    }

    public record IPinfoGeolocation(
        String city,
        String country,
        Double latitude,
        Double longitude,
        String postalCode, // what do we want to do with this one?
        String region,
        String timezone
    ) {
        @SuppressWarnings("checkstyle:RedundantModifier")
        @MaxMindDbConstructor
        public IPinfoGeolocation(
            @MaxMindDbParameter(name = "city") String city,
            @MaxMindDbParameter(name = "country") String country,
            @MaxMindDbParameter(name = "latitude") String latitude,
            @MaxMindDbParameter(name = "longitude") String longitude,
            // @MaxMindDbParameter(name = "network") String network, // what do we want to do with this one?
            @MaxMindDbParameter(name = "postal_code") String postalCode, // what do we want to do with this one?
            @MaxMindDbParameter(name = "region") String region,
            @MaxMindDbParameter(name = "timezone") String timezone
        ) {
            this(city, country, parseLocationDouble(latitude), parseLocationDouble(longitude), postalCode, region, timezone);
        }
    }

    public record IPinfoPrivacyDetection(Boolean hosting, Boolean proxy, Boolean relay, String service, Boolean tor, Boolean vpn) {
        @SuppressWarnings("checkstyle:RedundantModifier")
        @MaxMindDbConstructor
        public IPinfoPrivacyDetection(
            @MaxMindDbParameter(name = "hosting") String hosting,
            // @MaxMindDbParameter(name = "network") String network, // what do we want to do with this one?
            @MaxMindDbParameter(name = "proxy") String proxy,
            @MaxMindDbParameter(name = "relay") String relay,
            @MaxMindDbParameter(name = "service") String service,
            @MaxMindDbParameter(name = "tor") String tor,
            @MaxMindDbParameter(name = "vpn") String vpn
        ) {
            this(parseBoolean(hosting), parseBoolean(proxy), parseBoolean(relay), service, parseBoolean(tor), parseBoolean(vpn));
        }
    }

    static class Asn extends AbstractBase<IPinfoASN> {
        Asn(Set<Database.Property> properties) {
            super(properties, IPinfoASN.class);
        }

        @Override
        protected Map<String, Object> transform(final Result<IPinfoASN> result) {
            IPinfoASN response = result.result;
            Long asn = response.asn;
            String organizationName = response.name;
            String network = result.network;

            Map<String, Object> data = new HashMap<>();
            for (Database.Property property : this.properties) {
                switch (property) {
                    case IP -> data.put("ip", result.ip);
                    case ASN -> {
                        if (asn != null) {
                            data.put("asn", asn);
                        }
                    }
                    case ORGANIZATION_NAME -> {
                        if (organizationName != null) {
                            data.put("organization_name", organizationName);
                        }
                    }
                    case NETWORK -> {
                        if (network != null) {
                            data.put("network", network);
                        }
                    }
                    case COUNTRY_ISO_CODE -> {
                        if (response.country != null) {
                            data.put("country_iso_code", response.country);
                        }
                    }
                    case DOMAIN -> {
                        if (response.domain != null) {
                            data.put("domain", response.domain);
                        }
                    }
                    case TYPE -> {
                        if (response.type != null) {
                            data.put("type", response.type);
                        }
                    }
                }
            }
            return data;
        }
    }

    static class City extends AbstractBase<IPinfoGeolocation> {
        City(final Set<Database.Property> properties) {
            super(properties, IPinfoGeolocation.class);
        }

        @Override
        protected Map<String, Object> transform(final Result<IPinfoGeolocation> result) {
            IPinfoGeolocation response = result.result;

            Map<String, Object> data = new HashMap<>();
            for (Database.Property property : this.properties) {
                switch (property) {
                    case IP -> data.put("ip", result.ip);
                    case COUNTRY_ISO_CODE -> {
                        String countryIsoCode = response.country;
                        if (countryIsoCode != null) {
                            data.put("country_iso_code", countryIsoCode);
                        }
                    }
                    case REGION_NAME -> {
                        String subdivisionName = response.region;
                        if (subdivisionName != null) {
                            data.put("region_name", subdivisionName);
                        }
                    }
                    case CITY_NAME -> {
                        String cityName = response.city;
                        if (cityName != null) {
                            data.put("city_name", cityName);
                        }
                    }
                    case TIMEZONE -> {
                        String locationTimeZone = response.timezone;
                        if (locationTimeZone != null) {
                            data.put("timezone", locationTimeZone);
                        }
                    }
                    case LOCATION -> {
                        Double latitude = response.latitude;
                        Double longitude = response.longitude;
                        if (latitude != null && longitude != null) {
                            Map<String, Object> locationObject = new HashMap<>();
                            locationObject.put("lat", latitude);
                            locationObject.put("lon", longitude);
                            data.put("location", locationObject);
                        }
                    }
                }
            }
            return data;
        }
    }

    static class Country extends AbstractBase<IPinfoCountry> {
        Country(Set<Database.Property> properties) {
            super(properties, IPinfoCountry.class);
        }

        @Override
        protected Map<String, Object> transform(final Result<IPinfoCountry> result) {
            IPinfoCountry response = result.result;

            Map<String, Object> data = new HashMap<>();
            for (Database.Property property : this.properties) {
                switch (property) {
                    case IP -> data.put("ip", result.ip);
                    case COUNTRY_ISO_CODE -> {
                        String countryIsoCode = response.country;
                        if (countryIsoCode != null) {
                            data.put("country_iso_code", countryIsoCode);
                        }
                    }
                    case COUNTRY_NAME -> {
                        String countryName = response.countryName;
                        if (countryName != null) {
                            data.put("country_name", countryName);
                        }
                    }
                    case CONTINENT_CODE -> {
                        String continentCode = response.continent;
                        if (continentCode != null) {
                            data.put("continent_code", continentCode);
                        }
                    }
                    case CONTINENT_NAME -> {
                        String continentName = response.continentName;
                        if (continentName != null) {
                            data.put("continent_name", continentName);
                        }
                    }
                }
            }
            return data;
        }
    }

    static class PrivacyDetection extends AbstractBase<IPinfoPrivacyDetection> {
        PrivacyDetection(Set<Database.Property> properties) {
            super(properties, IPinfoPrivacyDetection.class);
        }

        @Override
        protected Map<String, Object> transform(final Result<IPinfoPrivacyDetection> result) {
            IPinfoPrivacyDetection response = result.result;

            Map<String, Object> data = new HashMap<>();
            for (Database.Property property : this.properties) {
                switch (property) {
                    case IP -> data.put("ip", result.ip);
                    case HOSTING_PROVIDER -> {
                        if (response.hosting != null) {
                            data.put("hosting_provider", response.hosting); // TODO name in response doc
                        }
                    }
                    case TOR_EXIT_NODE -> {
                        if (response.tor != null) {
                            data.put("tor_exit_node", response.tor); // TODO name in response doc
                        }
                    }
                    case PROXY -> {
                        if (response.proxy != null) {
                            data.put("proxy", response.proxy);
                        }
                    }
                    case RELAY -> {
                        if (response.relay != null) {
                            data.put("relay", response.relay);
                        }
                    }
                    case VPN -> {
                        if (response.vpn != null) {
                            data.put("vpn", response.vpn);
                        }
                    }
                    case SERVICE -> {
                        if (Strings.hasText(response.service)) {
                            data.put("service", response.service);
                        }
                    }
                }
            }
            return data;
        }
    }

    /**
     * Just a little record holder -- there's the data that we receive via the binding to our record objects from the Reader via the
     * getRecord call, but then we also need to capture the passed-in ip address that came from the caller as well as the network for
     * the returned DatabaseRecord from the Reader.
     */
    public record Result<T>(T result, String ip, String network) {}

    /**
     * The {@code MaxMindGeoDataLookups.AbstractBase} is an abstract base implementation of {@link IpDataLookup} that
     * provides common functionality for getting a specific kind of IPinfoBaseClass from a {@link IpDatabase}.
     *
     * @param <RESPONSE> the intermediate type of IPinfoBaseClass
     */
    private abstract static class AbstractBase<RESPONSE /*extends some IPinfoBaseClass*/> implements IpDataLookup {

        protected final Set<Database.Property> properties;
        protected final Class<RESPONSE> clazz;

        AbstractBase(final Set<Database.Property> properties, final Class<RESPONSE> clazz) {
            this.properties = Set.copyOf(properties);
            this.clazz = clazz;
        }

        @Override
        public Set<Database.Property> getProperties() {
            return this.properties;
        }

        @Override
        public final Map<String, Object> get(final IpDatabase ipDatabase, final String ipAddress) {
            final Result<RESPONSE> response = ipDatabase.getResponse(ipAddress, this::lookup);
            return (response == null || response.result == null) ? Map.of() : transform(response);
        }

        @Nullable
        private Result<RESPONSE> lookup(final Reader reader, final String ipAddress) throws IOException {
            final InetAddress ip = InetAddresses.forString(ipAddress);
            final DatabaseRecord<RESPONSE> record = reader.getRecord(ip, clazz);
            final RESPONSE data = record.getData();
            return (data == null) ? null : new Result<>(data, NetworkAddress.format(ip), record.getNetwork().toString());
        }

        /**
         * Extract the configured properties from the retrieved response
         * @param response the non-null response that was retrieved
         * @return a mapping of properties for the ip from the response
         */
        protected abstract Map<String, Object> transform(Result<RESPONSE> response);
    }
}
