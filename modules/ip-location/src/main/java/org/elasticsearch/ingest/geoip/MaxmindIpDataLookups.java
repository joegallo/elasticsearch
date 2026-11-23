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
import com.maxmind.db.Network;
import com.maxmind.db.Reader;
import com.maxmind.geoip2.model.AnonymousIpResponse;
import com.maxmind.geoip2.model.AsnResponse;
import com.maxmind.geoip2.model.CityResponse;
import com.maxmind.geoip2.model.ConnectionTypeResponse;
import com.maxmind.geoip2.model.CountryResponse;
import com.maxmind.geoip2.model.DomainResponse;
import com.maxmind.geoip2.model.EnterpriseResponse;
import com.maxmind.geoip2.model.IspResponse;
import com.maxmind.geoip2.record.Continent;
import com.maxmind.geoip2.record.Location;
import com.maxmind.geoip2.record.Postal;
import com.maxmind.geoip2.record.Subdivision;
import com.maxmind.geoip2.record.Traits;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.network.InetAddresses;
import org.elasticsearch.common.network.NetworkAddress;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.ingest.geoip.InternalIpDataLookup.Result;
import org.elasticsearch.iplocation.api.DatabaseProperty;
import org.elasticsearch.iplocation.api.IpLocationInfoCollector;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * A collection of {@link InternalIpDataLookup} implementations for MaxMind databases
 */
final class MaxmindIpDataLookups {

    private MaxmindIpDataLookups() {
        // utility class
    }

    private static final Logger logger = LogManager.getLogger(MaxmindIpDataLookups.class);

    // the actual prefixes from the metadata are cased like the literal strings, but
    // prefix dispatch and checks case-insensitive, so the actual constants are lowercase
    static final String GEOIP2_PREFIX = "GeoIP2".toLowerCase(Locale.ROOT);
    static final String GEOLITE2_PREFIX = "GeoLite2".toLowerCase(Locale.ROOT);

    // note: the secondary dispatch on suffix happens to be case-sensitive
    private static final String CITY_DB_SUFFIX = "-City";
    private static final String COUNTRY_DB_SUFFIX = "-Country";
    private static final String ASN_DB_SUFFIX = "-ASN";
    private static final String ANONYMOUS_IP_DB_SUFFIX = "-Anonymous-IP";
    private static final String CONNECTION_TYPE_DB_SUFFIX = "-Connection-Type";
    private static final String DOMAIN_DB_SUFFIX = "-Domain";
    private static final String ENTERPRISE_DB_SUFFIX = "-Enterprise";
    private static final String ISP_DB_SUFFIX = "-ISP";

    @Nullable
    static Database getMaxmindDatabase(final String databaseType) {
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
            // no match was found
            logger.trace("returning null for unsupported database_type [{}]", databaseType);
            return null;
        }
    }

    @Nullable
    static Function<Set<DatabaseProperty>, InternalIpDataLookup> getMaxmindLookup(final Database database) {
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

    static class AnonymousIp extends SimpleBase<AnonymousIpResponse> {
        AnonymousIp(final Set<DatabaseProperty> properties) {
            super(properties, AnonymousIpResponse.class, AnonymousIpResponse::network);
        }

        @Override
        protected void transform(final Result<AnonymousIpResponse> result, final IpLocationInfoCollector collector) {
            String ip = result.ip();
            AnonymousIpResponse response = result.result();

            for (DatabaseProperty property : this.properties) {
                switch (property) {
                    case IP -> collector.ip(ip);
                    case HOSTING_PROVIDER -> collector.hostingProvider(response.isHostingProvider());
                    case TOR_EXIT_NODE -> collector.torExitNode(response.isTorExitNode());
                    case ANONYMOUS_VPN -> collector.anonymousVpn(response.isAnonymousVpn());
                    case ANONYMOUS -> collector.anonymous(response.isAnonymous());
                    case PUBLIC_PROXY -> collector.publicProxy(response.isPublicProxy());
                    case RESIDENTIAL_PROXY -> collector.residentialProxy(response.isResidentialProxy());
                }
            }
        }
    }

    static class Asn extends SimpleBase<AsnResponse> {
        Asn(Set<DatabaseProperty> properties) {
            super(properties, AsnResponse.class, AsnResponse::network);
        }

        @Override
        protected void transform(final Result<AsnResponse> result, final IpLocationInfoCollector collector) {
            String ip = result.ip();
            String network = result.network();
            AsnResponse response = result.result();
            Long asn = response.autonomousSystemNumber();
            String organizationName = response.autonomousSystemOrganization();

            for (DatabaseProperty property : this.properties) {
                switch (property) {
                    case IP -> collector.ip(ip);
                    case ASN -> {
                        if (asn != null) collector.asn(asn);
                    }
                    case ORGANIZATION_NAME -> {
                        if (organizationName != null) collector.organizationName(organizationName);
                    }
                    case NETWORK -> {
                        if (network != null) collector.network(network);
                    }
                }
            }
        }
    }

    record CacheableCityResponse(
        Boolean isInEuropeanUnion,
        String countryIsoCode,
        String countryName,
        String continentCode,
        String continentName,
        String regionIsoCode,
        String regionName,
        String cityName,
        String timezone,
        Double latitude,
        Double longitude,
        Integer accuracyRadius,
        String postalCode,
        Boolean registeredCountryIsInEuropeanUnion,
        String registeredCountryIsoCode,
        String registeredCountryName
    ) {}

    static class City extends AbstractBase<CityResponse, Result<CacheableCityResponse>> {
        City(final Set<DatabaseProperty> properties) {
            super(properties, CityResponse.class);
        }

        @Override
        protected Result<CacheableCityResponse> cacheableRecord(CityResponse response, String ip) {
            // the copy constructor binds the locales, which the localized names below depend on
            final CityResponse localized = new CityResponse(response, List.of("en"));
            final com.maxmind.geoip2.record.Country country = localized.country();
            final Continent continent = localized.continent();
            final Subdivision subdivision = localized.mostSpecificSubdivision();
            final com.maxmind.geoip2.record.City city = localized.city();
            final Location location = localized.location();
            final Postal postal = localized.postal();
            final com.maxmind.geoip2.record.Country registeredCountry = localized.registeredCountry();

            return new Result<>(
                new CacheableCityResponse(
                    isInEuropeanUnion(country),
                    country.isoCode(),
                    country.name(),
                    continent.code(),
                    continent.name(),
                    regionIsoCode(country, subdivision),
                    subdivision.name(),
                    city.name(),
                    location.timeZone(),
                    location.latitude(),
                    location.longitude(),
                    location.accuracyRadius(),
                    postal.code(),
                    isInEuropeanUnion(registeredCountry),
                    registeredCountry.isoCode(),
                    registeredCountry.name()
                ),
                ip,
                networkString(localized.traits().network())
            );
        }

        @Override
        protected void transform(final Result<CacheableCityResponse> result, final IpLocationInfoCollector collector) {
            String ip = result.ip();
            CacheableCityResponse response = result.result();

            for (DatabaseProperty property : this.properties) {
                switch (property) {
                    case IP -> collector.ip(ip);
                    case COUNTRY_IN_EUROPEAN_UNION -> {
                        if (response.isInEuropeanUnion != null) collector.countryInEuropeanUnion(response.isInEuropeanUnion);
                    }
                    case COUNTRY_ISO_CODE -> {
                        if (response.countryIsoCode != null) collector.countryIsoCode(response.countryIsoCode);
                    }
                    case COUNTRY_NAME -> {
                        if (response.countryName != null) collector.countryName(response.countryName);
                    }
                    case CONTINENT_CODE -> {
                        if (response.continentCode != null) collector.continentCode(response.continentCode);
                    }
                    case CONTINENT_NAME -> {
                        if (response.continentName != null) collector.continentName(response.continentName);
                    }
                    case REGION_ISO_CODE -> {
                        if (response.regionIsoCode != null) collector.regionIsoCode(response.regionIsoCode);
                    }
                    case REGION_NAME -> {
                        if (response.regionName != null) collector.regionName(response.regionName);
                    }
                    case CITY_NAME -> {
                        if (response.cityName != null) collector.cityName(response.cityName);
                    }
                    case TIMEZONE -> {
                        if (response.timezone != null) collector.timezone(response.timezone);
                    }
                    case LOCATION -> {
                        if (response.latitude != null && response.longitude != null) {
                            collector.location(response.latitude, response.longitude);
                        }
                    }
                    case ACCURACY_RADIUS -> {
                        if (response.accuracyRadius != null) collector.accuracyRadius(response.accuracyRadius);
                    }
                    case POSTAL_CODE -> {
                        if (response.postalCode != null) collector.postalCode(response.postalCode);
                    }
                    case REGISTERED_COUNTRY_IN_EUROPEAN_UNION -> {
                        if (response.registeredCountryIsInEuropeanUnion != null) {
                            collector.registeredCountryInEuropeanUnion(response.registeredCountryIsInEuropeanUnion);
                        }
                    }
                    case REGISTERED_COUNTRY_ISO_CODE -> {
                        if (response.registeredCountryIsoCode != null) collector.registeredCountryIsoCode(
                            response.registeredCountryIsoCode
                        );
                    }
                    case REGISTERED_COUNTRY_NAME -> {
                        if (response.registeredCountryName != null) collector.registeredCountryName(response.registeredCountryName);
                    }
                }
            }
        }
    }

    static class ConnectionType extends SimpleBase<ConnectionTypeResponse> {
        ConnectionType(final Set<DatabaseProperty> properties) {
            super(properties, ConnectionTypeResponse.class, ConnectionTypeResponse::network);
        }

        @Override
        protected void transform(final Result<ConnectionTypeResponse> result, final IpLocationInfoCollector collector) {
            String ip = result.ip();
            ConnectionTypeResponse response = result.result();
            ConnectionTypeResponse.ConnectionType connectionType = response.connectionType();

            for (DatabaseProperty property : this.properties) {
                switch (property) {
                    case IP -> collector.ip(ip);
                    case CONNECTION_TYPE -> {
                        if (connectionType != null) collector.connectionType(connectionType.toString());
                    }
                }
            }
        }
    }

    record CacheableCountryResponse(
        Boolean isInEuropeanUnion,
        String countryIsoCode,
        String countryName,
        String continentCode,
        String continentName,
        Boolean registeredCountryIsInEuropeanUnion,
        String registeredCountryIsoCode,
        String registeredCountryName
    ) {}

    static class Country extends AbstractBase<CountryResponse, Result<CacheableCountryResponse>> {
        Country(final Set<DatabaseProperty> properties) {
            super(properties, CountryResponse.class);
        }

        @Override
        protected Result<CacheableCountryResponse> cacheableRecord(CountryResponse response, String ip) {
            // the copy constructor binds the locales, which the localized names below depend on
            final CountryResponse localized = new CountryResponse(response, List.of("en"));
            final com.maxmind.geoip2.record.Country country = localized.country();
            final Continent continent = localized.continent();
            final com.maxmind.geoip2.record.Country registeredCountry = localized.registeredCountry();
            return new Result<>(
                new CacheableCountryResponse(
                    isInEuropeanUnion(country),
                    country.isoCode(),
                    country.name(),
                    continent.code(),
                    continent.name(),
                    isInEuropeanUnion(registeredCountry),
                    registeredCountry.isoCode(),
                    registeredCountry.name()
                ),
                ip,
                networkString(localized.traits().network())
            );
        }

        @Override
        protected void transform(final Result<CacheableCountryResponse> result, final IpLocationInfoCollector collector) {
            String ip = result.ip();
            CacheableCountryResponse response = result.result();

            for (DatabaseProperty property : this.properties) {
                switch (property) {
                    case IP -> collector.ip(ip);
                    case COUNTRY_IN_EUROPEAN_UNION -> {
                        if (response.isInEuropeanUnion != null) collector.countryInEuropeanUnion(response.isInEuropeanUnion);
                    }
                    case COUNTRY_ISO_CODE -> {
                        if (response.countryIsoCode != null) collector.countryIsoCode(response.countryIsoCode);
                    }
                    case COUNTRY_NAME -> {
                        if (response.countryName != null) collector.countryName(response.countryName);
                    }
                    case CONTINENT_CODE -> {
                        if (response.continentCode != null) collector.continentCode(response.continentCode);
                    }
                    case CONTINENT_NAME -> {
                        if (response.continentName != null) collector.continentName(response.continentName);
                    }
                    case REGISTERED_COUNTRY_IN_EUROPEAN_UNION -> {
                        if (response.registeredCountryIsInEuropeanUnion != null) {
                            collector.registeredCountryInEuropeanUnion(response.registeredCountryIsInEuropeanUnion);
                        }
                    }
                    case REGISTERED_COUNTRY_ISO_CODE -> {
                        if (response.registeredCountryIsoCode != null) collector.registeredCountryIsoCode(
                            response.registeredCountryIsoCode
                        );
                    }
                    case REGISTERED_COUNTRY_NAME -> {
                        if (response.registeredCountryName != null) collector.registeredCountryName(response.registeredCountryName);
                    }
                }
            }
        }
    }

    static class Domain extends SimpleBase<DomainResponse> {
        Domain(final Set<DatabaseProperty> properties) {
            super(properties, DomainResponse.class, DomainResponse::network);
        }

        @Override
        protected void transform(final Result<DomainResponse> result, final IpLocationInfoCollector collector) {
            String ip = result.ip();
            DomainResponse response = result.result();
            String domain = response.domain();

            for (DatabaseProperty property : this.properties) {
                switch (property) {
                    case IP -> collector.ip(ip);
                    case DOMAIN -> {
                        if (domain != null) collector.domain(domain);
                    }
                }
            }
        }
    }

    record CacheableEnterpriseResponse(
        Integer countryConfidence,
        Boolean isInEuropeanUnion,
        String countryIsoCode,
        String countryName,
        String continentCode,
        String continentName,
        String regionIsoCode,
        String regionName,
        Integer cityConfidence,
        String cityName,
        String timezone,
        Double latitude,
        Double longitude,
        Integer accuracyRadius,
        String postalCode,
        Integer postalConfidence,
        Long asn,
        String organizationName,
        boolean isHostingProvider,
        boolean isTorExitNode,
        boolean isAnonymousVpn,
        boolean isAnonymous,
        boolean isPublicProxy,
        boolean isResidentialProxy,
        String domain,
        String isp,
        String ispOrganization,
        String mobileCountryCode,
        String mobileNetworkCode,
        String userType,
        String connectionType,
        Boolean registeredCountryIsInEuropeanUnion,
        String registeredCountryIsoCode,
        String registeredCountryName
    ) {}

    static class Enterprise extends AbstractBase<EnterpriseResponse, Result<CacheableEnterpriseResponse>> {
        Enterprise(final Set<DatabaseProperty> properties) {
            super(properties, EnterpriseResponse.class);
        }

        @Override
        protected Result<CacheableEnterpriseResponse> cacheableRecord(EnterpriseResponse response, String ip) {
            // the copy constructor binds the locales, which the localized names below depend on
            final EnterpriseResponse localized = new EnterpriseResponse(response, List.of("en"));
            final com.maxmind.geoip2.record.Country country = localized.country();
            final Continent continent = localized.continent();
            final Subdivision subdivision = localized.mostSpecificSubdivision();
            final com.maxmind.geoip2.record.City city = localized.city();
            final Location location = localized.location();
            final Postal postal = localized.postal();
            final com.maxmind.geoip2.record.Country registeredCountry = localized.registeredCountry();
            final Traits traits = localized.traits();

            return new Result<>(
                new CacheableEnterpriseResponse(
                    country.confidence(),
                    isInEuropeanUnion(country),
                    country.isoCode(),
                    country.name(),
                    continent.code(),
                    continent.name(),
                    regionIsoCode(country, subdivision),
                    subdivision.name(),
                    city.confidence(),
                    city.name(),
                    location.timeZone(),
                    location.latitude(),
                    location.longitude(),
                    location.accuracyRadius(),
                    postal.code(),
                    postal.confidence(),
                    traits.autonomousSystemNumber(),
                    traits.autonomousSystemOrganization(),
                    traits.isHostingProvider(),
                    traits.isTorExitNode(),
                    traits.isAnonymousVpn(),
                    traits.isAnonymous(),
                    traits.isPublicProxy(),
                    traits.isResidentialProxy(),
                    traits.domain(),
                    traits.isp(),
                    traits.organization(),
                    traits.mobileCountryCode(),
                    traits.mobileNetworkCode(),
                    traits.userType(),
                    traits.connectionType() == null ? null : traits.connectionType().toString(),
                    isInEuropeanUnion(registeredCountry),
                    registeredCountry.isoCode(),
                    registeredCountry.name()
                ),
                ip,
                networkString(traits.network())
            );
        }

        @Override
        protected void transform(final Result<CacheableEnterpriseResponse> result, final IpLocationInfoCollector collector) {
            String ip = result.ip();
            String network = result.network();
            CacheableEnterpriseResponse response = result.result();

            for (DatabaseProperty property : this.properties) {
                switch (property) {
                    case IP -> collector.ip(ip);
                    case COUNTRY_CONFIDENCE -> {
                        if (response.countryConfidence != null) collector.countryConfidence(response.countryConfidence);
                    }
                    case COUNTRY_IN_EUROPEAN_UNION -> {
                        if (response.isInEuropeanUnion != null) collector.countryInEuropeanUnion(response.isInEuropeanUnion);
                    }
                    case COUNTRY_ISO_CODE -> {
                        if (response.countryIsoCode != null) collector.countryIsoCode(response.countryIsoCode);
                    }
                    case COUNTRY_NAME -> {
                        if (response.countryName != null) collector.countryName(response.countryName);
                    }
                    case CONTINENT_CODE -> {
                        if (response.continentCode != null) collector.continentCode(response.continentCode);
                    }
                    case CONTINENT_NAME -> {
                        if (response.continentName != null) collector.continentName(response.continentName);
                    }
                    case REGION_ISO_CODE -> {
                        if (response.regionIsoCode != null) collector.regionIsoCode(response.regionIsoCode);
                    }
                    case REGION_NAME -> {
                        if (response.regionName != null) collector.regionName(response.regionName);
                    }
                    case CITY_CONFIDENCE -> {
                        if (response.cityConfidence != null) collector.cityConfidence(response.cityConfidence);
                    }
                    case CITY_NAME -> {
                        if (response.cityName != null) collector.cityName(response.cityName);
                    }
                    case TIMEZONE -> {
                        if (response.timezone != null) collector.timezone(response.timezone);
                    }
                    case LOCATION -> {
                        if (response.latitude != null && response.longitude != null) {
                            collector.location(response.latitude, response.longitude);
                        }
                    }
                    case ACCURACY_RADIUS -> {
                        if (response.accuracyRadius != null) collector.accuracyRadius(response.accuracyRadius);
                    }
                    case POSTAL_CODE -> {
                        if (response.postalCode != null) collector.postalCode(response.postalCode);
                    }
                    case POSTAL_CONFIDENCE -> {
                        if (response.postalConfidence != null) collector.postalConfidence(response.postalConfidence);
                    }
                    case ASN -> {
                        if (response.asn != null) collector.asn(response.asn);
                    }
                    case ORGANIZATION_NAME -> {
                        if (response.organizationName != null) collector.organizationName(response.organizationName);
                    }
                    case NETWORK -> {
                        if (network != null) collector.network(network);
                    }
                    case HOSTING_PROVIDER -> collector.hostingProvider(response.isHostingProvider);
                    case TOR_EXIT_NODE -> collector.torExitNode(response.isTorExitNode);
                    case ANONYMOUS_VPN -> collector.anonymousVpn(response.isAnonymousVpn);
                    case ANONYMOUS -> collector.anonymous(response.isAnonymous);
                    case PUBLIC_PROXY -> collector.publicProxy(response.isPublicProxy);
                    case RESIDENTIAL_PROXY -> collector.residentialProxy(response.isResidentialProxy);
                    case DOMAIN -> {
                        if (response.domain != null) collector.domain(response.domain);
                    }
                    case ISP -> {
                        if (response.isp != null) collector.isp(response.isp);
                    }
                    case ISP_ORGANIZATION_NAME -> {
                        if (response.ispOrganization != null) collector.ispOrganizationName(response.ispOrganization);
                    }
                    case MOBILE_COUNTRY_CODE -> {
                        if (response.mobileCountryCode != null) collector.mobileCountryCode(response.mobileCountryCode);
                    }
                    case MOBILE_NETWORK_CODE -> {
                        if (response.mobileNetworkCode != null) collector.mobileNetworkCode(response.mobileNetworkCode);
                    }
                    case USER_TYPE -> {
                        if (response.userType != null) collector.userType(response.userType);
                    }
                    case CONNECTION_TYPE -> {
                        if (response.connectionType != null) collector.connectionType(response.connectionType);
                    }
                    case REGISTERED_COUNTRY_IN_EUROPEAN_UNION -> {
                        if (response.registeredCountryIsInEuropeanUnion != null) {
                            collector.registeredCountryInEuropeanUnion(response.registeredCountryIsInEuropeanUnion);
                        }
                    }
                    case REGISTERED_COUNTRY_ISO_CODE -> {
                        if (response.registeredCountryIsoCode != null) collector.registeredCountryIsoCode(
                            response.registeredCountryIsoCode
                        );
                    }
                    case REGISTERED_COUNTRY_NAME -> {
                        if (response.registeredCountryName != null) collector.registeredCountryName(response.registeredCountryName);
                    }
                }
            }
        }
    }

    static class Isp extends SimpleBase<IspResponse> {
        Isp(final Set<DatabaseProperty> properties) {
            super(properties, IspResponse.class, IspResponse::network);
        }

        @Override
        protected void transform(final Result<IspResponse> result, final IpLocationInfoCollector collector) {
            String ip = result.ip();
            String network = result.network();
            IspResponse response = result.result();
            String isp = response.isp();
            String ispOrganization = response.organization();
            String mobileNetworkCode = response.mobileNetworkCode();
            String mobileCountryCode = response.mobileCountryCode();
            Long asn = response.autonomousSystemNumber();
            String organizationName = response.autonomousSystemOrganization();

            for (DatabaseProperty property : this.properties) {
                switch (property) {
                    case IP -> collector.ip(ip);
                    case ASN -> {
                        if (asn != null) collector.asn(asn);
                    }
                    case ORGANIZATION_NAME -> {
                        if (organizationName != null) collector.organizationName(organizationName);
                    }
                    case NETWORK -> {
                        if (network != null) collector.network(network);
                    }
                    case ISP -> {
                        if (isp != null) collector.isp(isp);
                    }
                    case ISP_ORGANIZATION_NAME -> {
                        if (ispOrganization != null) collector.ispOrganizationName(ispOrganization);
                    }
                    case MOBILE_COUNTRY_CODE -> {
                        if (mobileCountryCode != null) collector.mobileCountryCode(mobileCountryCode);
                    }
                    case MOBILE_NETWORK_CODE -> {
                        if (mobileNetworkCode != null) collector.mobileNetworkCode(mobileNetworkCode);
                    }
                }
            }
        }
    }

    /**
     * The {@link MaxmindIpDataLookups.AbstractBase} is an abstract base implementation of {@link InternalIpDataLookup} that
     * provides common functionality for getting a specific kind of response from a {@link IpDatabase}.
     *
     * @param <RESPONSE> the intermediate type of response
     */
    private abstract static class AbstractBase<RESPONSE, RECORD> implements InternalIpDataLookup {

        protected final Set<DatabaseProperty> properties;
        protected final Class<RESPONSE> clazz;

        AbstractBase(final Set<DatabaseProperty> properties, final Class<RESPONSE> clazz) {
            this.properties = Set.copyOf(properties);
            this.clazz = clazz;
        }

        @Override
        public Set<DatabaseProperty> getProperties() {
            return this.properties;
        }

        @Override
        public final boolean getData(final IpDatabase ipDatabase, final String ipAddress, final IpLocationInfoCollector collector) {
            final RECORD response = ipDatabase.getResponse(ipAddress, this::lookup);
            if (response == null) {
                return false;
            }
            transform(response, collector);
            return true;
        }

        @Nullable
        private RECORD lookup(final Reader reader, final String ipAddress) throws IOException {
            final InetAddress ip = InetAddresses.forString(ipAddress);
            final DatabaseRecord<RESPONSE> entry = reader.getRecord(ip, clazz);
            final RESPONSE data = entry.data();
            // the canonical string form of the ip is computed once here, so that it is cached along with the record
            return (data == null) ? null : cacheableRecord(data, NetworkAddress.format(ip));
        }

        /**
         * Given a fully-populated response object, create a record that is suitable for caching. If the fully-populated response object
         * itself is suitable for caching, then it is acceptable to simply return it.
         *
         * @param response the fully-populated response
         * @param ip the canonical string form of the looked-up ip address, which is worth caching alongside the record
         */
        protected abstract RECORD cacheableRecord(RESPONSE response, String ip);

        /**
         * Extract the configured properties from the retrieved response and push them to the collector.
         *
         * @param response the non-null response that was retrieved
         * @param collector the collector to push results to
         */
        protected abstract void transform(RECORD response, IpLocationInfoCollector collector);
    }

    /**
     * The {@link MaxmindIpDataLookups.SimpleBase} is the base for lookups whose MaxMind response is already suitable for caching as-is,
     * so the only thing to add is the canonical ip and the network, both as strings so that they are not rendered again on every
     * cache hit.
     *
     * @param <RESPONSE> the type of response, which is also what is cached
     */
    private abstract static class SimpleBase<RESPONSE> extends AbstractBase<RESPONSE, Result<RESPONSE>> {

        private final Function<RESPONSE, Network> networkAccessor;

        SimpleBase(final Set<DatabaseProperty> properties, final Class<RESPONSE> clazz, final Function<RESPONSE, Network> networkAccessor) {
            super(properties, clazz);
            this.networkAccessor = networkAccessor;
        }

        @Override
        protected final Result<RESPONSE> cacheableRecord(final RESPONSE response, final String ip) {
            return new Result<>(response, ip, networkString(networkAccessor.apply(response)));
        }
    }

    @Nullable
    private static String networkString(@Nullable final Network network) {
        return (network == null) ? null : network.toString();
    }

    @Nullable
    private static Boolean isInEuropeanUnion(com.maxmind.geoip2.record.Country country) {
        // isInEuropeanUnion is a lowercase-b boolean so it cannot be null, but it really only makes sense for us to return a value
        // for this if there's actually a real country here, as opposed to an empty null-object country, so we check for an iso code first
        return (country.isoCode() == null) ? null : country.isInEuropeanUnion();
    }

    @Nullable
    private static String regionIsoCode(final com.maxmind.geoip2.record.Country country, final Subdivision subdivision) {
        // ISO 3166-2 code for country subdivisions, see https://www.iso.org/iso-3166-country-codes.html
        final String countryIso = country.isoCode();
        final String subdivisionIso = subdivision.isoCode();
        if (countryIso != null && subdivisionIso != null) {
            return countryIso + "-" + subdivisionIso;
        } else {
            return null;
        }
    }
}
