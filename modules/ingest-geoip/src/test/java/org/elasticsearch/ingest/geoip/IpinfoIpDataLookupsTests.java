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
import com.maxmind.db.Networks;
import com.maxmind.db.Reader;

import org.apache.lucene.util.Constants;
import org.elasticsearch.common.network.NetworkAddress;
import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.core.IOUtils;
import org.elasticsearch.core.SuppressForbidden;
import org.elasticsearch.test.ESTestCase;
import org.junit.After;
import org.junit.Before;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import static java.util.Map.entry;
import static org.elasticsearch.ingest.geoip.GeoIpTestUtils.copyDatabase;
import static org.elasticsearch.ingest.geoip.IpinfoIpDataLookups.ipinfoTypeCleanup;
import static org.elasticsearch.ingest.geoip.IpinfoIpDataLookups.parseAsn;
import static org.elasticsearch.ingest.geoip.IpinfoIpDataLookups.parseBoolean;
import static org.elasticsearch.ingest.geoip.IpinfoIpDataLookups.parseLocationDouble;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

public class IpinfoIpDataLookupsTests extends ESTestCase {

    // a temporary directory that mmdb files can be copied to and read from
    private Path tmpDir;

    @Before
    public void setup() {
        tmpDir = createTempDir();
    }

    @After
    public void cleanup() throws IOException {
        IOUtils.rm(tmpDir);
    }

    public void testDatabasePropertyInvariants() {
        // the second ASN variant database is like a specialization of the ASN database
        assertThat(Sets.difference(Database.Asn.properties(), Database.AsnV2.properties()), is(empty()));
        assertThat(Database.Asn.defaultProperties(), equalTo(Database.AsnV2.defaultProperties()));

        // the second City variant database is like a version of the ordinary City database but lacking many fields
        assertThat(Sets.difference(Database.CityV2.properties(), Database.City.properties()), is(empty()));
        assertThat(Sets.difference(Database.CityV2.defaultProperties(), Database.City.defaultProperties()), is(empty()));

        // the second Country variant database is like a version of the ordinary Country database but lacking come fields
        assertThat(Sets.difference(Database.CountryV2.properties(), Database.CountryV2.properties()), is(empty()));
        assertThat(Database.CountryV2.defaultProperties(), equalTo(Database.Country.defaultProperties()));
    }

    public void testParseAsn() {
        // expected case: "AS123" is 123
        assertThat(parseAsn("AS123"), equalTo(123L));
        // defensive cases: null and empty becomes null, this is not expected fwiw
        assertThat(parseAsn(null), nullValue());
        assertThat(parseAsn(""), nullValue());
        // defensive cases: we strip whitespace and ignore case
        assertThat(parseAsn(" as 456  "), equalTo(456L));
        // defensive cases: we ignore the absence of the 'AS' prefix
        assertThat(parseAsn("123"), equalTo(123L));
        // bottom case: a non-parsable string is null
        assertThat(parseAsn("anythingelse"), nullValue());
    }

    public void testParseBoolean() {
        // expected cases: "true" is true and "" is false
        assertThat(parseBoolean("true"), equalTo(true));
        assertThat(parseBoolean(""), equalTo(false));
        assertThat(parseBoolean("false"), equalTo(false)); // future proofing
        // defensive case: null becomes null, this is not expected fwiw
        assertThat(parseBoolean(null), nullValue());
        // defensive cases: we strip whitespace and ignore case
        assertThat(parseBoolean("    "), equalTo(false));
        assertThat(parseBoolean(" TrUe "), equalTo(true));
        assertThat(parseBoolean(" FaLSE "), equalTo(false));
        // bottom case: a non-parsable string is null
        assertThat(parseBoolean(randomAlphaOfLength(8)), nullValue());
    }

    public void testParseLocationDouble() {
        // expected case: "123.45" is 123.45
        assertThat(parseLocationDouble("123.45"), equalTo(123.45));
        // defensive cases: null and empty becomes null, this is not expected fwiw
        assertThat(parseLocationDouble(null), nullValue());
        assertThat(parseLocationDouble(""), nullValue());
        // defensive cases: we strip whitespace
        assertThat(parseLocationDouble("  -123.45  "), equalTo(-123.45));
        // bottom case: a non-parsable string is null
        assertThat(parseLocationDouble("anythingelse"), nullValue());
    }

    public void testAsnFree() {
        assumeFalse("https://github.com/elastic/elasticsearch/issues/114266", Constants.WINDOWS);
        String databaseName = "ip_asn_sample.mmdb";
        String ip = "5.182.109.0";
        assertExpectedLookupResults(
            databaseName,
            ip,
            new IpinfoIpDataLookups.Asn(Database.AsnV2.properties()),
            Map.ofEntries(
                entry("ip", ip),
                entry("organization_name", "M247 Europe SRL"),
                entry("asn", 9009L),
                entry("network", "5.182.109.0/24"),
                entry("domain", "m247.com")
            )
        );
    }

    public void testAsnStandard() {
        assumeFalse("https://github.com/elastic/elasticsearch/issues/114266", Constants.WINDOWS);
        String databaseName = "asn_sample.mmdb";
        String ip = "23.53.116.0";
        assertExpectedLookupResults(
            databaseName,
            ip,
            new IpinfoIpDataLookups.Asn(Database.AsnV2.properties()),
            Map.ofEntries(
                entry("ip", ip),
                entry("organization_name", "Akamai Technologies, Inc."),
                entry("asn", 32787L),
                entry("network", "23.53.116.0/24"),
                entry("domain", "akamai.com"),
                entry("type", "hosting"),
                entry("country_iso_code", "US")
            )
        );
    }

    public void testAsnInvariants() {
        assumeFalse("https://github.com/elastic/elasticsearch/issues/114266", Constants.WINDOWS);
        Path configDir = tmpDir;
        copyDatabase("ipinfo/ip_asn_sample.mmdb", configDir.resolve("ip_asn_sample.mmdb"));
        copyDatabase("ipinfo/asn_sample.mmdb", configDir.resolve("asn_sample.mmdb"));

        {
            final Set<String> expectedColumns = Set.of("network", "asn", "name", "domain");

            Path databasePath = configDir.resolve("ip_asn_sample.mmdb");
            assertDatabaseInvariants(databasePath, (ip, row) -> {
                assertThat(row.keySet(), equalTo(expectedColumns));
                String asn = (String) row.get("asn");
                assertThat(asn, startsWith("AS"));
                assertThat(asn, equalTo(asn.trim()));
                Long parsed = parseAsn(asn);
                assertThat(parsed, notNullValue());
                assertThat(asn, equalTo("AS" + parsed)); // reverse it
            });
        }

        {
            final Set<String> expectedColumns = Set.of("network", "asn", "name", "domain", "country", "type");

            Path databasePath = configDir.resolve("asn_sample.mmdb");
            assertDatabaseInvariants(databasePath, (ip, row) -> {
                assertThat(row.keySet(), equalTo(expectedColumns));
                String asn = (String) row.get("asn");
                assertThat(asn, startsWith("AS"));
                assertThat(asn, equalTo(asn.trim()));
                Long parsed = parseAsn(asn);
                assertThat(parsed, notNullValue());
                assertThat(asn, equalTo("AS" + parsed)); // reverse it
            });
        }
    }

    public void testCountryFree() {
        assumeFalse("https://github.com/elastic/elasticsearch/issues/114266", Constants.WINDOWS);
        String databaseName = "ip_country_sample.mmdb";
        String ip = "4.221.143.168";
        assertExpectedLookupResults(
            databaseName,
            ip,
            new IpinfoIpDataLookups.Country(Database.CountryV2.properties()),
            Map.ofEntries(
                entry("ip", ip),
                entry("country_name", "South Africa"),
                entry("country_iso_code", "ZA"),
                entry("continent_name", "Africa"),
                entry("continent_code", "AF")
            )
        );
    }

    public void testGeolocationStandard() {
        assumeFalse("https://github.com/elastic/elasticsearch/issues/114266", Constants.WINDOWS);
        String databaseName = "ip_geolocation_sample.mmdb";
        String ip = "2.124.90.182";
        assertExpectedLookupResults(
            databaseName,
            ip,
            new IpinfoIpDataLookups.Geolocation(Database.CityV2.properties()),
            Map.ofEntries(
                entry("ip", ip),
                entry("country_iso_code", "GB"),
                entry("region_name", "England"),
                entry("city_name", "London"),
                entry("timezone", "Europe/London"),
                entry("postal_code", "E1W"),
                entry("location", Map.of("lat", 51.50853, "lon", -0.12574))
            )
        );
    }

    public void testGeolocationInvariants() {
        assumeFalse("https://github.com/elastic/elasticsearch/issues/114266", Constants.WINDOWS);
        Path configDir = tmpDir;
        copyDatabase("ipinfo/ip_geolocation_sample.mmdb", configDir.resolve("ip_geolocation_sample.mmdb"));

        {
            final Set<String> expectedColumns = Set.of(
                "network",
                "city",
                "region",
                "country",
                "postal_code",
                "timezone",
                "latitude",
                "longitude"
            );

            Path databasePath = configDir.resolve("ip_geolocation_sample.mmdb");
            assertDatabaseInvariants(databasePath, (ip, row) -> {
                assertThat(row.keySet(), equalTo(expectedColumns));
                {
                    String latitude = (String) row.get("latitude");
                    assertThat(latitude, equalTo(latitude.trim()));
                    Double parsed = parseLocationDouble(latitude);
                    assertThat(parsed, notNullValue());
                    assertThat(latitude, equalTo(Double.toString(parsed))); // reverse it
                }
                {
                    String longitude = (String) row.get("longitude");
                    assertThat(longitude, equalTo(longitude.trim()));
                    Double parsed = parseLocationDouble(longitude);
                    assertThat(parsed, notNullValue());
                    assertThat(longitude, equalTo(Double.toString(parsed))); // reverse it
                }
            });
        }
    }

    public void testPrivacyDetectionStandard() {
        assumeFalse("https://github.com/elastic/elasticsearch/issues/114266", Constants.WINDOWS);
        String databaseName = "privacy_detection_sample.mmdb";
        String ip = "1.53.59.33";
        assertExpectedLookupResults(
            databaseName,
            ip,
            new IpinfoIpDataLookups.PrivacyDetection(Database.PrivacyDetection.properties()),
            Map.ofEntries(
                entry("ip", ip),
                entry("hosting", false),
                entry("proxy", false),
                entry("relay", false),
                entry("tor", false),
                entry("vpn", true)
            )
        );
    }

    public void testPrivacyDetectionStandardNonEmptyService() {
        assumeFalse("https://github.com/elastic/elasticsearch/issues/114266", Constants.WINDOWS);
        String databaseName = "privacy_detection_sample.mmdb";
        String ip = "216.131.74.65";
        assertExpectedLookupResults(
            databaseName,
            ip,
            new IpinfoIpDataLookups.PrivacyDetection(Database.PrivacyDetection.properties()),
            Map.ofEntries(
                entry("ip", ip),
                entry("hosting", true),
                entry("proxy", false),
                entry("service", "FastVPN"),
                entry("relay", false),
                entry("tor", false),
                entry("vpn", true)
            )
        );
    }

    public void testPrivacyDetectionInvariants() {
        assumeFalse("https://github.com/elastic/elasticsearch/issues/114266", Constants.WINDOWS);
        Path configDir = tmpDir;
        copyDatabase("ipinfo/privacy_detection_sample.mmdb", configDir.resolve("privacy_detection_sample.mmdb"));

        {
            final Set<String> expectedColumns = Set.of("network", "service", "hosting", "proxy", "relay", "tor", "vpn");

            Path databasePath = configDir.resolve("privacy_detection_sample.mmdb");
            assertDatabaseInvariants(databasePath, (ip, row) -> {
                assertThat(row.keySet(), equalTo(expectedColumns));

                for (String booleanColumn : Set.of("hosting", "proxy", "relay", "tor", "vpn")) {
                    String bool = (String) row.get(booleanColumn);
                    assertThat(bool, anyOf(equalTo("true"), equalTo(""), equalTo("false")));
                    assertThat(parseBoolean(bool), notNullValue());
                }
            });
        }
    }

    public void testIpinfoTypeCleanup() {
        // database_type strings from upstream:
        // abuse.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo standard_abuse_mmdb_v4.mmdb"), is("abuse_v4"));
        // asn.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo generic_asn_mmdb_v4.mmdb"), is("asn_v4"));
        // carrier.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo standard_carrier_mmdb.mmdb"), is("carrier"));
        // country.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo generic_country_free.mmdb"), is("country"));
        // location_extended_v2.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo extended_location_v2.mmdb"), is("location_v2"));
        // privacy_extended_v2.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo extended_privacy_v2.mmdb"), is("privacy_v2"));
        // standard_company.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo standard_company.mmdb"), is("company"));
        // standard_ip_hosted_domains_sample.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo standard_ip_hosted_domains_sample.mmdb"), is("hosted_domains"));
        // standard_location.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo standard_location_mmdb_v4.mmdb"), is("location_v4"));
        // standard_privacy.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo standard_privacy.mmdb"), is("privacy"));

        // database_type strings from test files:
        // ip_asn_sample.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo ip_asn_sample.mmdb"), is("asn"));
        // ip_country_asn_sample.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo ip_country_asn_sample.mmdb"), is("country_asn"));
        // ip_geolocation_sample.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo ip_geolocation_sample.mmdb"), is("geolocation"));
        // privacy_detection_sample.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo privacy_detection_sample.mmdb"), is("privacy_detection"));
        // abuse_contact_sample.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo abuse_contact_sample.mmdb"), is("abuse_contact"));
        // asn_sample.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo asn_sample.mmdb"), is("asn"));
        // hosted_domains_sample.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo hosted_domains_sample.mmdb"), is("hosted_domains"));
        // ip_asn_sample.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo ip_asn_sample.mmdb"), is("asn"));
        // ip_carrier_sample.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo ip_carrier_sample.mmdb"), is("carrier"));
        // ip_company_sample.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo ip_company_sample.mmdb"), is("company"));
        // ip_country_asn_sample.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo ip_country_asn_sample.mmdb"), is("country_asn"));
        // ip_country_sample.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo ip_country_sample.mmdb"), is("country"));
        // ip_geolocation_extended_ipv4_sample.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo ip_geolocation_extended_ipv4_sample.mmdb"), is("geolocation_ipv4"));
        // ip_geolocation_extended_ipv6_sample.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo ip_geolocation_extended_ipv6_sample.mmdb"), is("geolocation_ipv6"));
        // ip_geolocation_extended_sample.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo ip_geolocation_extended_sample.mmdb"), is("geolocation"));
        // ip_geolocation_sample.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo ip_geolocation_sample.mmdb"), is("geolocation"));
        // ip_rdns_domains_sample.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo ip_rdns_domains_sample.mmdb"), is("rdns_domains"));
        // ip_rdns_hostnames_sample.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo ip_rdns_hostnames_sample.mmdb"), is("rdns_hostnames"));
        // privacy_detection_extended_sample.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo privacy_detection_extended_sample.mmdb"), is("privacy_detection"));
        // privacy_detection_sample.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo privacy_detection_sample.mmdb"), is("privacy_detection"));

        // database_type strings from downloaded (free) files:
        // asn.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo generic_asn_free.mmdb"), is("asn"));
        // country.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo generic_country_free.mmdb"), is("country"));
        // country_asn.mmdb
        assertThat(ipinfoTypeCleanup("ipinfo generic_country_free_country_asn.mmdb"), is("country_country_asn"));
    }

    public void testDatabaseTypeParsing() throws IOException {
        // this test is a little bit overloaded -- it's testing that we're getting the expected sorts of
        // database_type strings from these files, *and* it's also testing that we dispatch on those strings
        // correctly and associated those files with the correct high-level Elasticsearch Database type.
        // down the road it would probably make sense to split these out and find a better home for some of the
        // logic, but for now it's probably more valuable to have the test *somewhere* than to get especially
        // pedantic about where precisely it should be.

        copyDatabase("ipinfo/ip_asn_sample.mmdb", tmpDir.resolve("ip_asn_sample.mmdb"));
        copyDatabase("ipinfo/ip_geolocation_sample.mmdb", tmpDir.resolve("ip_geolocation_sample.mmdb"));
        copyDatabase("ipinfo/asn_sample.mmdb", tmpDir.resolve("asn_sample.mmdb"));
        copyDatabase("ipinfo/ip_country_sample.mmdb", tmpDir.resolve("ip_country_sample.mmdb"));
        copyDatabase("ipinfo/privacy_detection_sample.mmdb", tmpDir.resolve("privacy_detection_sample.mmdb"));

        assertThat(parseDatabaseFromType("ip_asn_sample.mmdb"), is(Database.AsnV2));
        assertThat(parseDatabaseFromType("ip_geolocation_sample.mmdb"), is(Database.CityV2));
        assertThat(parseDatabaseFromType("asn_sample.mmdb"), is(Database.AsnV2));
        assertThat(parseDatabaseFromType("ip_country_sample.mmdb"), is(Database.CountryV2));
        assertThat(parseDatabaseFromType("privacy_detection_sample.mmdb"), is(Database.PrivacyDetection));
    }

    private Database parseDatabaseFromType(String databaseFile) throws IOException {
        return IpDataLookupFactories.getDatabase(MMDBUtil.getDatabaseType(tmpDir.resolve(databaseFile)));
    }

    private static void assertDatabaseInvariants(final Path databasePath, final BiConsumer<InetAddress, Map<String, Object>> rowConsumer) {
        try (Reader reader = new Reader(pathToFile(databasePath))) {
            Networks<?> networks = reader.networks(Map.class);
            while (networks.hasNext()) {
                DatabaseRecord<?> dbr = networks.next();
                InetAddress address = dbr.getNetwork().getNetworkAddress();
                @SuppressWarnings("unchecked")
                Map<String, Object> result = reader.get(address, Map.class);
                try {
                    rowConsumer.accept(address, result);
                } catch (AssertionError e) {
                    fail(e, "Assert failed for address [%s]", NetworkAddress.format(address));
                } catch (Exception e) {
                    fail(e, "Exception handling address [%s]", NetworkAddress.format(address));
                }
            }
        } catch (Exception e) {
            fail(e);
        }
    }

    @SuppressForbidden(reason = "Maxmind API requires java.io.File")
    private static File pathToFile(Path databasePath) {
        return databasePath.toFile();
    }

    private void assertExpectedLookupResults(String databaseName, String ip, IpDataLookup lookup, Map<String, Object> expected) {
        try (DatabaseReaderLazyLoader loader = loader(databaseName)) {
            Map<String, Object> actual = lookup.getData(loader, ip);
            assertThat(
                "The set of keys in the result are not the same as the set of expected keys",
                actual.keySet(),
                containsInAnyOrder(expected.keySet().toArray(new String[0]))
            );
            for (Map.Entry<String, Object> entry : expected.entrySet()) {
                assertThat("Unexpected value for key [" + entry.getKey() + "]", actual.get(entry.getKey()), equalTo(entry.getValue()));
            }
        } catch (AssertionError e) {
            fail(e, "Assert failed for database [%s] with address [%s]", databaseName, ip);
        } catch (Exception e) {
            fail(e, "Exception for database [%s] with address [%s]", databaseName, ip);
        }
    }

    private DatabaseReaderLazyLoader loader(final String databaseName) {
        Path path = tmpDir.resolve(databaseName);
        copyDatabase("ipinfo/" + databaseName, path); // the ipinfo databases are prefixed on the test classpath
        final GeoIpCache cache = new GeoIpCache(1000);
        return new DatabaseReaderLazyLoader(cache, path, null);
    }
}
