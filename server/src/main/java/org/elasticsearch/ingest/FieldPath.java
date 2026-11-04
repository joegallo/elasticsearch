/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.ingest;

import org.elasticsearch.common.Strings;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.script.TemplateScript;

import java.util.Map;
import java.util.Objects;

/**
 * A path to a field in an {@link IngestDocument} (e.g. <code>foo.bar.baz</code>), prepared as far as possible ahead of time.
 * <p>
 * Processors are configured with field names that are either plain strings or templates (e.g. <code>{{foo.bar}}</code>). Plain strings,
 * and templates that do not actually contain any templating, are the same for every document, so they only need to be parsed once, when
 * the processor is created. Only the genuinely templated ones have to be rendered and parsed for every document. The
 * {@link IngestDocument} methods that accept a {@link FieldPath} do whichever of these is needed, by calling {@link #resolve}, so a
 * processor can simply create its {@link FieldPath}s up front and pass them in.
 * <p>
 * A processor that performs several operations with one {@link FieldPath} should call {@link #resolve(IngestDocument)} once and use the
 * result for all of them: for a templated path every call renders the template again, but the result of resolving is already resolved,
 * and resolving that is free.
 * <p>
 * Creating a {@link FieldPath} never fails because a path is malformed. That is deliberate: pipelines are stored, and one that
 * contains a malformed path must continue to load and to fail only when a document is actually run through it, as it always has. A
 * malformed path therefore throws the same {@link IllegalArgumentException} that parsing it would have, but when it is resolved instead.
 * <p>
 * A path that is null is a different matter, as it is not a malformed path but a missing one. Required paths are created with
 * {@link #of(String)}, which rejects null as the programming error that it is. Optional paths are created with
 * {@link #ofNullable(String)}, which returns <code>null</code> when it is given <code>null</code>, so that callers can pass the configured
 * value straight through and then check whether the result is <code>null</code>.
 * <p>
 * A {@link FieldPath} is immutable and thread safe, and it is independent of the {@link IngestPipelineFieldAccessPattern}: whether a
 * path is valid for the access pattern of the pipeline that is currently executing is checked when the path is used.
 */
public abstract sealed class FieldPath permits FieldPath.Parsed, FieldPath.Unparseable, FieldPath.Templated {

    private static final int MAX_SIZE = 512;
    private static final Map<String, Parsed> CACHE = ConcurrentCollections.newConcurrentMapWithAggressiveConcurrency();

    private FieldPath() {}

    /**
     * Creates a {@link FieldPath} for a path that is the same for every document, such as a processor's <code>field</code>. This
     * is for required paths: use {@link #ofNullable(String)} for the ones that are optional.
     * <p>
     * A path that is malformed (or empty) is not rejected here, see the class documentation. A path that is null is, though, as that is
     * not something that a configuration can cause, so it is a programming error and it is better to find out about it when the processor
     * is created than when it is first used.
     *
     * @param path the path
     * @throws NullPointerException if the path is null
     */
    public static FieldPath of(String path) {
        Objects.requireNonNull(path, "path cannot be null");
        return create(path);
    }

    /**
     * Creates a {@link FieldPath} for an optional path that is the same for every document, such as a processor's
     * <code>copy_from</code>. Use {@link #of(String)} for the ones that are required.
     * <p>
     * Unlike the other methods, this returns <code>null</code> if the given path is <code>null</code>. That makes it convenient for
     * optional fields: pass in the configured value (which is null if it was not configured) and check the result for <code>null</code>
     * before using it. A path that is present but empty or malformed is <em>not</em> treated that way, see the class documentation.
     *
     * @param path the path, or null if there is no path
     * @return the {@link FieldPath} for the path, or null if the path is null
     */
    @Nullable
    public static FieldPath ofNullable(@Nullable String path) {
        return path == null ? null : create(path);
    }

    /**
     * Creates a {@link FieldPath} from a template, as produced by
     * {@link ConfigurationUtils#compileTemplate(String, String, String, String, org.elasticsearch.script.ScriptService)}.
     * If the template is a {@link ConfigurationUtils.ConstantTemplateScriptFactory} then it is evaluated (and parsed) here, once.
     * Otherwise, it will be rendered for every document.
     *
     * @throws NullPointerException if the template is null, which is a programming error, so it is reported when the processor is
     * created rather than for every document that it is later used with
     */
    public static FieldPath of(TemplateScript.Factory template) {
        Objects.requireNonNull(template, "template cannot be null");
        if (template instanceof ConfigurationUtils.ConstantTemplateScriptFactory) {
            // a constant template ignores the model that it is rendered against, so it doesn't need a document
            //
            // note that this deliberately goes through create, and not through of or ofNullable. The template here is not optional, so
            // this method must never return null, and a constant template is not something that a caller can be blamed for if it
            // renders as null (in which case the result is the same as it would have been for a template that renders as null for
            // every document: resolving throws).
            return create(template.newInstance(Map.of()).execute());
        }
        return new Templated(template);
    }

    /**
     * Never throws, no matter what the path is (even if it is null, in which case it is resolving that throws).
     */
    private static FieldPath create(String path) {
        try {
            return parse(path);
        } catch (IllegalArgumentException e) {
            // keep the path around so that resolving can report the problem, at the same time as it always has
            return new Unparseable(path);
        }
    }

    /**
     * Parses the given path, using a cache of recently parsed paths to avoid repeating the work for paths that are used often.
     *
     * @param path The path within the document in dot-notation
     * @return the parsed path
     * @throws IllegalArgumentException if the path is null, empty, or has an empty path element anywhere but the end
     */
    static Parsed parse(String path) {
        if (Strings.isEmpty(path)) {
            throw new IllegalArgumentException("path cannot be null nor empty");
        }
        Parsed res = CACHE.get(path);
        if (res == null) {
            res = new Parsed(path);
            if (CACHE.size() > MAX_SIZE) {
                CACHE.clear();
            }
            CACHE.put(path, res);
        }
        return res;
    }

    /**
     * Returns the path to use for the given document. For a path that is the same for every document this is that same path, and
     * for a templated one it is the result of rendering the template with the document. This is never null: a {@link FieldPath} is
     * only ever null when it was created by {@link #ofNullable(String)} for a null path, in which case there is no instance to call
     * this on.
     *
     * @throws IllegalArgumentException if the path is null, empty, or otherwise malformed
     */
    public final FieldPath resolve(IngestDocument document) {
        return resolveParsed(document);
    }

    /**
     * Like {@link #resolve(IngestDocument)}, except that this returns <code>null</code> rather than throwing if the path is empty.
     * <p>
     * This is for the processors that have always treated an empty field as one that is missing, rather than as an invalid path, which
     * matters (together with <code>ignore_missing</code>) when a template renders as nothing for a document that doesn't have the
     * thing that the template refers to. Other malformed paths still throw.
     *
     * @return the path to use for the document, or null if it is empty
     * @throws IllegalArgumentException if the path is malformed (but not if it is empty)
     */
    @Nullable
    public abstract FieldPath resolveUnlessEmpty(IngestDocument document);

    /** The same as {@link #resolve}, but for the {@link IngestDocument}, which needs the parsed path. */
    abstract Parsed resolveParsed(IngestDocument document);

    /**
     * A path that is the same for every document, and that has been parsed into its path elements. Parsing is relatively expensive,
     * which is why this is not something that is done for every document, and why the parsed paths are cached.
     */
    static final class Parsed extends FieldPath {

        final String path;
        final String[] pathElements;
        private final boolean useIngestContext;
        private final boolean containsBrackets;

        // you shouldn't call this directly, use FieldPath.parse instead!
        //
        // constructing a new Parsed requires that we parse a String (e.g. "foo.bar.baz") into an array of path elements
        // (e.g. ["foo", "bar", "baz"]). Calling String#split results in the allocation of an ArrayList to hold the results, then a new
        // String is created for each path element, and then finally a String[] is allocated to hold the actual result -- in addition
        // to all that, we do some processing ourselves on the path and path elements to validate and prepare them. The cache and
        // the pre-parsing of paths that are known ahead of time allow us to almost always avoid this work.
        //
        // note that parsing is independent of the access pattern: the only thing that depends on the access pattern is whether
        // the parsed path is valid, and that is checked (cheaply) every time the path is used rather than being baked in here.
        private Parsed(String path) {
            this.path = path;
            String newPath;
            if (path.startsWith(IngestDocument.INGEST_PREFIX)) {
                useIngestContext = true;
                newPath = path.substring(IngestDocument.INGEST_PREFIX.length());
            } else {
                useIngestContext = false;
                newPath = path.startsWith(IngestDocument.SOURCE_PREFIX) ? path.substring(IngestDocument.SOURCE_PREFIX.length()) : path;
            }
            String[] pathParts = newPath.split("\\.");
            if (pathParts.length == 0) {
                // String#split drops trailing empty strings, so a path made up of nothing but separators (e.g. "." or "_source..")
                // yields no elements at all, which is just as invalid as an empty element in the beginning or middle of a path
                throw new IllegalArgumentException("path [" + path + "] is not valid");
            }
            boolean brackets = false;
            for (String pathPart : pathParts) {
                // empty path elements are not valid in any access pattern
                if (pathPart.isEmpty()) {
                    throw new IllegalArgumentException("path [" + path + "] is not valid");
                }
                brackets |= pathPart.contains("[") || pathPart.contains("]");
            }
            this.pathElements = pathParts;
            this.containsBrackets = brackets;
        }

        /**
         * Checks the access pattern specific rules for a parsed path. The {@link IngestPipelineFieldAccessPattern#CLASSIC} pattern
         * supports syntax like context aware array access, so it has no further restrictions. The
         * {@link IngestPipelineFieldAccessPattern#FLEXIBLE} pattern reserves square brackets for (future) array access syntax, so
         * path elements containing them are rejected.
         * @param accessPattern The access pattern to validate against
         * @throws IllegalArgumentException if this path is not valid for the access pattern
         */
        void validate(IngestPipelineFieldAccessPattern accessPattern) {
            switch (accessPattern) {
                case CLASSIC -> {
                }
                case FLEXIBLE -> {
                    if (containsBrackets) {
                        throw new IllegalArgumentException("path [" + path + "] is not valid");
                    }
                }
            }
        }

        Object initialContext(IngestDocument document) {
            return useIngestContext ? document.getIngestMetadata() : document.getCtxMap();
        }

        @Override
        Parsed resolveParsed(IngestDocument document) {
            return this;
        }

        @Override
        public FieldPath resolveUnlessEmpty(IngestDocument document) {
            return this; // a path that parsed can't be empty
        }

        @Override
        public String toString() {
            return path;
        }
    }

    /**
     * A path that is the same for every document but that could not be parsed. It is kept only in order to report why, when it is
     * resolved.
     */
    static final class Unparseable extends FieldPath {

        @Nullable
        private final String path; // only null if a constant template rendered as null, see FieldPath.of(TemplateScript.Factory)

        private Unparseable(@Nullable String path) {
            this.path = path;
        }

        @Override
        Parsed resolveParsed(IngestDocument document) {
            // this throws, for the same reason that it did when we first tried to parse it
            return parse(path);
        }

        @Override
        public FieldPath resolveUnlessEmpty(IngestDocument document) {
            return Strings.isEmpty(path) ? null : parse(path);
        }

        /** The path, exactly as it was configured. */
        @Override
        public String toString() {
            return String.valueOf(path);
        }
    }

    /** A path that is different for different documents, as it is the result of rendering a template with the document. */
    static final class Templated extends FieldPath {

        private final TemplateScript.Factory template;

        private Templated(TemplateScript.Factory template) {
            this.template = template;
        }

        @Override
        Parsed resolveParsed(IngestDocument document) {
            return parse(document.renderTemplate(template));
        }

        @Override
        public FieldPath resolveUnlessEmpty(IngestDocument document) {
            String rendered = document.renderTemplate(template);
            return Strings.isEmpty(rendered) ? null : parse(rendered);
        }

        /** A templated path has no one path to show, so this is just a description of it. */
        @Override
        public String toString() {
            return "[templated path]";
        }
    }
}
