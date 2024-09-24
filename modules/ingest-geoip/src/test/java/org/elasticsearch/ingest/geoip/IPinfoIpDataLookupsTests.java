/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.ingest.geoip;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;
import static org.elasticsearch.ingest.geoip.GeoIpTestUtils.copyDatabase;
import static org.elasticsearch.ingest.geoip.IPinfoIpDataLookups.parseAsn;
import static org.elasticsearch.ingest.geoip.IPinfoIpDataLookups.parseBoolean;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

public class IPinfoIpDataLookupsTests extends ESTestCase {

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
        // edge case: any non-"true" (ignoring whitespace and casing) string is false
        assertThat(parseBoolean(randomAlphaOfLength(5)), equalTo(false));
    }

    public void testAsn() throws IOException {
        Path configDir = createTempDir();
        copyDatabase("asn.mmdb", configDir);
        copyDatabase("asn_sample.mmdb", configDir);

        GeoIpCache cache = new GeoIpCache(1000); // real cache to test purging of entries upon a reload
        ConfigDatabases configDatabases = new ConfigDatabases(configDir, cache);
        configDatabases.initialize(resourceWatcherService);

        {
            DatabaseReaderLazyLoader loader = configDatabases.getDatabase("asn.mmdb");
            IpDataLookup lookup = new IPinfoIpDataLookups.Asn(Set.of(Database.Property.values()));
            Map<String, Object> data = lookup.get(loader, "64.67.15.209");
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
            DatabaseReaderLazyLoader loader = configDatabases.getDatabase("asn_sample.mmdb");
            IpDataLookup lookup = new IPinfoIpDataLookups.Asn(Set.of(Database.Property.values()));
            Map<String, Object> data = lookup.get(loader, "24.248.118.0");
            assertThat(
                data,
                equalTo(
                    Map.ofEntries(
                        entry("ip", "24.248.118.0"),
                        entry("organization_name", "Cox Communications Inc."),
                        entry("asn", 22773L),
                        entry("network", "24.248.118.0/23"),
                        entry("domain", "cox.com"),
                        entry("country_iso_code", "US"),
                        entry("type", "isp")
                    )
                )
            );
        }
    }

    public void testGeolocation() throws IOException {
        Path configDir = createTempDir();
        copyDatabase("ip_geolocation_sample.mmdb", configDir);

        GeoIpCache cache = new GeoIpCache(1000); // real cache to test purging of entries upon a reload
        ConfigDatabases configDatabases = new ConfigDatabases(configDir, cache);
        configDatabases.initialize(resourceWatcherService);

        {
            DatabaseReaderLazyLoader loader = configDatabases.getDatabase("ip_geolocation_sample.mmdb");
            IpDataLookup lookup = new IPinfoIpDataLookups.City(Set.of(Database.Property.values()));
            Map<String, Object> data = lookup.get(loader, "1.20.16.64");
            assertThat(
                data,
                equalTo(
                    Map.ofEntries(
                        entry("ip", "1.20.16.64"),
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
            IpDataLookup lookup = new IPinfoIpDataLookups.PrivacyDetection(Set.of(Database.Property.values()));
            Map<String, Object> data = lookup.get(loader, "12.181.21.18");
            assertThat(
                data,
                equalTo(
                    Map.ofEntries(
                        entry("ip", "12.181.21.18"),
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
            IpDataLookup lookup = new IPinfoIpDataLookups.PrivacyDetection(Set.of(Database.Property.values()));
            Map<String, Object> data = lookup.get(loader, "140.248.38.0");
            assertThat(
                data,
                equalTo(
                    Map.ofEntries(
                        entry("ip", "140.248.38.0"),
                        entry("hosting_provider", true),
                        entry("proxy", false),
                        entry("service", "Apple Private Relay"),
                        entry("relay", true),
                        entry("tor_exit_node", false),
                        entry("vpn", false)
                    )
                )
            );
        }
    }
}
