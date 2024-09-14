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
import org.elasticsearch.core.Nullable;

import java.io.IOException;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A collection of {@link IpDataLookup} implementations for IPinfo databases
 */
class IPinfoIpDataLookups {
    private IPinfoIpDataLookups() {}

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
            this(Long.parseLong(asn.replaceAll("AS", "")), country, domain, name, type);
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
        @MaxMindDbParameter(name = "city") String city,
        @MaxMindDbParameter(name = "country") String country,
        @MaxMindDbParameter(name = "latitude") String latitude,
        @MaxMindDbParameter(name = "longitude") String longitude,
        // @MaxMindDbParameter(name = "network") String network, // what do we want to do with this one?
        @MaxMindDbParameter(name = "postal_code") String postalCode, // what do we want to do with this one?
        @MaxMindDbParameter(name = "region") String region,
        @MaxMindDbParameter(name = "timezone") String timezone
    ) {
        @MaxMindDbConstructor
        public IPinfoGeolocation {}
    }

    static class Asn extends AbstractBase<IPinfoASN> {
        Asn(Set<Database.Property> properties) {
            super(properties, IPinfoASN.class);
        }

        @Override
        protected Map<String, Object> transformResponse(final Result<IPinfoASN> result) {
            IPinfoASN response = result.result;
            long asn = response.asn;
            String organizationName = response.name;
            String network = result.network;

            Map<String, Object> geoData = new HashMap<>();
            for (Database.Property property : this.properties) {
                switch (property) {
                    case IP -> geoData.put("ip", result.ip);
                    case ASN -> {
                        geoData.put("asn", asn);
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
                    case COUNTRY_ISO_CODE -> {
                        if (response.country != null) {
                            geoData.put("country_iso_code", response.country);
                        }
                    }
                    case DOMAIN -> {
                        if (response.domain != null) {
                            geoData.put("domain", response.domain);
                        }
                    }
                    case TYPE -> {
                        if (response.type != null) {
                            geoData.put("type", response.type);
                        }
                    }
                }
            }
            return geoData;
        }
    }

    static class City extends AbstractBase<IPinfoGeolocation> {
        City(final Set<Database.Property> properties) {
            super(properties, IPinfoGeolocation.class);
        }

        @Override
        protected Map<String, Object> transformResponse(final Result<IPinfoGeolocation> result) {
            IPinfoGeolocation response = result.result;

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
                    case REGION_NAME -> {
                        String subdivisionName = response.region;
                        if (subdivisionName != null) {
                            geoData.put("region_name", subdivisionName);
                        }
                    }
                    case CITY_NAME -> {
                        String cityName = response.city;
                        if (cityName != null) {
                            geoData.put("city_name", cityName);
                        }
                    }
                    case TIMEZONE -> {
                        String locationTimeZone = response.timezone;
                        if (locationTimeZone != null) {
                            geoData.put("timezone", locationTimeZone);
                        }
                    }
                    case LOCATION -> {
                        // todo bleh -- can we parse these in advance once?
                        Double latitude = Double.parseDouble(response.latitude);
                        Double longitude = Double.parseDouble(response.longitude);
                        if (latitude != null && longitude != null) {
                            Map<String, Object> locationObject = new HashMap<>();
                            locationObject.put("lat", latitude);
                            locationObject.put("lon", longitude);
                            geoData.put("location", locationObject);
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
            Result<RESPONSE> resp = ipDatabase.getResponse(ipAddress, this::lookup);
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
