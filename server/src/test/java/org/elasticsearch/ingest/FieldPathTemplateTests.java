/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.ingest;

import org.elasticsearch.ingest.IngestDocument.FieldPath;
import org.elasticsearch.script.TemplateScript;
import org.elasticsearch.test.ESTestCase;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

public class FieldPathTemplateTests extends ESTestCase {

    /**
     * A template that renders to the value of the document's <code>name</code> field, in order to have something that is genuinely
     * different from one document to the next.
     */
    private static final TemplateScript.Factory NAME_TEMPLATE = params -> new TemplateScript(params) {
        @Override
        public String execute() {
            return (String) params.get("name");
        }
    };

    private static IngestDocument documentNamed(String name) {
        IngestDocument document = TestIngestDocument.emptyIngestDocument();
        document.setFieldValue("name", name);
        return document;
    }

    public void testConstantTemplateIsParsedOnlyOnce() {
        TemplateScript.Factory template = ConfigurationUtils.compileTemplate(
            "test",
            "tag",
            "field",
            "foo.bar",
            TestTemplateService.instance()
        );
        // this is what makes it safe to pre-parse the template
        assertThat(template, instanceOf(ConfigurationUtils.ConstantTemplateScriptFactory.class));

        FieldPathTemplate fieldPathTemplate = FieldPathTemplate.of(template);
        FieldPath first = fieldPathTemplate.resolve(documentNamed("one"));
        FieldPath second = fieldPathTemplate.resolve(documentNamed("two"));
        assertThat(first.toString(), equalTo("foo.bar"));
        assertThat(second, sameInstance(first));
    }

    public void testConstantPathIsParsedOnlyOnce() {
        FieldPathTemplate fieldPathTemplate = FieldPathTemplate.constant("foo.bar");
        FieldPath first = fieldPathTemplate.resolve(documentNamed("one"));
        assertThat(first.toString(), equalTo("foo.bar"));
        assertThat(fieldPathTemplate.resolve(documentNamed("two")), sameInstance(first));
    }

    public void testRealTemplateIsRenderedForEveryDocument() {
        FieldPathTemplate fieldPathTemplate = FieldPathTemplate.of(NAME_TEMPLATE);
        FieldPath first = fieldPathTemplate.resolve(documentNamed("foo.bar"));
        FieldPath second = fieldPathTemplate.resolve(documentNamed("baz"));
        assertThat(first.toString(), equalTo("foo.bar"));
        assertThat(second.toString(), equalTo("baz"));
        assertThat(second, not(sameInstance(first)));
    }

    public void testRealTemplateWithMalformedResult() {
        FieldPathTemplate fieldPathTemplate = FieldPathTemplate.of(NAME_TEMPLATE);
        assertThat(fieldPathTemplate.resolve(documentNamed("fine")).toString(), equalTo("fine"));
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> fieldPathTemplate.resolve(documentNamed("a..b")));
        assertThat(e.getMessage(), equalTo("path [a..b] is not valid"));
        // a bad document does not poison later ones
        assertThat(fieldPathTemplate.resolve(documentNamed("fine")).toString(), equalTo("fine"));
    }

    /**
     * Pipelines are stored, so a pipeline that contains a malformed path has to still load. It has only ever failed when a document is
     * run through it, and that has to stay the case, so creating a {@link FieldPathTemplate} must not throw.
     */
    public void testMalformedConstantPathFailsOnlyWhenResolved() {
        TemplateScript.Factory template = ConfigurationUtils.compileTemplate(
            "test",
            "tag",
            "field",
            "a..b",
            TestTemplateService.instance()
        );
        assertThat(template, instanceOf(ConfigurationUtils.ConstantTemplateScriptFactory.class));

        for (FieldPathTemplate fieldPathTemplate : new FieldPathTemplate[] {
            FieldPathTemplate.of(template),
            FieldPathTemplate.constant("a..b") }) {
            // and it fails the same way every time, not just the first time
            for (int i = 0; i < 3; i++) {
                IllegalArgumentException e = expectThrows(
                    IllegalArgumentException.class,
                    () -> fieldPathTemplate.resolve(documentNamed("irrelevant"))
                );
                assertThat(e.getMessage(), equalTo("path [a..b] is not valid"));
            }
        }
    }

    /**
     * Optional paths (like a set processor's <code>copy_from</code>) can be passed straight through, with null meaning "no path".
     */
    public void testNullConstantPathIsNull() {
        assertNull(FieldPathTemplate.constant(null));
    }

    /**
     * Unlike a null path, which means that there isn't one, a path that is present but empty is a (malformed) path.
     */
    public void testEmptyConstantPathsFailOnlyWhenResolved() {
        for (FieldPathTemplate fieldPathTemplate : new FieldPathTemplate[] {
            FieldPathTemplate.constant(""),
            FieldPathTemplate.of(ConfigurationUtils.compileTemplate("test", "tag", "field", "", TestTemplateService.instance())) }) {
            IllegalArgumentException e = expectThrows(
                IllegalArgumentException.class,
                () -> fieldPathTemplate.resolve(documentNamed("irrelevant"))
            );
            assertThat(e.getMessage(), equalTo("path cannot be null nor empty"));
        }
    }

    public void testToString() {
        assertThat(FieldPathTemplate.constant("foo.bar").toString(), equalTo("foo.bar"));
        // malformed and empty paths are still shown the way that they were configured
        assertThat(FieldPathTemplate.constant("a..b").toString(), equalTo("a..b"));
        assertThat(FieldPathTemplate.constant("").toString(), equalTo(""));
        TemplateScript.Factory constantTemplate = ConfigurationUtils.compileTemplate(
            "test",
            "tag",
            "field",
            "foo.bar",
            TestTemplateService.instance()
        );
        assertThat(FieldPathTemplate.of(constantTemplate).toString(), equalTo("foo.bar"));
        assertThat(FieldPathTemplate.of(NAME_TEMPLATE).toString(), equalTo("[templated path]"));
    }

    /**
     * A path that is only invalid for some access patterns is fine to parse up front, the access pattern is checked when it is used.
     */
    public void testPathThatIsOnlyInvalidForSomeAccessPatternsCanBeCreated() {
        FieldPathTemplate fieldPathTemplate = FieldPathTemplate.constant("a[0]");
        assertThat(fieldPathTemplate.resolve(documentNamed("irrelevant")).toString(), equalTo("a[0]"));
    }
}
