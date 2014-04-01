/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.operation.collect.files;

import com.google.common.collect.ImmutableMap;
import io.crate.DataType;
import io.crate.metadata.DynamicFunctionResolver;
import io.crate.metadata.FunctionIdent;
import io.crate.metadata.FunctionImplementation;
import io.crate.metadata.Functions;
import io.crate.operation.projectors.CollectingProjector;
import io.crate.operation.reference.file.FileLineReferenceResolver;
import org.apache.lucene.util.BytesRef;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static io.crate.testing.TestingHelpers.createReference;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;


public class FileReadingCollectorTest {

    private File tmpFile;
    private File tmpFileGz;
    private FileCollectInputSymbolVisitor inputSymbolVisitor;

    @Before
    public void setUp() throws Exception {
        Path copy_from = Files.createTempDirectory("copy_from");
        Path copy_from_gz = Files.createTempDirectory("copy_from_gz");
        tmpFileGz = File.createTempFile("fileReadingCollector", ".json.gz", copy_from_gz.toFile());
        tmpFile = File.createTempFile("fileReadingCollector", ".json", copy_from.toFile());
        try (BufferedWriter writer =
                     new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(tmpFileGz))))) {
            writer.write("{\"name\": \"Arthur\", \"id\": 4, \"details\": {\"age\": 38}}\n");
            writer.write("{\"id\": 5, \"name\": \"Trillian\", \"details\": {\"age\": 33}}\n");
        }
        try (FileWriter writer = new FileWriter(tmpFile)) {
            writer.write("{\"name\": \"Arthur\", \"id\": 4, \"details\": {\"age\": 38}}\n");
            writer.write("{\"id\": 5, \"name\": \"Trillian\", \"details\": {\"age\": 33}}\n");
        }
        Functions functions = new Functions(
                ImmutableMap.<FunctionIdent, FunctionImplementation>of(),
                ImmutableMap.<String, DynamicFunctionResolver>of()
        );
        inputSymbolVisitor =
                new FileCollectInputSymbolVisitor(functions, FileLineReferenceResolver.INSTANCE);
    }

    @After
    public void tearDown() throws Exception {
        tmpFile.delete();
        tmpFileGz.delete();
    }

    @Test
    public void testNoErrorIfNoSuchFile() throws Throwable {
        // no error, -> don't want to fail just because one node doesn't have a file
        getObjects("/some/path/that/shouldnt/exist/foo.json");
    }

    @Test
    public void testCollectFromUriWithRegex() throws Throwable {
        CollectingProjector projector = getObjects(tmpFile.getParent() + "/file.*\\.json");
        assertCorrectResult(projector.result().get());
    }

    @Test
    public void testCollectFromDirectory() throws Throwable {
        CollectingProjector projector = getObjects(tmpFile.getParent());
        assertCorrectResult(projector.result().get());
    }

    @Test
    public void testDoCollectRaw() throws Throwable {
        CollectingProjector projector = getObjects(tmpFile.getAbsolutePath());
        assertCorrectResult(projector.result().get());
    }

    @Test
    public void testDoCollectRawFromCompressed() throws Throwable {
        CollectingProjector projector = getObjects(tmpFileGz.getAbsolutePath(), true);
        assertCorrectResult(projector.result().get());
    }

    private void assertCorrectResult(Object[][] rows) throws Throwable {
        assertThat(((BytesRef)rows[0][0]).utf8ToString(), is(
                "{\"name\": \"Arthur\", \"id\": 4, \"details\": {\"age\": 38}}"));
        assertThat(((BytesRef)rows[1][0]).utf8ToString(), is(
                "{\"id\": 5, \"name\": \"Trillian\", \"details\": {\"age\": 33}}"));
    }

    private CollectingProjector getObjects(String fileUri) throws IOException {
        return getObjects(fileUri, false);
    }

    private CollectingProjector getObjects(String fileUri, boolean compressed) throws IOException {
        CollectingProjector projector = new CollectingProjector();
        FileCollectInputSymbolVisitor.Context context =
                inputSymbolVisitor.process(createReference("_raw", DataType.STRING));
        FileReadingCollector collector = new FileReadingCollector(
                fileUri,
                context.topLevelInputs(),
                context.expressions(),
                projector,
                FileReadingCollector.FileFormat.JSON,
                compressed
        );
        projector.startProjection();
        collector.doCollect();
        return projector;
    }
}