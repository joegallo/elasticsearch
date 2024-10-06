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
import com.maxmind.db.InvalidNetworkException;
import com.maxmind.db.Networks;
import com.maxmind.db.Reader;

import org.elasticsearch.common.network.NetworkAddress;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import static java.util.Map.entry;
import static org.elasticsearch.ingest.geoip.GeoIpTestUtils.copyDatabase;
import static org.elasticsearch.ingest.geoip.IpinfoIpDataLookups.parseAsn;
import static org.elasticsearch.ingest.geoip.IpinfoIpDataLookups.parseBoolean;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

public class IpinfoIpDataLookupsTests extends ESTestCase {

    private ThreadPool threadPool;
    private ResourceWatcherService resourceWatcherService;

    @Before
    public void setup() {
        threadPool = new TestThreadPool(ConfigDatabases.class.getSimpleName());
        Settings settings = Settings.builder().put("resource.reload.interval.high", TimeValue.timeValueMillis(100)).build();
        resourceWatcherService = new ResourceWatcherService(settings, threadPool);
    }

    @After
    public void cleanup() {
        resourceWatcherService.close();
        threadPool.shutdownNow();
    }

    public void testParseAsn() {
        // expected case: "AS123" is 123
        assertThat(parseAsn("AS123"), equalTo(123L));
        // defensive case: null becomes null, this is not expected fwiw
        assertThat(parseAsn(null), nullValue());
        assertThat(parseAsn(""), nullValue());
        // defensive cases: we strip whitespace and ignore case
        assertThat(parseAsn(" as 456  "), equalTo(456L));
        // edge case: any non-parsable string is null
        assertThat(parseAsn("anythingelse"), nullValue());
    }

    public void testParseBoolean() {
        // expected cases: "true" is true and "" is false
        assertThat(parseBoolean(""), equalTo(false));
        assertThat(parseBoolean("true"), equalTo(true));
        // defensive case: null becomes null, this is not expected fwiw
        assertThat(parseBoolean(null), nullValue());
        // defensive cases: we strip whitespace and ignore case
        assertThat(parseBoolean("    "), equalTo(false));
        assertThat(parseBoolean(" TrUe "), equalTo(true));
        assertThat(parseBoolean(" FaLSE "), equalTo(false));
        // edge case: any non-"true" (ignoring whitespace and casing) string is false
        assertThat(parseBoolean(randomAlphaOfLength(5)), nullValue());
    }

    public void testAsn() throws IOException {
        Path configDir = createTempDir();
        // copyDatabase("asn.mmdb", configDir);
        copyDatabase("ip_asn_sample.mmdb", configDir);

        GeoIpCache cache = new GeoIpCache(1000); // real cache to test purging of entries upon a reload
        ConfigDatabases configDatabases = new ConfigDatabases(configDir, cache);
        configDatabases.initialize(resourceWatcherService);

        if (false) {
            DatabaseReaderLazyLoader loader = configDatabases.getDatabase("asn.mmdb");
            IpDataLookup lookup = new IpinfoIpDataLookups.Asn(Set.of(Database.Property.values()));
            Map<String, Object> data = lookup.getData(loader, "64.67.15.209");
            assertThat(
                data,
                equalTo(
                    Map.ofEntries(
                        entry("ip", "64.67.15.209"),
                        entry("organization_name", "PenTeleData Inc."),
                        entry("asn", 3737L),
                        entry("network", "64.67.0.0/16"),
                        entry("domain", "penteledata.net")
                    )
                )
            );
        }

        {
            DatabaseReaderLazyLoader loader = configDatabases.getDatabase("ip_asn_sample.mmdb");
            IpDataLookup lookup = new IpinfoIpDataLookups.Asn(Set.of(Database.Property.values()));
            Map<String, Object> data = lookup.getData(loader, "174.77.179.0");
            assertThat(
                data,
                equalTo(
                    Map.ofEntries(
                        entry("ip", "174.77.179.0"),
                        entry("organization_name", "Cox Communications Inc."),
                        entry("asn", 22773L),
                        entry("network", "174.77.179.0/24"),
                        entry("domain", "cox.com")// ,
                        // entry("country_iso_code", "US"),
                        // entry("type", "isp")
                    )
                )
            );
        }
    }

    public void testAsnInvariants() {
        Path configDir = createTempDir();
        copyDatabase("ip_asn_sample.mmdb", configDir);

        final Set<String> expectedColumns = Set.of("network", "asn", "name", "domain");

        Path databasePath = configDir.resolve("ip_asn_sample.mmdb");
        assertDatabaseInvariants(databasePath, (ip, row) -> {
            assertThat(row.keySet(), equalTo(expectedColumns));
            String asn = (String) row.get("asn");
            assertThat(asn, startsWith("AS"));
            assertThat(asn, equalTo(asn.trim()));
            assertThat(parseAsn(asn), notNullValue());
        });
    }

    public void testGeolocation() throws IOException {
        Path configDir = createTempDir();
        copyDatabase("ip_geolocation_sample.mmdb", configDir);

        GeoIpCache cache = new GeoIpCache(1000); // real cache to test purging of entries upon a reload
        ConfigDatabases configDatabases = new ConfigDatabases(configDir, cache);
        configDatabases.initialize(resourceWatcherService);

        {
            DatabaseReaderLazyLoader loader = configDatabases.getDatabase("ip_geolocation_sample.mmdb");
            IpDataLookup lookup = new IpinfoIpDataLookups.City(Set.of(Database.Property.values()));
            Map<String, Object> data = lookup.getData(loader, "49.237.132.248");
            assertThat(
                data,
                equalTo(
                    Map.ofEntries(
                        entry("ip", "49.237.132.248"),
                        entry("country_iso_code", "TH"),
                        entry("region_name", "Bangkok"),
                        entry("city_name", "Bangkok"),
                        entry("timezone", "Asia/Bangkok"),
                        entry("location", Map.of("lat", 13.75398, "lon", 100.50144))
                    )
                )
            );
        }
    }

    public void testPrivacyDetection() throws IOException {
        Path configDir = createTempDir();
        copyDatabase("privacy_detection_sample.mmdb", configDir);

        GeoIpCache cache = new GeoIpCache(1000); // real cache to test purging of entries upon a reload
        ConfigDatabases configDatabases = new ConfigDatabases(configDir, cache);
        configDatabases.initialize(resourceWatcherService);

        {
            DatabaseReaderLazyLoader loader = configDatabases.getDatabase("privacy_detection_sample.mmdb");
            IpDataLookup lookup = new IpinfoIpDataLookups.PrivacyDetection(Set.of(Database.Property.values()));
            Map<String, Object> data = lookup.getData(loader, "2.139.198.28");
            assertThat(
                data,
                equalTo(
                    Map.ofEntries(
                        entry("ip", "2.139.198.28"),
                        entry("hosting_provider", false),
                        entry("proxy", false),
                        entry("relay", false),
                        entry("tor_exit_node", false),
                        entry("vpn", true)
                    )
                )
            );
        }

        {
            DatabaseReaderLazyLoader loader = configDatabases.getDatabase("privacy_detection_sample.mmdb");
            IpDataLookup lookup = new IpinfoIpDataLookups.PrivacyDetection(Set.of(Database.Property.values()));
            Map<String, Object> data = lookup.getData(loader, "149.50.212.32");
            assertThat(
                data,
                equalTo(
                    Map.ofEntries(
                        entry("ip", "149.50.212.32"),
                        entry("hosting_provider", true),
                        entry("proxy", false),
                        entry("service", "Private Internet Access"),
                        entry("relay", false),
                        entry("tor_exit_node", false),
                        entry("vpn", true)
                    )
                )
            );
        }
    }

    public void testPrivacyDetectionInvariants() throws IOException, InvalidNetworkException {
        Path configDir = createTempDir();
        copyDatabase("privacy_detection_sample.mmdb", configDir);

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

    public static void assertDatabaseInvariants(final Path databasePath, final BiConsumer<InetAddress, Map<String, Object>> rowConsumer) {
        try (Reader reader = new Reader(databasePath.toFile())) {
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
}
