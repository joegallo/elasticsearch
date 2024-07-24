/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.ingest.geoip;

import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.TimeValue;

class DatabaseExpirationService implements ClusterStateListener {

    static final String DATABASE_VALIDITY = "ingest.geoip.database_validity";

    // we've hardcoded that the limit before you're 'close to expiration' is 25 days
    private static final long TWENTY_FIVE_DAYS_MILLIS = TimeValue.timeValueDays(25).millis();

    // expiration
    private static final TimeValue THIRTY_DAYS = TimeValue.timeValueDays(30);
    private static final long THIRTY_DAYS_MILLIS = THIRTY_DAYS.millis();

    private final ClusterService clusterService;

    private volatile Settings settings;
    private volatile long expirationMillis;

    DatabaseExpirationService(ClusterService clusterService) {
        this.clusterService = clusterService;
    }

    public void init() {
        clusterService.addListener(this);
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        final Settings settings = event.state().metadata().settings();
        if (this.settings != settings) {
            this.settings = settings;

            if (this.settings.hasValue(DATABASE_VALIDITY)) {
                expirationMillis = this.settings.getAsTime(DATABASE_VALIDITY, THIRTY_DAYS).millis();
            } else {
                expirationMillis = THIRTY_DAYS_MILLIS;
            }
        }
    }

    public boolean isAlmostExpired(long now, GeoIpTaskState.Metadata metadata) {
        return metadata.lastCheck() < now - TWENTY_FIVE_DAYS_MILLIS;
    }

    public boolean isExpired(long now, GeoIpTaskState.Metadata metadata) {
        return metadata.lastCheck() < now - expirationMillis;
    }
}
