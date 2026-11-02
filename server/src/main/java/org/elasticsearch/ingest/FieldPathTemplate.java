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
import org.elasticsearch.core.Nullable;
import org.elasticsearch.ingest.IngestDocument.FieldPath;
import org.elasticsearch.script.TemplateScript;

import java.util.Map;
import java.util.Objects;

/**
 * Produces the {@link FieldPath} that a processor should use for one of its configured fields, doing as much of the work as possible
 * up front, when the processor is created, rather than for every document.
 * <p>
 * Processors are configured with field names that are either plain strings or templates (e.g. <code>{{foo.bar}}</code>). Plain strings,
 * and templates that do not actually contain any templating, are the same for every document, so they only need to be parsed once. Only
 * the genuinely templated ones have to be rendered and parsed for every document.
 * <p>
 * Creating a {@link FieldPathTemplate} never fails because a path is malformed. That is deliberate: pipelines are stored, and one that
 * contains a malformed path must continue to load and to fail only when a document is actually run through it, as it always has. A
 * malformed path therefore throws the same {@link IllegalArgumentException} that parsing it would have, but from
 * {@link #resolve(IngestDocument)} instead.
 * <p>
 * A path that is null is a different matter, as it is not a malformed path but a missing one. Required paths are created with
 * {@link #of(String)}, which rejects null as the programming error that it is. Optional paths are created with
 * {@link #ofNullable(String)}, which returns <code>null</code> when it is given <code>null</code>, so that callers can pass the configured
 * value straight through and then simply check whether the result is <code>null</code>, rather than having to guard every call.
 */
public final class FieldPathTemplate {

    /** The path, pre-parsed, if it is the same for every document and could be parsed. */
    private final FieldPath parsed;
    /** The path, unparsed, if it is the same for every document but could not be parsed. Only kept in order to report why. */
    private final String unparseable;
    /** The template to render for every document, if the path is not the same for every document. */
    private final TemplateScript.Factory template;

    private FieldPathTemplate(FieldPath parsed, String unparseable, TemplateScript.Factory template) {
        this.parsed = parsed;
        this.unparseable = unparseable;
        this.template = template;
    }

    /**
     * Parses a path that is the same for every document. Never returns null and never throws, no matter what the path is (even if it
     * is null, in which case it is {@link #resolve(IngestDocument)} that throws).
     */
    private static FieldPathTemplate parse(String path) {
        try {
            return new FieldPathTemplate(FieldPath.of(path), null, null);
        } catch (IllegalArgumentException e) {
            // keep the path around so that resolve can report the problem, at the same time as it always has
            return new FieldPathTemplate(null, path, null);
        }
    }

    /**
     * Creates a {@link FieldPathTemplate} from a template, as produced by
     * {@link ConfigurationUtils#compileTemplate(String, String, String, String, org.elasticsearch.script.ScriptService)}.
     * If the template is a {@link ConfigurationUtils.ConstantTemplateScriptFactory} then it is evaluated (and parsed) here, once.
     * Otherwise, it will be rendered for every document.
     *
     * @throws NullPointerException if the template is null, which is a programming error, so it is reported when the processor is
     * created rather than for every document that it is later used with
     */
    public static FieldPathTemplate of(TemplateScript.Factory template) {
        Objects.requireNonNull(template, "template cannot be null");
        if (template instanceof ConfigurationUtils.ConstantTemplateScriptFactory) {
            // a constant template ignores the model that it is rendered against, so it doesn't need a document
            //
            // note that this deliberately goes through parse, and not through of or ofNullable. The template here is not optional, so
            // this method must never return null, and a constant template is not something that a caller can be blamed for if it
            // renders as null (in which case the result is the same as it would have been for a template that renders as null for
            // every document: resolve throws).
            return parse(template.newInstance(Map.of()).execute());
        }
        return new FieldPathTemplate(null, null, template);
    }

    /**
     * Creates a {@link FieldPathTemplate} for a path that is the same for every document, such as a processor's <code>field</code>. This
     * is for required paths: use {@link #ofNullable(String)} for the ones that are optional.
     * <p>
     * A path that is malformed (or empty) is not rejected here, see the class documentation. A path that is null is, though, as that is
     * not something that a configuration can cause, so it is a programming error and it is better to find out about it when the processor
     * is created than when it is first used.
     *
     * @param path the path
     * @throws NullPointerException if the path is null
     */
    public static FieldPathTemplate of(String path) {
        Objects.requireNonNull(path, "path cannot be null");
        return parse(path);
    }

    /**
     * Creates a {@link FieldPathTemplate} for an optional path that is the same for every document, such as a processor's
     * <code>copy_from</code>. Use {@link #of(String)} for the ones that are required.
     * <p>
     * Unlike the other methods, this returns <code>null</code> if the given path is <code>null</code>. That makes it convenient for
     * optional fields: pass in the configured value (which is null if it was not configured) and check the result for <code>null</code>
     * before calling {@link #resolve(IngestDocument)} on it. A path that is present but empty or malformed is <em>not</em> treated
     * that way, see the class documentation.
     *
     * @param path the path, or null if there is no path
     * @return the {@link FieldPathTemplate} for the path, or null if the path is null
     */
    @Nullable
    public static FieldPathTemplate ofNullable(@Nullable String path) {
        if (path == null) {
            return null; // there's nothing to resolve, and callers are expected to check for this (see above)
        }
        return parse(path);
    }

    /**
     * Returns the path to use for the given document. This is never null: a {@link FieldPathTemplate} is only ever null when it was
     * created by {@link #ofNullable(String)} for a null path, in which case there is no instance to call this on.
     *
     * @throws IllegalArgumentException if the path is null, empty, or otherwise malformed
     */
    public FieldPath resolve(IngestDocument document) {
        if (template != null) {
            return FieldPath.of(document.renderTemplate(template));
        } else if (parsed != null) {
            return parsed;
        } else {
            // This throws, for the same reason that it did when we first tried to parse it. (The path can only be null here if a
            // constant template rendered as null, see 'of'. Parsing a null path also throws, so that is covered too.)
            return FieldPath.of(unparseable);
        }
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
    public FieldPath resolveUnlessEmpty(IngestDocument document) {
        if (template != null) {
            String rendered = document.renderTemplate(template);
            return Strings.isEmpty(rendered) ? null : FieldPath.of(rendered);
        } else if (parsed != null) {
            return parsed; // a path that parsed can't be empty
        } else {
            return Strings.isEmpty(unparseable) ? null : FieldPath.of(unparseable);
        }
    }

    /**
     * For a path that is the same for every document this is that path, exactly as it was configured (even if it turned out to be
     * malformed). A real template has no one path to show, so this is just a description of it.
     */
    @Override
    public String toString() {
        if (template != null) {
            return "[templated path]";
        } else if (parsed != null) {
            return parsed.toString();
        } else {
            return String.valueOf(unparseable);
        }
    }
}
