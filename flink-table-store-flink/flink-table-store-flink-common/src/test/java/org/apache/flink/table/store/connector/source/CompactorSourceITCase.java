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

package org.apache.flink.table.store.connector.source;

import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.store.connector.util.AbstractTestBase;
import org.apache.flink.table.store.data.BinaryRow;
import org.apache.flink.table.store.data.BinaryRowWriter;
import org.apache.flink.table.store.data.BinaryString;
import org.apache.flink.table.store.data.GenericRow;
import org.apache.flink.table.store.file.io.DataFileMetaSerializer;
import org.apache.flink.table.store.file.schema.Schema;
import org.apache.flink.table.store.file.schema.SchemaManager;
import org.apache.flink.table.store.file.schema.TableSchema;
import org.apache.flink.table.store.fs.Path;
import org.apache.flink.table.store.fs.local.LocalFileIO;
import org.apache.flink.table.store.table.FileStoreTable;
import org.apache.flink.table.store.table.FileStoreTableFactory;
import org.apache.flink.table.store.table.sink.StreamTableCommit;
import org.apache.flink.table.store.table.sink.StreamTableWrite;
import org.apache.flink.table.store.types.DataType;
import org.apache.flink.table.store.types.DataTypes;
import org.apache.flink.table.store.types.RowType;
import org.apache.flink.util.CloseableIterator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** IT cases for {@link CompactorSourceBuilder}. */
public class CompactorSourceITCase extends AbstractTestBase {

    private static final RowType ROW_TYPE =
            RowType.of(
                    new DataType[] {
                        DataTypes.INT(), DataTypes.INT(), DataTypes.STRING(), DataTypes.INT()
                    },
                    new String[] {"k", "v", "dt", "hh"});

    private final DataFileMetaSerializer dataFileMetaSerializer = new DataFileMetaSerializer();

    private Path tablePath;
    private String commitUser;

    @BeforeEach
    public void before() throws IOException {
        tablePath = new Path(getTempDirPath());
        commitUser = UUID.randomUUID().toString();
    }

    @Test
    public void testBatchRead() throws Exception {
        FileStoreTable table = createFileStoreTable();
        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);

        write.write(rowData(1, 1510, BinaryString.fromString("20221208"), 15));
        write.write(rowData(2, 1620, BinaryString.fromString("20221208"), 16));
        commit.commit(0, write.prepareCommit(true, 0));

        write.write(rowData(1, 1511, BinaryString.fromString("20221208"), 15));
        write.write(rowData(1, 1510, BinaryString.fromString("20221209"), 15));
        commit.commit(1, write.prepareCommit(true, 1));

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        DataStreamSource<RowData> compactorSource =
                new CompactorSourceBuilder("test", table)
                        .withContinuousMode(false)
                        .withEnv(env)
                        .build();
        CloseableIterator<RowData> it = compactorSource.executeAndCollect();

        List<String> actual = new ArrayList<>();
        while (it.hasNext()) {
            actual.add(toString(it.next()));
        }
        assertThat(actual)
                .hasSameElementsAs(
                        Arrays.asList(
                                "+I 2|20221208|15|0|0",
                                "+I 2|20221208|16|0|0",
                                "+I 2|20221209|15|0|0"));

        write.close();
        commit.close();
        it.close();
    }

    @Test
    public void testStreamingRead() throws Exception {
        FileStoreTable table = createFileStoreTable();
        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);

        write.write(rowData(1, 1510, BinaryString.fromString("20221208"), 15));
        write.write(rowData(2, 1620, BinaryString.fromString("20221208"), 16));
        commit.commit(0, write.prepareCommit(true, 0));

        write.write(rowData(1, 1511, BinaryString.fromString("20221208"), 15));
        write.write(rowData(1, 1510, BinaryString.fromString("20221209"), 15));
        write.compact(binaryRow("20221208", 15), 0, true);
        write.compact(binaryRow("20221209", 15), 0, true);
        commit.commit(1, write.prepareCommit(true, 1));

        write.write(rowData(2, 1520, BinaryString.fromString("20221208"), 15));
        write.write(rowData(2, 1621, BinaryString.fromString("20221208"), 16));
        commit.commit(2, write.prepareCommit(true, 2));

        write.write(rowData(1, 1512, BinaryString.fromString("20221208"), 15));
        write.write(rowData(2, 1620, BinaryString.fromString("20221209"), 16));
        commit.commit(3, write.prepareCommit(true, 3));

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        DataStreamSource<RowData> compactorSource =
                new CompactorSourceBuilder("test", table)
                        .withContinuousMode(true)
                        .withEnv(env)
                        .build();
        CloseableIterator<RowData> it = compactorSource.executeAndCollect();

        List<String> actual = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            actual.add(toString(it.next()));
        }
        assertThat(actual)
                .hasSameElementsAs(
                        Arrays.asList(
                                "+I 4|20221208|15|0|1",
                                "+I 4|20221208|16|0|1",
                                "+I 5|20221208|15|0|1",
                                "+I 5|20221209|16|0|1"));

        write.write(rowData(2, 1520, BinaryString.fromString("20221209"), 15));
        write.write(rowData(1, 1510, BinaryString.fromString("20221208"), 16));
        write.write(rowData(1, 1511, BinaryString.fromString("20221209"), 15));
        commit.commit(4, write.prepareCommit(true, 4));

        actual.clear();
        for (int i = 0; i < 2; i++) {
            actual.add(toString(it.next()));
        }
        assertThat(actual)
                .hasSameElementsAs(Arrays.asList("+I 6|20221208|16|0|1", "+I 6|20221209|15|0|1"));

        write.close();
        commit.close();
        it.close();
    }

    @Test
    public void testStreamingPartitionSpec() throws Exception {
        testPartitionSpec(
                true,
                getSpecifiedPartitions(),
                Arrays.asList(
                        "+I 1|20221208|16|0|1",
                        "+I 2|20221209|15|0|1",
                        "+I 3|20221208|16|0|1",
                        "+I 3|20221209|15|0|1"));
    }

    @Test
    public void testBatchPartitionSpec() throws Exception {
        testPartitionSpec(
                false,
                getSpecifiedPartitions(),
                Arrays.asList("+I 3|20221208|16|0|0", "+I 3|20221209|15|0|0"));
    }

    private List<Map<String, String>> getSpecifiedPartitions() {
        Map<String, String> partition1 = new HashMap<>();
        partition1.put("dt", "20221208");
        partition1.put("hh", "16");

        Map<String, String> partition2 = new HashMap<>();
        partition2.put("dt", "20221209");
        partition2.put("hh", "15");

        return Arrays.asList(partition1, partition2);
    }

    private void testPartitionSpec(
            boolean isStreaming,
            List<Map<String, String>> specifiedPartitions,
            List<String> expected)
            throws Exception {
        FileStoreTable table = createFileStoreTable();
        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);

        write.write(rowData(1, 1510, BinaryString.fromString("20221208"), 15));
        write.write(rowData(2, 1620, BinaryString.fromString("20221208"), 16));
        commit.commit(0, write.prepareCommit(true, 0));

        write.write(rowData(2, 1520, BinaryString.fromString("20221208"), 15));
        write.write(rowData(2, 1520, BinaryString.fromString("20221209"), 15));
        commit.commit(1, write.prepareCommit(true, 1));

        write.write(rowData(1, 1511, BinaryString.fromString("20221208"), 15));
        write.write(rowData(1, 1610, BinaryString.fromString("20221208"), 16));
        write.write(rowData(1, 1510, BinaryString.fromString("20221209"), 15));
        commit.commit(2, write.prepareCommit(true, 2));

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        DataStreamSource<RowData> compactorSource =
                new CompactorSourceBuilder("test", table)
                        .withContinuousMode(isStreaming)
                        .withEnv(env)
                        .withPartitions(specifiedPartitions)
                        .build();
        CloseableIterator<RowData> it = compactorSource.executeAndCollect();

        List<String> actual = new ArrayList<>();
        for (int i = 0; i < expected.size(); i++) {
            actual.add(toString(it.next()));
        }
        assertThat(actual).hasSameElementsAs(expected);

        write.close();
        commit.close();
        it.close();
    }

    private String toString(RowData rowData) {
        int numFiles;
        try {
            numFiles = dataFileMetaSerializer.deserializeList(rowData.getBinary(4)).size();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return String.format(
                "%s %d|%s|%d|%d|%d",
                rowData.getRowKind().shortString(),
                rowData.getLong(0),
                rowData.getString(1).toString(),
                rowData.getInt(2),
                rowData.getInt(3),
                numFiles);
    }

    private GenericRow rowData(Object... values) {
        return GenericRow.of(values);
    }

    private BinaryRow binaryRow(String dt, int hh) {
        BinaryRow b = new BinaryRow(2);
        BinaryRowWriter writer = new BinaryRowWriter(b);
        writer.writeString(0, BinaryString.fromString(dt));
        writer.writeInt(1, hh);
        writer.complete();
        return b;
    }

    private FileStoreTable createFileStoreTable() throws Exception {
        SchemaManager schemaManager = new SchemaManager(LocalFileIO.create(), tablePath);
        TableSchema tableSchema =
                schemaManager.createTable(
                        new Schema(
                                ROW_TYPE.getFields(),
                                Arrays.asList("dt", "hh"),
                                Arrays.asList("dt", "hh", "k"),
                                Collections.emptyMap(),
                                ""));
        return FileStoreTableFactory.create(LocalFileIO.create(), tablePath, tableSchema);
    }
}
