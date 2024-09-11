/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
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
import static org.hamcrest.Matchers.equalTo;

public class IPinfoGeoDataLookupsTest extends ESTestCase {

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

    public void testAsn() throws IOException {
        Path configDir = createTempDir();
        copyDatabase("asn.mmdb", configDir);
        copyDatabase("asn_sample.mmdb", configDir);

        GeoIpCache cache = new GeoIpCache(1000); // real cache to test purging of entries upon a reload
        ConfigDatabases configDatabases = new ConfigDatabases(configDir, cache);
        configDatabases.initialize(resourceWatcherService);

        {
            DatabaseReaderLazyLoader loader = configDatabases.getDatabase("asn.mmdb");
            GeoDataLookup asn = new IPinfoGeoDataLookups.Asn(Set.of(Database.Property.values()));
            Map<String, Object> data = asn.getGeoData(loader, "64.67.15.209");
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
            GeoDataLookup asn = new IPinfoGeoDataLookups.Asn(Set.of(Database.Property.values()));
            Map<String, Object> data = asn.getGeoData(loader, "24.248.118.0");
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
}
