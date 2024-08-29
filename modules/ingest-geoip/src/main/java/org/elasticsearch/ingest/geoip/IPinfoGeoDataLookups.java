/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.ingest.geoip;

import com.maxmind.db.DatabaseRecord;
import com.maxmind.db.MaxMindDbConstructor;
import com.maxmind.db.MaxMindDbParameter;
import com.maxmind.db.Reader;

import org.elasticsearch.common.network.InetAddresses;
import org.elasticsearch.common.network.NetworkAddress;

import java.io.IOException;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A collection of {@link GeoDataLookup} implementations for IPinfo databases
 */
class IPinfoGeoDataLookups {
    private IPinfoGeoDataLookups() {}

    public record IPinfoASN(
        @MaxMindDbParameter(name = "asn") String asn,
        @MaxMindDbParameter(name = "domain") String domain, // what do we want to do with this one?
        @MaxMindDbParameter(name = "name") String name
    ) {
        @MaxMindDbConstructor
        public IPinfoASN {}
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

    static class Asn extends AbstractBase<IPinfoASN> {
        Asn(Set<Database.Property> properties) {
            super(properties, IPinfoASN.class);
        }

        @Override
        protected Map<String, Object> transformResponse(final Result<IPinfoASN> result) {
            IPinfoASN response = result.result;
            String asn = response.asn; // barf, this is a string, we want it to be a number
            String organizationName = response.name;
            String network = result.network;

            Map<String, Object> geoData = new HashMap<>();
            for (Database.Property property : this.properties) {
                switch (property) {
                    case IP -> geoData.put("ip", result.ip);
                    case ASN -> {
                        if (asn != null) {
                            // todo bleh -- can we parse this in advance once? and make it a number?
                            geoData.put("asn", Long.parseLong(asn.replaceAll("AS", "")));
                        }
                    }
                    case ORGANIZATION_NAME -> {
                        if (organizationName != null) {
                            geoData.put("organization_name", organizationName);
                        }
                    }
                    case NETWORK -> {
                        if (network != null) {
                            geoData.put("network", network);
                        }
                    }
                }
            }
            return geoData;
        }
    }

    static class Country extends AbstractBase<IPinfoCountry> {
        Country(Set<Database.Property> properties) {
            super(properties, IPinfoCountry.class);
        }

        @Override
        protected Map<String, Object> transformResponse(final Result<IPinfoCountry> result) {
            IPinfoCountry response = result.result;

            Map<String, Object> geoData = new HashMap<>();
            for (Database.Property property : this.properties) {
                switch (property) {
                    case IP -> geoData.put("ip", result.ip);
                    case COUNTRY_ISO_CODE -> {
                        String countryIsoCode = response.country;
                        if (countryIsoCode != null) {
                            geoData.put("country_iso_code", countryIsoCode);
                        }
                    }
                    case COUNTRY_NAME -> {
                        String countryName = response.countryName;
                        if (countryName != null) {
                            geoData.put("country_name", countryName);
                        }
                    }
                    case CONTINENT_CODE -> {
                        String continentCode = response.continent;
                        if (continentCode != null) {
                            geoData.put("continent_code", continentCode);
                        }
                    }
                    case CONTINENT_NAME -> {
                        String continentName = response.continentName;
                        if (continentName != null) {
                            geoData.put("continent_name", continentName);
                        }
                    }
                }
            }
            return geoData;
        }
    }

    public record Result<T>(T result, String ip, String network) {}

    /**
     * The {@code MaxMindGeoDataLookups.AbstractBase} is an abstract base implementation of {@link GeoDataLookup} that
     * provides common functionality for getting a specific kind of IPinfoBaseClass from a {@link GeoIpDatabase}.
     *
     * @param <RESPONSE> the intermediate type of IPinfoBaseClass
     */
    private abstract static class AbstractBase<RESPONSE /*extends some IPinfoBaseClass*/> implements GeoDataLookup {

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
        public final Map<String, Object> getGeoData(final GeoIpDatabase geoIpDatabase, final String ipAddress) {
            Result<RESPONSE> resp = geoIpDatabase.getResponse(ipAddress, this::lookup);
            if (resp == null || resp.result == null) {
                return Map.of();
            } else {
                return transformResponse(resp);
            }
        }

        protected Optional<Result<RESPONSE>> lookup(Reader reader, String ipAddress) throws IOException {
            InetAddress ip = InetAddresses.forString(ipAddress);
            DatabaseRecord<RESPONSE> record = reader.getRecord(ip, clazz);
            RESPONSE result = record.getData();
            if (result == null) {
                return Optional.empty();
            } else {
                return Optional.of(new Result<>(result, NetworkAddress.format(ip), record.getNetwork().toString()));
            }
        }

        /**
         * Extract the configured properties from the retrieved response
         * @param response the response that was retrieved
         * @return a mapping of properties for the ip from the response
         */
        protected abstract Map<String, Object> transformResponse(Result<RESPONSE> response);
    }
}
