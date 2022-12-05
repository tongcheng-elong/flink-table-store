/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.store.connector;

import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.fs.Path;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.catalog.ObjectIdentifier;
import org.apache.flink.table.connector.Projection;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.data.conversion.DataStructureConverter;
import org.apache.flink.table.data.conversion.DataStructureConverters;
import org.apache.flink.table.runtime.typeutils.InternalSerializers;
import org.apache.flink.table.runtime.typeutils.InternalTypeInfo;
import org.apache.flink.table.store.CoreOptions;
import org.apache.flink.table.store.connector.sink.FlinkSinkBuilder;
import org.apache.flink.table.store.connector.sink.StoreSink;
import org.apache.flink.table.store.connector.source.ContinuousFileStoreSource;
import org.apache.flink.table.store.connector.source.FlinkSourceBuilder;
import org.apache.flink.table.store.connector.source.StaticFileStoreSource;
import org.apache.flink.table.store.file.schema.SchemaManager;
import org.apache.flink.table.store.file.schema.UpdateSchema;
import org.apache.flink.table.store.file.utils.BlockingIterator;
import org.apache.flink.table.store.file.utils.FailingAtomicRenameFileSystem;
import org.apache.flink.table.store.table.FileStoreTable;
import org.apache.flink.table.store.table.FileStoreTableFactory;
import org.apache.flink.table.types.logical.IntType;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.table.types.logical.VarCharType;
import org.apache.flink.table.types.utils.TypeConversions;
import org.apache.flink.test.util.AbstractTestBase;
import org.apache.flink.types.Row;
import org.apache.flink.types.RowKind;
import org.apache.flink.util.CloseableIterator;

import org.junit.Assume;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.flink.table.store.CoreOptions.BUCKET;
import static org.apache.flink.table.store.CoreOptions.FILE_FORMAT;
import static org.apache.flink.table.store.CoreOptions.PATH;
import static org.apache.flink.table.store.file.utils.FailingAtomicRenameFileSystem.retryArtificialException;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ITCase for {@link StaticFileStoreSource}, {@link ContinuousFileStoreSource} and {@link
 * StoreSink}.
 */
@RunWith(Parameterized.class)
public class FileStoreITCase extends AbstractTestBase {

    public static final RowType TABLE_TYPE =
            new RowType(
                    Arrays.asList(
                            new RowType.RowField("v", new IntType()),
                            new RowType.RowField("p", new VarCharType()),
                            // rename key
                            new RowType.RowField("_k", new IntType())));

    public static final ObjectIdentifier IDENTIFIER = ObjectIdentifier.of("catalog", "db", "t");

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static final DataStructureConverter<RowData, Row> CONVERTER =
            (DataStructureConverter)
                    DataStructureConverters.getConverter(
                            TypeConversions.fromLogicalToDataType(TABLE_TYPE));

    private static final int NUM_BUCKET = 3;

    public static final List<RowData> SOURCE_DATA =
            Arrays.asList(
                    wrap(GenericRowData.of(0, StringData.fromString("p1"), 1)),
                    wrap(GenericRowData.of(0, StringData.fromString("p1"), 2)),
                    wrap(GenericRowData.of(0, StringData.fromString("p1"), 1)),
                    wrap(GenericRowData.of(5, StringData.fromString("p1"), 1)),
                    wrap(GenericRowData.of(6, StringData.fromString("p2"), 1)),
                    wrap(GenericRowData.of(3, StringData.fromString("p2"), 5)),
                    wrap(GenericRowData.of(5, StringData.fromString("p2"), 1)));

    private final boolean isBatch;

    private final StreamExecutionEnvironment env;

    public FileStoreITCase(boolean isBatch) throws IOException {
        this.isBatch = isBatch;
        this.env = isBatch ? buildBatchEnv() : buildStreamEnv();
    }

    @Parameterized.Parameters(name = "isBatch-{0}")
    public static List<Boolean> getVarSeg() {
        return Arrays.asList(true, false);
    }

    private static SerializableRowData wrap(RowData row) {
        return new SerializableRowData(row, InternalSerializers.create(TABLE_TYPE));
    }

    @Test
    public void testPartitioned() throws Exception {
        FileStoreTable table = buildFileStoreTable(new int[] {1}, new int[] {1, 2});

        // write
        new FlinkSinkBuilder(table).withInput(buildTestSource(env, isBatch)).build();
        env.execute();

        // read
        List<Row> results =
                executeAndCollect(new FlinkSourceBuilder(IDENTIFIER, table).withEnv(env).build());

        // assert
        Row[] expected =
                new Row[] {
                    Row.of(5, "p2", 1), Row.of(3, "p2", 5), Row.of(5, "p1", 1), Row.of(0, "p1", 2)
                };
        assertThat(results).containsExactlyInAnyOrder(expected);
    }

    @Test
    public void testNonPartitioned() throws Exception {
        FileStoreTable table = buildFileStoreTable(new int[0], new int[] {2});

        // write
        new FlinkSinkBuilder(table).withInput(buildTestSource(env, isBatch)).build();
        env.execute();

        // read
        List<Row> results =
                executeAndCollect(new FlinkSourceBuilder(IDENTIFIER, table).withEnv(env).build());

        // assert
        Row[] expected = new Row[] {Row.of(5, "p2", 1), Row.of(0, "p1", 2), Row.of(3, "p2", 5)};
        assertThat(results).containsExactlyInAnyOrder(expected);
    }

    @Test
    public void testOverwrite() throws Exception {
        Assume.assumeTrue(isBatch);

        FileStoreTable table = buildFileStoreTable(new int[] {1}, new int[] {1, 2});

        // write
        new FlinkSinkBuilder(table).withInput(buildTestSource(env, isBatch)).build();
        env.execute();

        // overwrite p2
        DataStreamSource<RowData> partialData =
                env.fromCollection(
                        Collections.singletonList(
                                wrap(GenericRowData.of(9, StringData.fromString("p2"), 5))),
                        InternalTypeInfo.of(TABLE_TYPE));
        Map<String, String> overwrite = new HashMap<>();
        overwrite.put("p", "p2");
        new FlinkSinkBuilder(table)
                .withInput(partialData)
                .withOverwritePartition(overwrite)
                .build();
        env.execute();

        // read
        List<Row> results =
                executeAndCollect(new FlinkSourceBuilder(IDENTIFIER, table).withEnv(env).build());

        Row[] expected = new Row[] {Row.of(9, "p2", 5), Row.of(5, "p1", 1), Row.of(0, "p1", 2)};
        assertThat(results).containsExactlyInAnyOrder(expected);

        // overwrite all
        partialData =
                env.fromCollection(
                        Collections.singletonList(
                                wrap(GenericRowData.of(19, StringData.fromString("p2"), 6))),
                        InternalTypeInfo.of(TABLE_TYPE));
        new FlinkSinkBuilder(table)
                .withInput(partialData)
                .withOverwritePartition(new HashMap<>())
                .build();
        env.execute();

        // read
        results = executeAndCollect(new FlinkSourceBuilder(IDENTIFIER, table).withEnv(env).build());
        expected = new Row[] {Row.of(19, "p2", 6)};
        assertThat(results).containsExactlyInAnyOrder(expected);
    }

    @Test
    public void testPartitionedNonKey() throws Exception {
        FileStoreTable table = buildFileStoreTable(new int[] {1}, new int[0]);

        // write
        new FlinkSinkBuilder(table).withInput(buildTestSource(env, isBatch)).build();
        env.execute();

        // read
        List<Row> results =
                executeAndCollect(new FlinkSourceBuilder(IDENTIFIER, table).withEnv(env).build());

        // assert
        // in streaming mode, expect origin data X 2 (FiniteTestSource)
        Stream<RowData> expectedStream =
                isBatch
                        ? SOURCE_DATA.stream()
                        : Stream.concat(SOURCE_DATA.stream(), SOURCE_DATA.stream());
        Row[] expected = expectedStream.map(CONVERTER::toExternal).toArray(Row[]::new);
        assertThat(results).containsExactlyInAnyOrder(expected);
    }

    @Test
    public void testKeyedProjection() throws Exception {
        testProjection(buildFileStoreTable(new int[0], new int[] {2}));
    }

    @Test
    public void testNonKeyedProjection() throws Exception {
        testProjection(buildFileStoreTable(new int[0], new int[0]));
    }

    private void testProjection(FileStoreTable table) throws Exception {
        // write
        new FlinkSinkBuilder(table).withInput(buildTestSource(env, isBatch)).build();
        env.execute();

        // read
        Projection projection = Projection.of(new int[] {1, 2});
        @SuppressWarnings({"unchecked", "rawtypes"})
        DataStructureConverter<RowData, Row> converter =
                (DataStructureConverter)
                        DataStructureConverters.getConverter(
                                TypeConversions.fromLogicalToDataType(
                                        projection.project(TABLE_TYPE)));
        List<Row> results =
                executeAndCollect(
                        new FlinkSourceBuilder(IDENTIFIER, table)
                                .withProjection(projection.toNestedIndexes())
                                .withEnv(env)
                                .build(),
                        converter);

        // assert
        Row[] expected = new Row[] {Row.of("p2", 1), Row.of("p1", 2), Row.of("p2", 5)};
        if (table.schema().trimmedPrimaryKeys().isEmpty()) {
            // in streaming mode, expect origin data X 2 (FiniteTestSource)
            Stream<RowData> expectedStream =
                    isBatch
                            ? SOURCE_DATA.stream()
                            : Stream.concat(SOURCE_DATA.stream(), SOURCE_DATA.stream());
            expected =
                    expectedStream
                            .map(CONVERTER::toExternal)
                            .map(r -> Row.of(r.getField(1), r.getField(2)))
                            .toArray(Row[]::new);
        }
        assertThat(results).containsExactlyInAnyOrder(expected);
    }

    @Test
    public void testContinuous() throws Exception {
        innerTestContinuous(buildFileStoreTable(new int[0], new int[] {2}));
    }

    @Test
    public void testContinuousWithoutPK() throws Exception {
        innerTestContinuous(buildFileStoreTable(new int[0], new int[0]));
    }

    private void innerTestContinuous(FileStoreTable table) throws Exception {
        Assume.assumeFalse(isBatch);

        BlockingIterator<RowData, Row> iterator =
                BlockingIterator.of(
                        new FlinkSourceBuilder(IDENTIFIER, table)
                                .withContinuousMode(true)
                                .withEnv(env)
                                .build()
                                .executeAndCollect(),
                        CONVERTER::toExternal);
        Thread.sleep(ThreadLocalRandom.current().nextInt(1000));

        sinkAndValidate(
                table,
                Arrays.asList(
                        srcRow(RowKind.INSERT, 1, "p1", 1), srcRow(RowKind.INSERT, 2, "p2", 2)),
                iterator,
                Row.ofKind(RowKind.INSERT, 1, "p1", 1),
                Row.ofKind(RowKind.INSERT, 2, "p2", 2));

        sinkAndValidate(
                table,
                Arrays.asList(
                        srcRow(RowKind.DELETE, 1, "p1", 1), srcRow(RowKind.INSERT, 3, "p3", 3)),
                iterator,
                Row.ofKind(RowKind.DELETE, 1, "p1", 1),
                Row.ofKind(RowKind.INSERT, 3, "p3", 3));
    }

    private void sinkAndValidate(
            FileStoreTable table,
            List<RowData> src,
            BlockingIterator<RowData, Row> iterator,
            Row... expected)
            throws Exception {
        if (isBatch) {
            throw new UnsupportedOperationException();
        }
        DataStreamSource<RowData> source =
                env.addSource(new FiniteTestSource<>(src, true), InternalTypeInfo.of(TABLE_TYPE));
        new FlinkSinkBuilder(table).withInput(source).build();
        env.execute();
        assertThat(iterator.collect(expected.length)).containsExactlyInAnyOrder(expected);
    }

    public FileStoreTable buildFileStoreTable(int[] partitions, int[] primaryKey) throws Exception {
        return buildFileStoreTable(isBatch, TEMPORARY_FOLDER, partitions, primaryKey);
    }

    private static RowData srcRow(RowKind kind, int v, String p, int k) {
        return wrap(GenericRowData.ofKind(kind, v, StringData.fromString(p), k));
    }

    public static StreamExecutionEnvironment buildStreamEnv() {
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setRuntimeMode(RuntimeExecutionMode.STREAMING);
        env.enableCheckpointing(100);
        env.setParallelism(2);
        return env;
    }

    public static StreamExecutionEnvironment buildBatchEnv() {
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setRuntimeMode(RuntimeExecutionMode.BATCH);
        env.setParallelism(2);
        return env;
    }

    public static FileStoreTable buildFileStoreTable(
            boolean noFail, TemporaryFolder temporaryFolder, int[] partitions, int[] primaryKey)
            throws Exception {
        Configuration options = buildConfiguration(noFail, temporaryFolder.newFolder());
        Path tablePath = new CoreOptions(options).path();
        UpdateSchema updateSchema =
                new UpdateSchema(
                        TABLE_TYPE,
                        Arrays.stream(partitions)
                                .mapToObj(i -> TABLE_TYPE.getFieldNames().get(i))
                                .collect(Collectors.toList()),
                        Arrays.stream(primaryKey)
                                .mapToObj(i -> TABLE_TYPE.getFieldNames().get(i))
                                .collect(Collectors.toList()),
                        options.toMap(),
                        "");
        return retryArtificialException(
                () -> {
                    new SchemaManager(tablePath).commitNewVersion(updateSchema);
                    return FileStoreTableFactory.create(options);
                });
    }

    public static Configuration buildConfiguration(boolean noFail, File folder) {
        Configuration options = new Configuration();
        options.set(BUCKET, NUM_BUCKET);
        if (noFail) {
            options.set(PATH, folder.toURI().toString());
        } else {
            String failingName = UUID.randomUUID().toString();
            FailingAtomicRenameFileSystem.reset(failingName, 3, 100);
            options.set(
                    PATH,
                    FailingAtomicRenameFileSystem.getFailingPath(failingName, folder.getPath()));
        }
        options.set(FILE_FORMAT, "avro");
        return options;
    }

    public static DataStreamSource<RowData> buildTestSource(
            StreamExecutionEnvironment env, boolean isBatch) {
        return isBatch
                ? env.fromCollection(SOURCE_DATA, InternalTypeInfo.of(TABLE_TYPE))
                : env.addSource(
                        new FiniteTestSource<>(SOURCE_DATA, false),
                        InternalTypeInfo.of(TABLE_TYPE));
    }

    public static List<Row> executeAndCollect(DataStreamSource<RowData> source) throws Exception {
        return executeAndCollect(source, CONVERTER);
    }

    public static List<Row> executeAndCollect(
            DataStreamSource<RowData> source, DataStructureConverter<RowData, Row> converter)
            throws Exception {
        CloseableIterator<RowData> iterator = source.executeAndCollect();
        List<Row> results = new ArrayList<>();
        while (iterator.hasNext()) {
            results.add(converter.toExternal(iterator.next()));
        }
        iterator.close();
        return results;
    }
}
