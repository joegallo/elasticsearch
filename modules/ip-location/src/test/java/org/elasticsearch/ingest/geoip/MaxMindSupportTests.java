/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.ingest.geoip;

import com.maxmind.db.Network;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.AnonymousIpResponse;
import com.maxmind.geoip2.model.AnonymousPlusResponse;
import com.maxmind.geoip2.model.AsnResponse;
import com.maxmind.geoip2.model.CityResponse;
import com.maxmind.geoip2.model.ConnectionTypeResponse;
import com.maxmind.geoip2.model.CountryResponse;
import com.maxmind.geoip2.model.DomainResponse;
import com.maxmind.geoip2.model.EnterpriseResponse;
import com.maxmind.geoip2.model.IpRiskResponse;
import com.maxmind.geoip2.model.IspResponse;
import com.maxmind.geoip2.record.MaxMind;

import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.core.SuppressForbidden;
import org.elasticsearch.test.ESTestCase;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

/**
 * This test is meant to help us understand if we are falling behind on support for mmdb types and fields that get added over time. It
 * maintains a list for each mmdb file type we support of which fields in that type we support and which we do not.
 * <p>
 * This test will:
 * <p>
 * - Fail if MaxMind adds a new field to one of the mmdb file types we currently support (city, country, etc) and we don't update this test
 *   with whether or not we support that field.
 * - Fail if we add support for a new mmdb file type (enterprise for example) but don't update the test with which fields we do and do not
 *   support.
 * - Fail if MaxMind adds a new mmdb file type that we don't know about
 * - Fail if we expose a MaxMind type through IpDatabase, but don't update the test to know how to handle it
 */
public class MaxMindSupportTests extends ESTestCase {

    private static final Set<String> ANONYMOUS_IP_SUPPORTED_FIELDS = Set.of(
        "anonymous",
        "anonymousVpn",
        "hostingProvider",
        "publicProxy",
        "residentialProxy",
        "torExitNode"
    );
    private static final Set<String> ANONYMOUS_IP_UNSUPPORTED_FIELDS = Set.of("ipAddress", "network");

    private static final Set<String> ASN_SUPPORTED_FIELDS = Set.of("autonomousSystemNumber", "autonomousSystemOrganization", "network");
    private static final Set<String> ASN_UNSUPPORTED_FIELDS = Set.of("ipAddress");

    private static final Set<String> CITY_SUPPORTED_FIELDS = Set.of(
        "city.name",
        "continent.code",
        "continent.name",
        "country.inEuropeanUnion",
        "country.isoCode",
        "country.name",
        "location.accuracyRadius",
        "location.latitude",
        "location.longitude",
        "location.timeZone",
        "mostSpecificSubdivision.isoCode",
        "mostSpecificSubdivision.name",
        "postal.code",
        "registeredCountry.inEuropeanUnion",
        "registeredCountry.isoCode",
        "registeredCountry.name"
    );
    private static final Set<String> CITY_UNSUPPORTED_FIELDS = Set.of(
        "city.confidence",
        "city.geonameId",
        "city.locales",
        "city.names",
        "continent.geonameId",
        "continent.locales",
        "continent.names",
        "country.confidence",
        "country.geonameId",
        "country.locales",
        "country.names",
        "leastSpecificSubdivision.confidence",
        "leastSpecificSubdivision.geonameId",
        "leastSpecificSubdivision.isoCode",
        "leastSpecificSubdivision.locales",
        "leastSpecificSubdivision.name",
        "leastSpecificSubdivision.names",
        "location.averageIncome",
        "location.populationDensity",
        "maxmind",
        "mostSpecificSubdivision.confidence",
        "mostSpecificSubdivision.geonameId",
        "mostSpecificSubdivision.locales",
        "mostSpecificSubdivision.names",
        "postal.confidence",
        "registeredCountry.confidence",
        "registeredCountry.geonameId",
        "registeredCountry.locales",
        "registeredCountry.names",
        "representedCountry.confidence",
        "representedCountry.geonameId",
        "representedCountry.inEuropeanUnion",
        "representedCountry.isoCode",
        "representedCountry.locales",
        "representedCountry.name",
        "representedCountry.names",
        "representedCountry.type",
        "subdivisions.confidence",
        "subdivisions.geonameId",
        "subdivisions.isoCode",
        "subdivisions.locales",
        "subdivisions.name",
        "subdivisions.names",
        "traits.anonymous",
        "traits.anonymousVpn",
        "traits.anycast",
        "traits.autonomousSystemNumber",
        "traits.autonomousSystemOrganization",
        "traits.connectionType",
        "traits.domain",
        "traits.hostingProvider",
        "traits.ipAddress",
        "traits.ipRiskSnapshot",
        "traits.isp",
        "traits.legitimateProxy",
        "traits.mobileCountryCode",
        "traits.mobileNetworkCode",
        "traits.network",
        "traits.organization",
        "traits.publicProxy",
        "traits.residentialProxy",
        "traits.staticIpScore",
        "traits.torExitNode",
        "traits.userCount",
        "traits.userType"
    );

    private static final Set<String> CONNECT_TYPE_SUPPORTED_FIELDS = Set.of("connectionType");
    private static final Set<String> CONNECT_TYPE_UNSUPPORTED_FIELDS = Set.of("ipAddress", "network");

    private static final Set<String> COUNTRY_SUPPORTED_FIELDS = Set.of(
        "continent.name",
        "country.inEuropeanUnion",
        "country.isoCode",
        "continent.code",
        "country.name",
        "registeredCountry.inEuropeanUnion",
        "registeredCountry.isoCode",
        "registeredCountry.name"
    );
    private static final Set<String> COUNTRY_UNSUPPORTED_FIELDS = Set.of(
        "continent.geonameId",
        "continent.locales",
        "continent.names",
        "country.confidence",
        "country.geonameId",
        "country.locales",
        "country.names",
        "maxmind",
        "registeredCountry.confidence",
        "registeredCountry.geonameId",
        "registeredCountry.locales",
        "registeredCountry.names",
        "representedCountry.confidence",
        "representedCountry.geonameId",
        "representedCountry.inEuropeanUnion",
        "representedCountry.isoCode",
        "representedCountry.locales",
        "representedCountry.name",
        "representedCountry.names",
        "representedCountry.type",
        "traits.anonymous",
        "traits.anonymousVpn",
        "traits.anycast",
        "traits.autonomousSystemNumber",
        "traits.autonomousSystemOrganization",
        "traits.connectionType",
        "traits.domain",
        "traits.hostingProvider",
        "traits.ipAddress",
        "traits.ipRiskSnapshot",
        "traits.isp",
        "traits.legitimateProxy",
        "traits.mobileCountryCode",
        "traits.mobileNetworkCode",
        "traits.network",
        "traits.organization",
        "traits.publicProxy",
        "traits.residentialProxy",
        "traits.staticIpScore",
        "traits.torExitNode",
        "traits.userCount",
        "traits.userType"
    );

    private static final Set<String> DOMAIN_SUPPORTED_FIELDS = Set.of("domain");
    private static final Set<String> DOMAIN_UNSUPPORTED_FIELDS = Set.of("ipAddress", "network");

    private static final Set<String> ENTERPRISE_SUPPORTED_FIELDS = Set.of(
        "city.confidence",
        "city.name",
        "continent.code",
        "continent.name",
        "country.confidence",
        "country.inEuropeanUnion",
        "country.isoCode",
        "country.name",
        "location.accuracyRadius",
        "location.latitude",
        "location.longitude",
        "location.timeZone",
        "mostSpecificSubdivision.isoCode",
        "mostSpecificSubdivision.name",
        "postal.code",
        "postal.confidence",
        "registeredCountry.inEuropeanUnion",
        "registeredCountry.isoCode",
        "registeredCountry.name",
        "traits.anonymous",
        "traits.anonymousVpn",
        "traits.autonomousSystemNumber",
        "traits.autonomousSystemOrganization",
        "traits.connectionType",
        "traits.domain",
        "traits.hostingProvider",
        "traits.isp",
        "traits.mobileCountryCode",
        "traits.mobileNetworkCode",
        "traits.network",
        "traits.organization",
        "traits.publicProxy",
        "traits.residentialProxy",
        "traits.torExitNode",
        "traits.userType"
    );
    private static final Set<String> ENTERPRISE_UNSUPPORTED_FIELDS = Set.of(
        "city.geonameId",
        "city.locales",
        "city.names",
        "continent.geonameId",
        "continent.locales",
        "continent.names",
        "country.geonameId",
        "country.locales",
        "country.names",
        "leastSpecificSubdivision.confidence",
        "leastSpecificSubdivision.geonameId",
        "leastSpecificSubdivision.isoCode",
        "leastSpecificSubdivision.locales",
        "leastSpecificSubdivision.name",
        "leastSpecificSubdivision.names",
        "location.averageIncome",
        "location.populationDensity",
        "maxmind",
        "mostSpecificSubdivision.confidence",
        "mostSpecificSubdivision.geonameId",
        "mostSpecificSubdivision.locales",
        "mostSpecificSubdivision.names",
        "registeredCountry.confidence",
        "registeredCountry.geonameId",
        "registeredCountry.locales",
        "registeredCountry.names",
        "representedCountry.confidence",
        "representedCountry.geonameId",
        "representedCountry.inEuropeanUnion",
        "representedCountry.isoCode",
        "representedCountry.locales",
        "representedCountry.name",
        "representedCountry.names",
        "representedCountry.type",
        "subdivisions.confidence",
        "subdivisions.geonameId",
        "subdivisions.isoCode",
        "subdivisions.locales",
        "subdivisions.name",
        "subdivisions.names",
        "traits.anycast",
        "traits.ipAddress",
        "traits.ipRiskSnapshot",
        "traits.legitimateProxy",
        "traits.staticIpScore",
        "traits.userCount"
    );

    private static final Set<String> ISP_SUPPORTED_FIELDS = Set.of(
        "autonomousSystemNumber",
        "autonomousSystemOrganization",
        "network",
        "isp",
        "mobileCountryCode",
        "mobileNetworkCode",
        "organization"
    );

    private static final Set<String> ISP_UNSUPPORTED_FIELDS = Set.of("ipAddress");

    private static final Map<Database, Set<String>> TYPE_TO_SUPPORTED_FIELDS_MAP = Map.of(
        Database.AnonymousIp,
        ANONYMOUS_IP_SUPPORTED_FIELDS,
        Database.Asn,
        ASN_SUPPORTED_FIELDS,
        Database.City,
        CITY_SUPPORTED_FIELDS,
        Database.ConnectionType,
        CONNECT_TYPE_SUPPORTED_FIELDS,
        Database.Country,
        COUNTRY_SUPPORTED_FIELDS,
        Database.Domain,
        DOMAIN_SUPPORTED_FIELDS,
        Database.Enterprise,
        ENTERPRISE_SUPPORTED_FIELDS,
        Database.Isp,
        ISP_SUPPORTED_FIELDS
    );
    private static final Map<Database, Set<String>> TYPE_TO_UNSUPPORTED_FIELDS_MAP = Map.of(
        Database.AnonymousIp,
        ANONYMOUS_IP_UNSUPPORTED_FIELDS,
        Database.Asn,
        ASN_UNSUPPORTED_FIELDS,
        Database.City,
        CITY_UNSUPPORTED_FIELDS,
        Database.ConnectionType,
        CONNECT_TYPE_UNSUPPORTED_FIELDS,
        Database.Country,
        COUNTRY_UNSUPPORTED_FIELDS,
        Database.Domain,
        DOMAIN_UNSUPPORTED_FIELDS,
        Database.Enterprise,
        ENTERPRISE_UNSUPPORTED_FIELDS,
        Database.Isp,
        ISP_UNSUPPORTED_FIELDS
    );
    private static final Map<Database, Class<?>> TYPE_TO_MAX_MIND_CLASS = Map.of(
        Database.AnonymousIp,
        AnonymousIpResponse.class,
        Database.Asn,
        AsnResponse.class,
        Database.City,
        CityResponse.class,
        Database.ConnectionType,
        ConnectionTypeResponse.class,
        Database.Country,
        CountryResponse.class,
        Database.Domain,
        DomainResponse.class,
        Database.Enterprise,
        EnterpriseResponse.class,
        Database.Isp,
        IspResponse.class
    );

    private static final Set<Class<?>> KNOWN_UNSUPPORTED_RESPONSE_CLASSES = Set.of(AnonymousPlusResponse.class, IpRiskResponse.class);

    private static final Set<Database> KNOWN_UNSUPPORTED_DATABASE_VARIANTS = Set.of(
        Database.AsnV2,
        Database.CityV2,
        Database.CountryV2,
        Database.PrivacyDetection,
        Database.IpinfoPlus
    );

    public void testMaxMindSupport() {
        for (Database databaseType : Database.values()) {
            if (KNOWN_UNSUPPORTED_DATABASE_VARIANTS.contains(databaseType)) {
                continue;
            }

            Class<?> maxMindClass = TYPE_TO_MAX_MIND_CLASS.get(databaseType);
            Set<String> supportedFields = TYPE_TO_SUPPORTED_FIELDS_MAP.get(databaseType);
            Set<String> unsupportedFields = TYPE_TO_UNSUPPORTED_FIELDS_MAP.get(databaseType);
            assertNotNull(
                "A new Database type, "
                    + databaseType
                    + ", has been added, but this test has not been updated to know which MaxMind "
                    + "class to use to load it. Update TYPE_TO_MAX_MIND_CLASS",
                maxMindClass
            );
            assertNotNull(
                "A new Database type, "
                    + databaseType
                    + ", has been added, but this test has not been updated to know which fields we "
                    + "support for it. Update TYPE_TO_SUPPORTED_FIELDS_MAP",
                supportedFields
            );
            assertNotNull(
                "A new Database type, "
                    + databaseType
                    + ", has been added, but this test has not been updated to know which fields we "
                    + "do not support for it. Update TYPE_TO_UNSUPPORTED_FIELDS_MAP",
                unsupportedFields
            );
            /*
             * fieldNamesFromMaxMind:        The fields that MaxMind's Response object contains
             * unusedFields:                 Fields that MaxMind's Response object contains that we do not claim to support
             * unknownUnsupportedFieldNames: Fields that we document as unsupported that MaxMind's Response object doesn't even contain
             * unknownSupportedFieldNames:   Fields that we document as supported that MaxMind's Response object doesn't even contain
             */
            final SortedSet<String> fieldNamesFromMaxMind = getFieldNamesUsedFromClass("", maxMindClass);
            Set<String> unusedFields = Sets.difference(fieldNamesFromMaxMind, supportedFields);
            Set<String> unknownUnsupportedFieldNames = Sets.difference(unsupportedFields, unusedFields);
            assertThat(
                "We have documented fields in TYPE_TO_UNSUPPORTED_FIELDS_MAP that MaxMind does not actually support for "
                    + databaseType
                    + ": "
                    + unknownUnsupportedFieldNames,
                unknownUnsupportedFieldNames,
                empty()
            );
            assertThat(
                "New MaxMind fields have been added for "
                    + databaseType
                    + " that we do not use or have documented in TYPE_TO_UNSUPPORTED_FIELDS_MAP. The actual list of fields is:\n"
                    + getFormattedList(unusedFields),
                unusedFields,
                equalTo(new TreeSet<>(unsupportedFields))
            );
            Set<String> unknownSupportedFieldNames = Sets.difference(supportedFields, fieldNamesFromMaxMind);
            assertThat(
                "We are attempting to use fields that MaxMind does not support for " + databaseType + ": " + unknownSupportedFieldNames,
                unknownSupportedFieldNames,
                empty()
            );
        }
    }

    public void testUnknownMaxMindResponseClasses() {
        Set<Class<?>> supportedMaxMindClasses = new HashSet<>(TYPE_TO_MAX_MIND_CLASS.values());
        // First just a sanity check that there's no overlap between what's supported and what's not:
        Set<Class<?>> supportedAndUnsupportedMaxMindClasses = Sets.intersection(
            supportedMaxMindClasses,
            KNOWN_UNSUPPORTED_RESPONSE_CLASSES
        );
        assertThat(
            "We claim both to support and not support some MaxMind response classes: " + supportedAndUnsupportedMaxMindClasses,
            supportedAndUnsupportedMaxMindClasses,
            empty()
        );
        Set<Class<?>> allActualMaxMindClasses = new HashSet<>();

        Method[] methods = DatabaseReader.class.getMethods();
        for (Method method : methods) {
            if (method.getName().startsWith("try")) {
                if (method.getReturnType().equals(Optional.class)) {
                    Type genericReturnType = method.getGenericReturnType();
                    if (genericReturnType instanceof ParameterizedType parameterizedGenericReturnType) {
                        Type[] actualTypes = parameterizedGenericReturnType.getActualTypeArguments();
                        if (actualTypes.length == 1 && actualTypes[0] instanceof Class<?> actualTypeClass) {
                            allActualMaxMindClasses.add(actualTypeClass);
                            if (KNOWN_UNSUPPORTED_RESPONSE_CLASSES.contains(actualTypeClass) == false) {
                                assertTrue(
                                    "MaxMind has added support for " + actualTypeClass.getSimpleName(),
                                    supportedMaxMindClasses.contains(actualTypeClass)
                                );
                            }
                        }
                    }
                }
            }
        }
        // Now make sure that we're not claiming to support any maxmind classes that aren't ever read by DatabaseReader:
        Set<Class<?>> supportedMaxMindClassesThatDoNotExist = Sets.difference(supportedMaxMindClasses, allActualMaxMindClasses);
        assertThat(
            "We claim to support a MaxMind response class that MaxMind does not expose through DatabaseReader: "
                + supportedMaxMindClassesThatDoNotExist,
            supportedMaxMindClassesThatDoNotExist,
            empty()
        );
    }

    /*
     * This tests that this test has a mapping in TYPE_TO_MAX_MIND_CLASS for all MaxMind classes exposed through GeoIpDatabase.
     */
    public void testUsedMaxMindResponseClassesAreAccountedFor() {
        Set<Class<?>> usedMaxMindResponseClasses = getUsedMaxMindResponseClasses();
        Set<Class<?>> supportedMaxMindClasses = new HashSet<>(TYPE_TO_MAX_MIND_CLASS.values());
        Set<Class<?>> usedButNotSupportedMaxMindResponseClasses = Sets.difference(usedMaxMindResponseClasses, supportedMaxMindClasses);
        assertThat(
            "MaxmindIpDataLookups exposes MaxMind response classes that this test does not know what to do with. Add mappings to "
                + "TYPE_TO_MAX_MIND_CLASS for the following: "
                + usedButNotSupportedMaxMindResponseClasses,
            usedButNotSupportedMaxMindResponseClasses,
            empty()
        );
        Set<Class<?>> supportedButNotUsedMaxMindClasses = Sets.difference(supportedMaxMindClasses, usedMaxMindResponseClasses);
        assertThat(
            "This test claims to support MaxMind response classes that are not exposed in GeoIpDatabase. Remove the following from "
                + "TYPE_TO_MAX_MIND_CLASS: "
                + supportedButNotUsedMaxMindClasses,
            supportedButNotUsedMaxMindClasses,
            empty()
        );
    }

    /*
     * This is the list of field types that causes us to stop recursing. That is, fields of these types are the lowest-level fields that
     * we care about.
     */
    private static final Set<Class<?>> TERMINAL_TYPES = Set.of(
        boolean.class,
        Boolean.class,
        char.class,
        Character.class,
        Class.class,
        ConnectionTypeResponse.ConnectionType.class,
        double.class,
        Double.class,
        InetAddress.class,
        int.class,
        Integer.class,
        long.class,
        Long.class,
        MaxMind.class,
        Network.class,
        Object.class,
        String.class,
        void.class,
        Void.class
    );
    /*
     * These are types that are containers for other types. We don't need to recurse into each method on these types. Instead, we need to
     *  look at their generic types.
     */
    private static final Set<Class<?>> CONTAINER_TYPES = Set.of(Collection.class, List.class, Map.class, Optional.class);
    /*
     * These are methods we don't want to traverse into.
     */
    private static final Set<String> IGNORED_METHODS = Arrays.stream(Object.class.getMethods())
        .map(Method::getName)
        .collect(Collectors.toUnmodifiableSet());

    /**
     * Returns the set of bean-property-like field names referenced from aClass, sorted alphabetically. This method calls itself
     * recursively for all record components until it reaches one of the types in TERMINAL_TYPES. The name of the accessor returning one
     * of those terminal types is converted to a bean-property-like name using the "recordFieldName" method.
     *
     * @param context This is a String representing where in the list of methods we are
     * @param aClass The class whose methods we want to traverse to generate field names
     * @return A sorted set of bean-property-like field names that can recursively be found from aClass
     */
    private static SortedSet<String> getFieldNamesUsedFromClass(String context, Class<?> aClass) {
        SortedSet<String> fieldNames = new TreeSet<>();
        if (TERMINAL_TYPES.contains(aClass)) {
            // We got here because a container type had a terminal type
            fieldNames.add(context);
            return fieldNames;
        }
        if (aClass.isRecord() == false) {
            throw new AssertionError("This test needs to be updated to deal with non-record class " + aClass.getName());
        }
        final Set<String> componentNames = new HashSet<>();
        for (RecordComponent component : aClass.getRecordComponents()) {
            componentNames.add(component.getName());
        }
        for (Method method : aClass.getMethods()) {
            // Compare by name: records override equals/hashCode/toString, so their Method objects are not equal to Object's.
            if (IGNORED_METHODS.contains(method.getName())) {
                continue;
            }
            if (method.getParameterCount() != 0
                || Modifier.isStatic(method.getModifiers())
                || method.isBridge()
                || (method.isAnnotationPresent(Deprecated.class) && componentNames.contains(method.getName()) == false)) {
                // Record accessors take no arguments. MaxMind keeps deprecated bean-style aliases (getGeoNameId, getMaxMind, etc.)
                // around, and those are not separate fields. Deprecated record components are still real fields, though.
                continue;
            }
            if (method.getName().startsWith("to")) {
                // ignoring methods like toJson or toString
                continue;
            }
            String name = recordFieldName(method);
            String currentContext = context + (context.isEmpty() ? "" : ".") + name;
            if (TERMINAL_TYPES.contains(method.getReturnType())) {
                fieldNames.add(currentContext);
            } else {
                Class<?> returnType = method.getReturnType();
                if (CONTAINER_TYPES.contains(returnType)) {
                    ParameterizedType genericReturnType = (ParameterizedType) method.getGenericReturnType();
                    for (Type actualType : genericReturnType.getActualTypeArguments()) {
                        if (actualType instanceof Class<?> actualTypeClass) {
                            fieldNames.addAll(getFieldNamesUsedFromClass(currentContext, actualTypeClass));
                        } else {
                            assert false : "This test needs to be updated to deal with this situation";
                        }
                    }
                } else {
                    fieldNames.addAll(getFieldNamesUsedFromClass(currentContext, method.getReturnType()));
                }
            }
        }
        return fieldNames;
    }

    /*
     * Record accessors have no get/is prefix, so the accessor name is the field name. The one exception is boolean components like
     * "isAnonymous", which we report as "anonymous" to match the bean-style names used in the sets above. We require a boolean return
     * type and an uppercase following character so that "isoCode" and "isp" are left alone.
     */
    private static String recordFieldName(Method accessor) {
        String name = accessor.getName();
        Class<?> type = accessor.getReturnType();
        if ((type == boolean.class || type == Boolean.class)
            && name.length() > 2
            && name.startsWith("is")
            && Character.isUpperCase(name.charAt(2))) {
            return Character.toLowerCase(name.charAt(2)) + name.substring(3);
        }
        return name;
    }

    /*
     * This is a convenience to format the list of field names in fields into a String that can be copied into a Set initializer above,
     * like countryUnsupportedFields.
     */
    private static String getFormattedList(Set<String> fields) {
        StringBuilder result = new StringBuilder();
        SortedSet<String> sortedFields = new TreeSet<>(fields);
        for (Iterator<String> it = sortedFields.iterator(); it.hasNext();) {
            result.append("\"");
            result.append(it.next());
            result.append("\"");
            if (it.hasNext()) {
                result.append(",\n");
            }
        }
        return result.toString();
    }

    /*
     * This returns all response classes that are used as the first type argument of the lookup classes defined in MaxmindIpDataLookups,
     * for example the AsnResponse in "Asn extends SimpleBase<AsnResponse>".
     */
    @SuppressForbidden(reason = "Need declared classes")
    private static Set<Class<?>> getUsedMaxMindResponseClasses() {
        Set<Class<?>> result = new HashSet<>();
        for (Class<?> declaredClass : MaxmindIpDataLookups.class.getDeclaredClasses()) {
            if (Modifier.isAbstract(declaredClass.getModifiers())) {
                continue;
            }
            if (declaredClass.getGenericSuperclass() instanceof ParameterizedType superclass
                && superclass.getActualTypeArguments()[0] instanceof Class<?> responseClass) {
                result.add(responseClass);
            }
        }
        return result;
    }
}
