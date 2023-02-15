/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.slf4j;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.bootstrap.FilePermissionUtils;
import org.elasticsearch.core.PathUtils;
import org.elasticsearch.core.SuppressForbidden;
import org.elasticsearch.jdk.JarHell;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PropertyPermission;
import java.util.Set;

public class SLF4JServiceProviderAdaptor {

    private static final Logger logger = LogManager.getLogger(SLF4JServiceProviderAdaptor.class);

    public static void wallhack() {
        List<SLF4JServiceProvider> providers = LoggerFactory.findServiceProviders();
        logger.info("providers.size() : " + providers.size());
        if (providers.size() == 1) {
            SLF4JServiceProvider provider = providers.get(0);
            provider.initialize();
            SLF4JServiceProvider wrappedProvider = new SLF4JServiceProviderWrapper(provider);
            LoggerFactory.INITIALIZATION_STATE = LoggerFactory.SUCCESSFUL_INITIALIZATION;
            LoggerFactory.PROVIDER = wrappedProvider;
        }
    }

    private static class SLF4JILoggerFactoryWrapper implements ILoggerFactory {

        private final ILoggerFactory factory;

        private SLF4JILoggerFactoryWrapper(ILoggerFactory factory) {
            this.factory = factory;
        }

        @Override
        public org.slf4j.Logger getLogger(String name) {
            return AccessController.doPrivileged(
                (PrivilegedAction<org.slf4j.Logger>) () -> {
                    // logger.info("lol somebody wants the following: " + name);
                    return this.factory.getLogger(name);
                },
                RESTRICTED_CONTEXT
            );
        }
    }

    private static final AccessControlContext RESTRICTED_CONTEXT = new AccessControlContext(
        new ProtectionDomain[] { new ProtectionDomain(null, getLog4jPermissions()) }
    );

    static PermissionCollection getLog4jPermissions() {
        Permissions perms = new Permissions();
        perms.add(new PropertyPermission("*", "read"));
        try {
            // add permissions for resource access:
            // classpath
            addReadPermissions(perms, JarHell.parseClassPath());
            // plugin jars
            if (SLF4JServiceProviderAdaptor.class.getClassLoader()instanceof URLClassLoader urlClassLoader) {
                URL[] urls = urlClassLoader.getURLs();
                Set<URL> set = new LinkedHashSet<>(Arrays.asList(urls));
                if (set.size() != urls.length) {
                    throw new AssertionError("duplicate jars: " + Arrays.toString(urls));
                }
                addReadPermissions(perms, set);
            }
            // jvm's java.io.tmpdir (needs read/write)
            FilePermissionUtils.addDirectoryPath(
                perms,
                "java.io.tmpdir",
                PathUtils.get(System.getProperty("java.io.tmpdir")),
                "read,readlink,write,delete",
                false
            );
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        perms.add(new RuntimePermission("getenv.*"));
        perms.setReadOnly();
        return perms;
    }

    // add resources to (what is typically) a jar, but might not be (e.g. in tests/IDE)
    @SuppressForbidden(reason = "adds access to jar resources")
    static void addReadPermissions(Permissions perms, Set<URL> resources) throws IOException {
        try {
            for (URL url : resources) {
                Path path = PathUtils.get(url.toURI());
                if (Files.isDirectory(path)) {
                    FilePermissionUtils.addDirectoryPath(perms, "class.path", path, "read,readlink", false);
                } else {
                    FilePermissionUtils.addSingleFilePath(perms, path, "read,readlink");
                }
            }
        } catch (URISyntaxException bogus) {
            throw new RuntimeException(bogus);
        }
    }

    private static class SLF4JServiceProviderWrapper implements SLF4JServiceProvider {

        private final SLF4JServiceProvider provider;
        private SLF4JILoggerFactoryWrapper factory;

        private SLF4JServiceProviderWrapper(SLF4JServiceProvider provider) {
            this.provider = provider;
            this.factory = new SLF4JILoggerFactoryWrapper(provider.getLoggerFactory());
        }

        @Override
        public ILoggerFactory getLoggerFactory() {
            return factory;
        }

        @Override
        public IMarkerFactory getMarkerFactory() {
            return provider.getMarkerFactory();
        }

        @Override
        public MDCAdapter getMDCAdapter() {
            return provider.getMDCAdapter();
        }

        @Override
        public String getRequestedApiVersion() {
            return provider.getRequestedApiVersion();
        }

        @Override
        public void initialize() {
            this.provider.initialize();
        }
    }

}
