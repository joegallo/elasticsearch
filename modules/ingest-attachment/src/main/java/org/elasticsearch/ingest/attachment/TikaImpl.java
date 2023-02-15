/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.ingest.attachment;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.ParserDecorator;
import org.elasticsearch.SpecialPermission;
import org.elasticsearch.bootstrap.FilePermissionUtils;
import org.elasticsearch.core.PathUtils;
import org.elasticsearch.core.SuppressForbidden;
import org.elasticsearch.jdk.JarHell;
import org.slf4j.SLF4JServiceProviderAdaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.ReflectPermission;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.security.ProtectionDomain;
import java.security.SecurityPermission;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PropertyPermission;
import java.util.Set;

/**
 * Runs tika with limited parsers and limited permissions.
 * <p>
 * Do NOT make public
 */
final class TikaImpl {

    private static final Logger logger = LogManager.getLogger(TikaImpl.class);

    static {
        logger.info("Forcing slf4j initialization");
        SLF4JServiceProviderAdaptor.wallhack();

        try {
            for (String className : List.of(
                "org.apache.fontbox.cff.Type1CharString",
                "org.apache.fontbox.cff.Type1CharStringParser",
                "org.apache.fontbox.ttf.CmapSubtable",
                "org.apache.fontbox.ttf.GlyphSubstitutionTable",
                "org.apache.fontbox.ttf.KerningSubtable",
                "org.apache.fontbox.ttf.KerningTable",
                "org.apache.fontbox.ttf.OS2WindowsMetricsTable",
                "org.apache.fontbox.ttf.PostScriptTable",
                "org.apache.fontbox.ttf.TTFParser",
                "org.apache.fontbox.util.autodetect.FontFileFinder",
                "org.apache.pdfbox.contentstream.PDFStreamEngine",
                "org.apache.pdfbox.contentstream.operator.DrawObject",
                "org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters",
                "org.apache.pdfbox.contentstream.operator.text.MoveText",
                "org.apache.pdfbox.contentstream.operator.text.SetFontAndSize",
                "org.apache.pdfbox.filter.ASCIIHexFilter",
                "org.apache.pdfbox.filter.DCTFilter",
                "org.apache.pdfbox.filter.Filter",
                "org.apache.pdfbox.filter.FlateFilter",
                "org.apache.pdfbox.filter.JBIG2Filter",
                "org.apache.pdfbox.filter.LZWFilter",
                "org.apache.pdfbox.filter.RunLengthDecodeFilter",
                "org.apache.pdfbox.io.ScratchFile",
                "org.apache.pdfbox.io.ScratchFileBuffer",
                "org.apache.pdfbox.pdfparser.BaseParser",
                "org.apache.pdfbox.pdfparser.COSParser",
                "org.apache.pdfbox.pdfparser.PDFParser",
                "org.apache.pdfbox.pdfparser.PDFStreamParser",
                "org.apache.pdfbox.pdfparser.XrefTrailerResolver",
                "org.apache.pdfbox.pdmodel.PDDocument",
                "org.apache.pdfbox.pdmodel.PDDocumentCatalog",
                "org.apache.pdfbox.pdmodel.PDPage",
                "org.apache.pdfbox.pdmodel.PDPageTree",
                "org.apache.pdfbox.pdmodel.common.PDStream",
                "org.apache.pdfbox.pdmodel.font.FileSystemFontProvider",
                "org.apache.pdfbox.pdmodel.font.FontMapperImpl",
                "org.apache.pdfbox.pdmodel.font.PDFont",
                "org.apache.pdfbox.pdmodel.font.PDFontFactory",
                "org.apache.pdfbox.pdmodel.font.PDSimpleFont",
                "org.apache.pdfbox.pdmodel.font.PDType1Font",
                "org.apache.pdfbox.pdmodel.font.encoding.GlyphList",
                "org.apache.pdfbox.pdmodel.graphics.color.PDColor",
                "org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation",
                "org.apache.pdfbox.text.LegacyPDFStreamEngine",
                "org.apache.pdfbox.text.PDFTextStripper",
                "org.apache.pdfbox.text.TextPosition",
                "org.apache.tika.config.TikaConfig",
                "org.apache.tika.detect.zip.DefaultZipContainerDetector",
                "org.apache.tika.io.TemporaryResources",
                "org.apache.tika.langdetect.tika.LanguageProfile",
                "org.apache.tika.mime.MimeTypesReader",
                "org.apache.tika.parser.html.HtmlParser",
                "org.apache.tika.parser.hwp.HwpTextExtractorV5",
                "org.apache.tika.renderer.pdf.pdfbox.PDFBoxRenderer"
            )) {
                Class.forName(className);
            }
        } catch (Exception e) {
            logger.error("OH NO you broke it", e);
        }
    }

    /** Exclude some formats */
    private static final Set<MediaType> EXCLUDES = new HashSet<>(
        Arrays.asList(
            MediaType.application("vnd.ms-visio.drawing"),
            MediaType.application("vnd.ms-visio.drawing.macroenabled.12"),
            MediaType.application("vnd.ms-visio.stencil"),
            MediaType.application("vnd.ms-visio.stencil.macroenabled.12"),
            MediaType.application("vnd.ms-visio.template"),
            MediaType.application("vnd.ms-visio.template.macroenabled.12"),
            MediaType.application("vnd.ms-visio.drawing")
        )
    );

    /** subset of parsers for types we support */
    private static final Parser PARSERS[] = new Parser[] {
        // documents
        new org.apache.tika.parser.html.HtmlParser(),
        new org.apache.tika.parser.microsoft.rtf.RTFParser(),
        new org.apache.tika.parser.pdf.PDFParser(),
        new org.apache.tika.parser.txt.TXTParser(),
        new org.apache.tika.parser.microsoft.OfficeParser(),
        new org.apache.tika.parser.microsoft.OldExcelParser(),
        ParserDecorator.withoutTypes(new org.apache.tika.parser.microsoft.ooxml.OOXMLParser(), EXCLUDES),
        new org.apache.tika.parser.odf.OpenDocumentParser(),
        new org.apache.tika.parser.iwork.IWorkPackageParser(),
        new org.apache.tika.parser.xml.DcXMLParser(),
        new org.apache.tika.parser.epub.EpubParser(), };

    /** autodetector based on this subset */
    private static final AutoDetectParser PARSER_INSTANCE = new AutoDetectParser(PARSERS);

    /** singleton tika instance */
    private static final Tika TIKA_INSTANCE = new Tika(PARSER_INSTANCE.getDetector(), PARSER_INSTANCE);

    /**
     * parses with tika, throwing any exception hit while parsing the document
     */
    static String parse(final byte content[], final Metadata metadata, final int limit) throws TikaException, IOException {
        // check that its not unprivileged code like a script
        SpecialPermission.check();

        try {
            return AccessController.doPrivileged(
                (PrivilegedExceptionAction<String>) () -> TIKA_INSTANCE.parseToString(new ByteArrayInputStream(content), metadata, limit),
                RESTRICTED_CONTEXT
            );
        } catch (PrivilegedActionException e) {
            // checked exception from tika: unbox it
            Throwable cause = e.getCause();
            if (cause instanceof TikaException tikaException) {
                throw tikaException;
            } else if (cause instanceof IOException ioException) {
                throw ioException;
            } else {
                throw new AssertionError(cause);
            }
        }
    }

    // apply additional containment for parsers, this is intersected with the current permissions
    // its hairy, but worth it so we don't have some XML flaw reading random crap from the FS
    private static final AccessControlContext RESTRICTED_CONTEXT = new AccessControlContext(
        new ProtectionDomain[] { new ProtectionDomain(null, getRestrictedPermissions()) }
    );

    // compute some minimal permissions for parsers. they only get r/w access to the java temp directory,
    // the ability to load some resources from JARs, and read sysprops
    @SuppressForbidden(reason = "adds access to tmp directory")
    static PermissionCollection getRestrictedPermissions() {
        Permissions perms = new Permissions();
        // property/env access needed for parsing
        perms.add(new PropertyPermission("*", "read"));
        perms.add(new RuntimePermission("getenv.TIKA_CONFIG"));

        try {
            // add permissions for resource access:
            // classpath
            addReadPermissions(perms, JarHell.parseClassPath());
            // plugin jars
            if (TikaImpl.class.getClassLoader()instanceof URLClassLoader urlClassLoader) {
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
        // current hacks needed for POI/PDFbox issues:
        perms.add(new SecurityPermission("putProviderProperty.BC"));
        perms.add(new SecurityPermission("insertProvider"));
        perms.add(new ReflectPermission("suppressAccessChecks"));
        perms.add(new RuntimePermission("accessClassInPackage.sun.java2d.cmm.kcms"));
        // xmlbeans, use by POI, needs to get the context classloader
        perms.add(new RuntimePermission("getClassLoader"));
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
}
