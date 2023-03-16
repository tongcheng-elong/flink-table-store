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

package org.apache.flink.table.store.table;

import org.apache.flink.table.store.CoreOptions;
import org.apache.flink.table.store.CoreOptions.ChangelogProducer;
import org.apache.flink.table.store.data.BinaryString;
import org.apache.flink.table.store.data.GenericRow;
import org.apache.flink.table.store.data.InternalRow;
import org.apache.flink.table.store.file.WriteMode;
import org.apache.flink.table.store.file.operation.ScanKind;
import org.apache.flink.table.store.file.predicate.Predicate;
import org.apache.flink.table.store.file.predicate.PredicateBuilder;
import org.apache.flink.table.store.file.schema.Schema;
import org.apache.flink.table.store.file.schema.SchemaManager;
import org.apache.flink.table.store.file.schema.SchemaUtils;
import org.apache.flink.table.store.file.schema.TableSchema;
import org.apache.flink.table.store.fs.local.LocalFileIO;
import org.apache.flink.table.store.options.Options;
import org.apache.flink.table.store.table.sink.BatchTableCommit;
import org.apache.flink.table.store.table.sink.BatchTableWrite;
import org.apache.flink.table.store.table.sink.BatchWriteBuilder;
import org.apache.flink.table.store.table.sink.CommitMessage;
import org.apache.flink.table.store.table.sink.InnerTableCommit;
import org.apache.flink.table.store.table.sink.StreamTableCommit;
import org.apache.flink.table.store.table.sink.StreamTableWrite;
import org.apache.flink.table.store.table.source.DataSplit;
import org.apache.flink.table.store.table.source.ReadBuilder;
import org.apache.flink.table.store.table.source.Split;
import org.apache.flink.table.store.table.source.StreamDataTableScan;
import org.apache.flink.table.store.table.source.TableRead;
import org.apache.flink.table.store.table.source.TableScan;
import org.apache.flink.table.store.table.source.snapshot.FullStartingScanner;
import org.apache.flink.table.store.table.source.snapshot.InputChangelogFollowUpScanner;
import org.apache.flink.table.store.table.source.snapshot.SnapshotSplitReader;
import org.apache.flink.table.store.table.system.AuditLogTable;
import org.apache.flink.table.store.types.DataType;
import org.apache.flink.table.store.types.DataTypes;
import org.apache.flink.table.store.types.RowKind;
import org.apache.flink.table.store.types.RowType;
import org.apache.flink.table.store.utils.CompatibilityTestUtils;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.apache.flink.table.store.data.DataFormatTestUtil.rowDataToString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for {@link ChangelogWithKeyFileStoreTable}. */
public class ChangelogWithKeyFileStoreTableTest extends FileStoreTableTestBase {

    protected static final RowType COMPATIBILITY_ROW_TYPE =
            RowType.of(
                    new DataType[] {
                        DataTypes.INT(),
                        DataTypes.INT(),
                        DataTypes.BIGINT(),
                        DataTypes.BINARY(1),
                        DataTypes.VARBINARY(1)
                    },
                    new String[] {"pt", "a", "b", "c", "d"});

    protected static final Function<InternalRow, String> COMPATIBILITY_BATCH_ROW_TO_STRING =
            rowData ->
                    rowData.getInt(0)
                            + "|"
                            + rowData.getInt(1)
                            + "|"
                            + rowData.getLong(2)
                            + "|"
                            + new String(rowData.getBinary(3))
                            + "|"
                            + new String(rowData.getBinary(4));

    protected static final Function<InternalRow, String> COMPATIBILITY_CHANGELOG_ROW_TO_STRING =
            rowData ->
                    rowData.getRowKind().shortString()
                            + " "
                            + COMPATIBILITY_BATCH_ROW_TO_STRING.apply(rowData);

    @Test
    public void testBatchWriteBuilder() throws Exception {
        FileStoreTable table = createFileStoreTable();
        BatchWriteBuilder writeBuilder = table.newBatchWriteBuilder();
        BatchTableWrite write = writeBuilder.newWrite();
        BatchTableCommit commit = writeBuilder.newCommit();
        write.write(rowData(1, 10, 100L));
        write.write(rowData(1, 11, 101L));
        commit.commit(write.prepareCommit());

        assertThatThrownBy(write::prepareCommit)
                .hasMessageContaining("BatchTableWrite only support one-time committing");
        assertThatThrownBy(() -> commit.commit(Collections.emptyList()))
                .hasMessageContaining("BatchTableCommit only support one-time committing");

        write.close();
        commit.close();

        ReadBuilder readBuilder = table.newReadBuilder();
        List<Split> splits = toSplits(table.newSnapshotSplitReader().splits());
        TableRead read = readBuilder.newRead();
        assertThat(getResult(read, splits, BATCH_ROW_TO_STRING))
                .isEqualTo(
                        Arrays.asList(
                                "1|10|100|binary|varbinary|mapKey:mapVal|multiset",
                                "1|11|101|binary|varbinary|mapKey:mapVal|multiset"));
    }

    @Test
    public void testSequenceNumber() throws Exception {
        FileStoreTable table =
                createFileStoreTable(conf -> conf.set(CoreOptions.SEQUENCE_FIELD, "b"));
        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);
        write.write(rowData(1, 10, 200L));
        write.write(rowData(1, 10, 100L));
        write.write(rowData(1, 11, 101L));
        commit.commit(0, write.prepareCommit(true, 0));
        write.write(rowData(1, 11, 55L));
        commit.commit(1, write.prepareCommit(true, 1));
        write.close();

        List<Split> splits = toSplits(table.newSnapshotSplitReader().splits());
        TableRead read = table.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_ROW_TO_STRING))
                .isEqualTo(
                        Arrays.asList(
                                "1|10|200|binary|varbinary|mapKey:mapVal|multiset",
                                "1|11|101|binary|varbinary|mapKey:mapVal|multiset"));
    }

    @Test
    public void testBatchReadWrite() throws Exception {
        writeData();
        FileStoreTable table = createFileStoreTable();

        List<Split> splits = toSplits(table.newSnapshotSplitReader().splits());
        TableRead read = table.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_ROW_TO_STRING))
                .isEqualTo(
                        Collections.singletonList(
                                "1|10|1000|binary|varbinary|mapKey:mapVal|multiset"));
        assertThat(getResult(read, splits, binaryRow(2), 0, BATCH_ROW_TO_STRING))
                .isEqualTo(
                        Arrays.asList(
                                "2|21|20001|binary|varbinary|mapKey:mapVal|multiset",
                                "2|22|202|binary|varbinary|mapKey:mapVal|multiset"));
    }

    @Test
    public void testBatchProjection() throws Exception {
        writeData();
        FileStoreTable table = createFileStoreTable();

        List<Split> splits = toSplits(table.newSnapshotSplitReader().splits());
        TableRead read = table.newRead().withProjection(PROJECTION);
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_PROJECTED_ROW_TO_STRING))
                .isEqualTo(Collections.singletonList("1000|10"));
        assertThat(getResult(read, splits, binaryRow(2), 0, BATCH_PROJECTED_ROW_TO_STRING))
                .isEqualTo(Arrays.asList("20001|21", "202|22"));
    }

    @Test
    public void testBatchFilter() throws Exception {
        writeData();
        FileStoreTable table = createFileStoreTable();
        PredicateBuilder builder = new PredicateBuilder(table.schema().logicalRowType());

        Predicate predicate = PredicateBuilder.and(builder.equal(2, 201L), builder.equal(1, 21));
        List<Split> splits =
                toSplits(table.newSnapshotSplitReader().withFilter(predicate).splits());
        TableRead read = table.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_ROW_TO_STRING)).isEmpty();
        assertThat(getResult(read, splits, binaryRow(2), 0, BATCH_ROW_TO_STRING))
                .isEqualTo(
                        Arrays.asList(
                                // only filter on key should be performed,
                                // and records from the same file should also be selected
                                "2|21|20001|binary|varbinary|mapKey:mapVal|multiset",
                                "2|22|202|binary|varbinary|mapKey:mapVal|multiset"));
    }

    @Test
    public void testStreamingReadWrite() throws Exception {
        writeData();
        FileStoreTable table = createFileStoreTable();

        List<Split> splits =
                toSplits(table.newSnapshotSplitReader().withKind(ScanKind.DELTA).splits());
        TableRead read = table.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, STREAMING_ROW_TO_STRING))
                .isEqualTo(
                        Collections.singletonList(
                                "-1|11|1001|binary|varbinary|mapKey:mapVal|multiset"));
        assertThat(getResult(read, splits, binaryRow(2), 0, STREAMING_ROW_TO_STRING))
                .isEqualTo(
                        Arrays.asList(
                                "-2|20|200|binary|varbinary|mapKey:mapVal|multiset",
                                "+2|21|20001|binary|varbinary|mapKey:mapVal|multiset",
                                "+2|22|202|binary|varbinary|mapKey:mapVal|multiset"));
    }

    @Test
    public void testStreamingProjection() throws Exception {
        writeData();
        FileStoreTable table = createFileStoreTable();

        List<Split> splits =
                toSplits(table.newSnapshotSplitReader().withKind(ScanKind.DELTA).splits());
        TableRead read = table.newRead().withProjection(PROJECTION);

        assertThat(getResult(read, splits, binaryRow(1), 0, STREAMING_PROJECTED_ROW_TO_STRING))
                .isEqualTo(Collections.singletonList("-1001|11"));
        assertThat(getResult(read, splits, binaryRow(2), 0, STREAMING_PROJECTED_ROW_TO_STRING))
                .isEqualTo(Arrays.asList("-200|20", "+20001|21", "+202|22"));
    }

    @Test
    public void testStreamingFilter() throws Exception {
        writeData();
        FileStoreTable table = createFileStoreTable();
        PredicateBuilder builder = new PredicateBuilder(table.schema().logicalRowType());

        Predicate predicate = PredicateBuilder.and(builder.equal(2, 201L), builder.equal(1, 21));
        List<Split> splits =
                toSplits(
                        table.newSnapshotSplitReader()
                                .withKind(ScanKind.DELTA)
                                .withFilter(predicate)
                                .splits());
        TableRead read = table.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, STREAMING_ROW_TO_STRING)).isEmpty();
        assertThat(getResult(read, splits, binaryRow(2), 0, STREAMING_ROW_TO_STRING))
                .isEqualTo(
                        Arrays.asList(
                                // only filter on key should be performed,
                                // and records from the same file should also be selected
                                "-2|20|200|binary|varbinary|mapKey:mapVal|multiset",
                                "+2|21|20001|binary|varbinary|mapKey:mapVal|multiset",
                                "+2|22|202|binary|varbinary|mapKey:mapVal|multiset"));
    }

    @Test
    public void testStreamingInputChangelog() throws Exception {
        FileStoreTable table =
                createFileStoreTable(
                        conf -> conf.set(CoreOptions.CHANGELOG_PRODUCER, ChangelogProducer.INPUT));
        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);
        write.write(rowData(1, 10, 100L));
        write.write(rowData(1, 20, 200L));
        write.write(rowDataWithKind(RowKind.DELETE, 1, 10, 100L));
        write.write(rowData(1, 10, 101L));
        write.write(rowDataWithKind(RowKind.UPDATE_BEFORE, 1, 20, 200L));
        write.write(rowDataWithKind(RowKind.UPDATE_AFTER, 1, 20, 201L));
        write.write(rowDataWithKind(RowKind.UPDATE_BEFORE, 1, 10, 101L));
        write.write(rowDataWithKind(RowKind.UPDATE_AFTER, 1, 10, 102L));
        commit.commit(0, write.prepareCommit(true, 0));
        write.close();

        List<Split> splits =
                toSplits(table.newSnapshotSplitReader().withKind(ScanKind.CHANGELOG).splits());
        TableRead read = table.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, CHANGELOG_ROW_TO_STRING))
                .containsExactlyInAnyOrder(
                        "+I 1|10|100|binary|varbinary|mapKey:mapVal|multiset",
                        "+I 1|20|200|binary|varbinary|mapKey:mapVal|multiset",
                        "-D 1|10|100|binary|varbinary|mapKey:mapVal|multiset",
                        "+I 1|10|101|binary|varbinary|mapKey:mapVal|multiset",
                        "-U 1|20|200|binary|varbinary|mapKey:mapVal|multiset",
                        "+U 1|20|201|binary|varbinary|mapKey:mapVal|multiset",
                        "-U 1|10|101|binary|varbinary|mapKey:mapVal|multiset",
                        "+U 1|10|102|binary|varbinary|mapKey:mapVal|multiset");
    }

    @Test
    public void testStreamingFullChangelog() throws Exception {
        FileStoreTable table =
                createFileStoreTable(
                        conf ->
                                conf.set(
                                        CoreOptions.CHANGELOG_PRODUCER,
                                        ChangelogProducer.FULL_COMPACTION));
        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);

        write.write(rowData(1, 10, 110L));
        write.write(rowData(1, 20, 120L));
        write.write(rowData(2, 10, 210L));
        write.write(rowData(2, 20, 220L));
        write.write(rowDataWithKind(RowKind.DELETE, 2, 10, 210L));
        write.compact(binaryRow(1), 0, true);
        write.compact(binaryRow(2), 0, true);
        commit.commit(0, write.prepareCommit(true, 0));

        List<Split> splits =
                toSplits(table.newSnapshotSplitReader().withKind(ScanKind.CHANGELOG).splits());
        TableRead read = table.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, CHANGELOG_ROW_TO_STRING))
                .containsExactlyInAnyOrder(
                        "+I 1|10|110|binary|varbinary|mapKey:mapVal|multiset",
                        "+I 1|20|120|binary|varbinary|mapKey:mapVal|multiset");
        assertThat(getResult(read, splits, binaryRow(2), 0, CHANGELOG_ROW_TO_STRING))
                .containsExactlyInAnyOrder("+I 2|20|220|binary|varbinary|mapKey:mapVal|multiset");

        write.write(rowData(1, 30, 130L));
        write.write(rowData(1, 40, 140L));
        write.write(rowData(2, 30, 230L));
        write.write(rowData(2, 40, 240L));
        commit.commit(1, write.prepareCommit(true, 1));

        write.write(rowDataWithKind(RowKind.DELETE, 1, 40, 140L));
        write.write(rowData(2, 40, 241L));
        write.compact(binaryRow(1), 0, true);
        write.compact(binaryRow(2), 0, true);
        commit.commit(2, write.prepareCommit(true, 2));

        splits = toSplits(table.newSnapshotSplitReader().withKind(ScanKind.CHANGELOG).splits());
        assertThat(getResult(read, splits, binaryRow(1), 0, CHANGELOG_ROW_TO_STRING))
                .containsExactlyInAnyOrder("+I 1|30|130|binary|varbinary|mapKey:mapVal|multiset");
        assertThat(getResult(read, splits, binaryRow(2), 0, CHANGELOG_ROW_TO_STRING))
                .containsExactlyInAnyOrder(
                        "+I 2|30|230|binary|varbinary|mapKey:mapVal|multiset",
                        "+I 2|40|241|binary|varbinary|mapKey:mapVal|multiset");

        write.write(rowData(1, 20, 121L));
        write.write(rowData(1, 30, 131L));
        write.write(rowData(2, 30, 231L));
        commit.commit(3, write.prepareCommit(true, 3));

        write.write(rowDataWithKind(RowKind.DELETE, 1, 20, 121L));
        write.write(rowData(1, 30, 132L));
        write.write(rowData(1, 40, 141L));
        write.write(rowDataWithKind(RowKind.DELETE, 2, 20, 220L));
        write.write(rowData(2, 20, 221L));
        write.write(rowDataWithKind(RowKind.DELETE, 2, 20, 221L));
        write.write(rowData(2, 40, 242L));
        write.compact(binaryRow(1), 0, true);
        write.compact(binaryRow(2), 0, true);
        commit.commit(4, write.prepareCommit(true, 4));

        splits = toSplits(table.newSnapshotSplitReader().withKind(ScanKind.CHANGELOG).splits());
        assertThat(getResult(read, splits, binaryRow(1), 0, CHANGELOG_ROW_TO_STRING))
                .containsExactlyInAnyOrder(
                        "-D 1|20|120|binary|varbinary|mapKey:mapVal|multiset",
                        "-U 1|30|130|binary|varbinary|mapKey:mapVal|multiset",
                        "+U 1|30|132|binary|varbinary|mapKey:mapVal|multiset",
                        "+I 1|40|141|binary|varbinary|mapKey:mapVal|multiset");
        assertThat(getResult(read, splits, binaryRow(2), 0, CHANGELOG_ROW_TO_STRING))
                .containsExactlyInAnyOrder(
                        "-D 2|20|220|binary|varbinary|mapKey:mapVal|multiset",
                        "-U 2|30|230|binary|varbinary|mapKey:mapVal|multiset",
                        "+U 2|30|231|binary|varbinary|mapKey:mapVal|multiset",
                        "-U 2|40|241|binary|varbinary|mapKey:mapVal|multiset",
                        "+U 2|40|242|binary|varbinary|mapKey:mapVal|multiset");
    }

    @Test
    public void testStreamingChangelogCompatibility02() throws Exception {
        // already contains 2 commits
        CompatibilityTestUtils.unzip("compatibility/table-changelog-0.2.zip", tablePath.getPath());
        FileStoreTable table =
                createFileStoreTable(
                        conf -> conf.set(CoreOptions.CHANGELOG_PRODUCER, ChangelogProducer.INPUT),
                        COMPATIBILITY_ROW_TYPE);

        List<List<List<String>>> expected =
                Arrays.asList(
                        // first changelog snapshot
                        Arrays.asList(
                                // partition 1
                                Arrays.asList(
                                        "+I 1|10|100|binary|varbinary",
                                        "+I 1|20|200|binary|varbinary",
                                        "-D 1|10|100|binary|varbinary",
                                        "+I 1|10|101|binary|varbinary",
                                        "-U 1|10|101|binary|varbinary",
                                        "+U 1|10|102|binary|varbinary"),
                                // partition 2
                                Collections.singletonList("+I 2|10|300|binary|varbinary")),
                        // second changelog snapshot
                        Arrays.asList(
                                // partition 1
                                Collections.singletonList("-D 1|20|200|binary|varbinary"),
                                // partition 2
                                Arrays.asList(
                                        "-U 2|10|300|binary|varbinary",
                                        "+U 2|10|301|binary|varbinary",
                                        "+I 2|20|400|binary|varbinary")),
                        // third changelog snapshot
                        Arrays.asList(
                                // partition 1
                                Arrays.asList(
                                        "-U 1|10|102|binary|varbinary",
                                        "+U 1|10|103|binary|varbinary",
                                        "+I 1|20|201|binary|varbinary"),
                                // partition 2
                                Collections.singletonList("-D 2|10|301|binary|varbinary")));

        StreamDataTableScan scan =
                table.newStreamScan()
                        .withStartingScanner(new FullStartingScanner())
                        .withFollowUpScanner(new InputChangelogFollowUpScanner());
        scan.restore(1L);

        Function<Integer, Void> assertNextSnapshot =
                i -> {
                    TableScan.Plan plan = scan.plan();
                    assertThat(plan).isNotNull();

                    List<Split> splits = plan.splits();
                    TableRead read = table.newRead();
                    for (int j = 0; j < 2; j++) {
                        try {
                            assertThat(
                                            getResult(
                                                    read,
                                                    splits,
                                                    binaryRow(j + 1),
                                                    0,
                                                    COMPATIBILITY_CHANGELOG_ROW_TO_STRING))
                                    .isEqualTo(expected.get(i).get(j));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }

                    return null;
                };

        for (int i = 0; i < 2; i++) {
            assertNextSnapshot.apply(i);
        }

        // no more changelog
        assertThat(scan.plan()).isNull();

        // write another commit
        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);
        write.write(rowDataWithKind(RowKind.UPDATE_BEFORE, 1, 10, 102L));
        write.write(rowDataWithKind(RowKind.UPDATE_AFTER, 1, 10, 103L));
        write.write(rowDataWithKind(RowKind.INSERT, 1, 20, 201L));
        write.write(rowDataWithKind(RowKind.DELETE, 2, 10, 301L));
        commit.commit(2, write.prepareCommit(true, 2));
        write.close();

        assertNextSnapshot.apply(2);

        // no more changelog
        assertThat(scan.plan()).isNull();
    }

    private void writeData() throws Exception {
        FileStoreTable table = createFileStoreTable();
        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);

        write.write(rowData(1, 10, 100L));
        write.write(rowData(2, 20, 200L));
        write.write(rowData(1, 11, 101L));
        commit.commit(0, write.prepareCommit(true, 0));

        write.write(rowData(1, 10, 1000L));
        write.write(rowData(2, 21, 201L));
        write.write(rowData(2, 21, 2001L));
        commit.commit(1, write.prepareCommit(true, 1));

        write.write(rowData(1, 11, 1001L));
        write.write(rowData(2, 21, 20001L));
        write.write(rowData(2, 22, 202L));
        write.write(rowDataWithKind(RowKind.DELETE, 1, 11, 1001L));
        write.write(rowDataWithKind(RowKind.DELETE, 2, 20, 200L));
        commit.commit(2, write.prepareCommit(true, 2));

        write.close();
    }

    @Override
    @Test
    public void testReadFilter() throws Exception {
        FileStoreTable table = createFileStoreTable();
        if (table.options().fileFormat().getFormatIdentifier().equals("parquet")) {
            // TODO support parquet reader filter push down
            return;
        }

        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);

        write.write(rowData(1, 10, 100L));
        write.write(rowData(1, 20, 200L));
        commit.commit(0, write.prepareCommit(true, 0));

        write.write(rowData(1, 30, 300L));
        write.write(rowData(1, 40, 400L));
        commit.commit(1, write.prepareCommit(true, 1));

        write.write(rowData(1, 50, 500L));
        write.write(rowData(1, 60, 600L));
        commit.commit(2, write.prepareCommit(true, 2));

        PredicateBuilder builder = new PredicateBuilder(ROW_TYPE);
        List<Split> splits = toSplits(table.newSnapshotSplitReader().splits());

        // push down key filter a = 30
        TableRead read = table.newRead().withFilter(builder.equal(1, 30));
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_ROW_TO_STRING))
                .hasSameElementsAs(
                        Arrays.asList(
                                "1|30|300|binary|varbinary|mapKey:mapVal|multiset",
                                "1|40|400|binary|varbinary|mapKey:mapVal|multiset"));

        // push down value filter b = 300L
        read = table.newRead().withFilter(builder.equal(2, 300L));
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_ROW_TO_STRING))
                .hasSameElementsAs(
                        Arrays.asList(
                                "1|30|300|binary|varbinary|mapKey:mapVal|multiset",
                                "1|40|400|binary|varbinary|mapKey:mapVal|multiset"));

        // push down both key filter and value filter
        read =
                table.newRead()
                        .withFilter(
                                PredicateBuilder.or(builder.equal(1, 10), builder.equal(2, 300L)));
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_ROW_TO_STRING))
                .hasSameElementsAs(
                        Arrays.asList(
                                "1|10|100|binary|varbinary|mapKey:mapVal|multiset",
                                "1|20|200|binary|varbinary|mapKey:mapVal|multiset",
                                "1|30|300|binary|varbinary|mapKey:mapVal|multiset",
                                "1|40|400|binary|varbinary|mapKey:mapVal|multiset"));

        // update pk 60, 10
        write.write(rowData(1, 60, 500L));
        write.write(rowData(1, 10, 10L));
        commit.commit(3, write.prepareCommit(true, 3));

        write.close();

        // cannot push down value filter b = 600L
        splits = toSplits(table.newSnapshotSplitReader().splits());
        read = table.newRead().withFilter(builder.equal(2, 600L));
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_ROW_TO_STRING))
                .hasSameElementsAs(
                        Arrays.asList(
                                "1|10|10|binary|varbinary|mapKey:mapVal|multiset",
                                "1|20|200|binary|varbinary|mapKey:mapVal|multiset",
                                "1|30|300|binary|varbinary|mapKey:mapVal|multiset",
                                "1|40|400|binary|varbinary|mapKey:mapVal|multiset",
                                "1|50|500|binary|varbinary|mapKey:mapVal|multiset",
                                "1|60|500|binary|varbinary|mapKey:mapVal|multiset"));
    }

    @Test
    public void testPartialUpdateIgnoreDelete() throws Exception {
        FileStoreTable table =
                createFileStoreTable(
                        conf -> {
                            conf.set(
                                    CoreOptions.MERGE_ENGINE,
                                    CoreOptions.MergeEngine.PARTIAL_UPDATE);
                            conf.set(CoreOptions.PARTIAL_UPDATE_IGNORE_DELETE, true);
                        });
        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);
        write.write(rowData(1, 10, 200L));
        write.write(rowDataWithKind(RowKind.DELETE, 1, 10, 100L));
        write.write(rowDataWithKind(RowKind.UPDATE_BEFORE, 1, 10, 100L));
        write.write(rowDataWithKind(RowKind.DELETE, 1, 20, 400L));
        commit.commit(0, write.prepareCommit(true, 0));
        write.close();

        List<Split> splits = toSplits(table.newSnapshotSplitReader().splits());
        TableRead read = table.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_ROW_TO_STRING))
                .isEqualTo(
                        Collections.singletonList(
                                "1|10|200|binary|varbinary|mapKey:mapVal|multiset"));
    }

    @Test
    public void testSlowCommit() throws Exception {
        FileStoreTable table = createFileStoreTable();
        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);

        write.write(rowData(1, 10, 100L));
        write.write(rowData(1, 20, 200L));
        List<CommitMessage> committables0 = write.prepareCommit(false, 0);

        write.write(rowData(2, 10, 300L));
        List<CommitMessage> committables1 = write.prepareCommit(false, 1);

        write.write(rowData(1, 20, 201L));
        List<CommitMessage> committables2 = write.prepareCommit(true, 2);

        commit.commit(0, committables0);
        commit.commit(1, committables1);
        commit.commit(2, committables2);

        write.close();

        List<Split> splits = toSplits(table.newSnapshotSplitReader().splits());
        TableRead read = table.newRead();
        assertThat(getResult(read, splits, binaryRow(1), 0, BATCH_ROW_TO_STRING))
                .isEqualTo(
                        Arrays.asList(
                                "1|10|100|binary|varbinary|mapKey:mapVal|multiset",
                                "1|20|201|binary|varbinary|mapKey:mapVal|multiset"));
        assertThat(getResult(read, splits, binaryRow(2), 0, BATCH_ROW_TO_STRING))
                .isEqualTo(
                        Collections.singletonList(
                                "2|10|300|binary|varbinary|mapKey:mapVal|multiset"));
    }

    @Test
    public void testIncrementalSplitOverwrite() throws Exception {
        FileStoreTable table = createFileStoreTable();
        StreamTableWrite write = table.newWrite(commitUser);
        InnerTableCommit commit = table.newCommit(commitUser);

        write.write(rowData(1, 10, 100L));
        write.write(rowData(1, 20, 200L));
        commit.commit(0, write.prepareCommit(true, 0));

        SnapshotSplitReader snapshotSplitReader =
                table.newSnapshotSplitReader().withKind(ScanKind.DELTA);
        List<DataSplit> splits0 = snapshotSplitReader.splits();
        assertThat(splits0).hasSize(1);
        assertThat(splits0.get(0).files()).hasSize(1);

        write.write(rowData(1, 10, 1000L));
        write.write(rowData(1, 20, 2000L));
        Map<String, String> overwritePartition = new HashMap<>();
        overwritePartition.put("pt", "1");
        commit.withOverwrite(overwritePartition);
        commit.commit(1, write.prepareCommit(true, 1));

        List<DataSplit> splits1 = snapshotSplitReader.splits();
        assertThat(splits1).hasSize(1);
        assertThat(splits1.get(0).files()).hasSize(1);
        assertThat(splits1.get(0).files().get(0).fileName())
                .isNotEqualTo(splits0.get(0).files().get(0).fileName());
    }

    @Test
    public void testAuditLog() throws Exception {
        FileStoreTable table =
                createFileStoreTable(
                        conf -> conf.set(CoreOptions.CHANGELOG_PRODUCER, ChangelogProducer.INPUT));
        StreamTableWrite write = table.newWrite(commitUser);
        StreamTableCommit commit = table.newCommit(commitUser);

        // first commit
        write.write(rowDataWithKind(RowKind.INSERT, 1, 10, 100L));
        write.write(rowDataWithKind(RowKind.INSERT, 2, 20, 200L));
        commit.commit(0, write.prepareCommit(true, 0));

        // second commit
        write.write(rowDataWithKind(RowKind.UPDATE_AFTER, 1, 30, 300L));
        commit.commit(1, write.prepareCommit(true, 1));

        write.close();
        commit.close();

        AuditLogTable auditLogTable = new AuditLogTable(table);
        Function<InternalRow, String> rowDataToString =
                row ->
                        rowDataToString(
                                row,
                                DataTypes.ROW(
                                        DataTypes.STRING(),
                                        DataTypes.INT(),
                                        DataTypes.INT(),
                                        DataTypes.BIGINT()));
        PredicateBuilder predicateBuilder = new PredicateBuilder(auditLogTable.rowType());

        // Read all
        SnapshotSplitReader snapshotSplitReader = auditLogTable.newSnapshotSplitReader();
        TableRead read = auditLogTable.newRead();
        List<String> result =
                getResult(read, toSplits(snapshotSplitReader.splits()), rowDataToString);
        assertThat(result)
                .containsExactlyInAnyOrder(
                        "+I[+I, 2, 20, 200]", "+I[+U, 1, 30, 300]", "+I[+I, 1, 10, 100]");

        // Read by filter row kind (No effect)
        Predicate rowKindEqual = predicateBuilder.equal(0, BinaryString.fromString("+I"));
        snapshotSplitReader = auditLogTable.newSnapshotSplitReader().withFilter(rowKindEqual);
        read = auditLogTable.newRead().withFilter(rowKindEqual);
        result = getResult(read, toSplits(snapshotSplitReader.splits()), rowDataToString);
        assertThat(result)
                .containsExactlyInAnyOrder(
                        "+I[+I, 2, 20, 200]", "+I[+U, 1, 30, 300]", "+I[+I, 1, 10, 100]");

        // Read by filter
        snapshotSplitReader =
                auditLogTable.newSnapshotSplitReader().withFilter(predicateBuilder.equal(2, 10));
        read = auditLogTable.newRead().withFilter(predicateBuilder.equal(2, 10));
        result = getResult(read, toSplits(snapshotSplitReader.splits()), rowDataToString);
        assertThat(result).containsExactlyInAnyOrder("+I[+I, 1, 10, 100]");

        // Read by projection
        snapshotSplitReader = auditLogTable.newSnapshotSplitReader();
        read = auditLogTable.newRead().withProjection(new int[] {2, 0, 1});
        Function<InternalRow, String> projectToString1 =
                row ->
                        rowDataToString(
                                row,
                                DataTypes.ROW(
                                        DataTypes.INT(), DataTypes.STRING(), DataTypes.INT()));
        result = getResult(read, toSplits(snapshotSplitReader.splits()), projectToString1);
        assertThat(result)
                .containsExactlyInAnyOrder("+I[20, +I, 2]", "+I[30, +U, 1]", "+I[10, +I, 1]");

        // Read by projection without row kind
        snapshotSplitReader = auditLogTable.newSnapshotSplitReader();
        read = auditLogTable.newRead().withProjection(new int[] {2, 1});
        Function<InternalRow, String> projectToString2 =
                row -> rowDataToString(row, DataTypes.ROW(DataTypes.INT(), DataTypes.INT()));
        result = getResult(read, toSplits(snapshotSplitReader.splits()), projectToString2);
        assertThat(result).containsExactlyInAnyOrder("+I[20, 2]", "+I[30, 1]", "+I[10, 1]");
    }

    @Test
    public void testAggMergeFunc() throws Exception {
        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.INT(), DataTypes.INT(), DataTypes.INT(), DataTypes.INT()
                        },
                        new String[] {"pt", "a", "b", "c"});
        FileStoreTable table =
                createFileStoreTable(
                        options -> {
                            options.set("merge-engine", "aggregation");
                            options.set("fields.b.aggregate-function", "sum");
                            options.set("fields.c.aggregate-function", "max");
                            options.set("fields.c.ignore-retract", "true");
                        },
                        rowType);
        Function<InternalRow, String> rowToString = row -> rowDataToString(row, rowType);
        SnapshotSplitReader snapshotSplitReader = table.newSnapshotSplitReader();
        TableRead read = table.newRead();
        StreamTableWrite write = table.newWrite("");
        StreamTableCommit commit = table.newCommit("");

        // 1. inserts
        write.write(GenericRow.of(1, 1, 3, 3));
        write.write(GenericRow.of(1, 1, 1, 1));
        write.write(GenericRow.of(1, 1, 2, 2));
        commit.commit(0, write.prepareCommit(true, 0));

        List<String> result = getResult(read, toSplits(snapshotSplitReader.splits()), rowToString);
        assertThat(result).containsExactlyInAnyOrder("+I[1, 1, 6, 3]");

        // 2. Retracts
        write.write(GenericRow.ofKind(RowKind.UPDATE_BEFORE, 1, 1, 1, 1));
        write.write(GenericRow.ofKind(RowKind.UPDATE_BEFORE, 1, 1, 2, 2));
        write.write(GenericRow.ofKind(RowKind.DELETE, 1, 1, 1, 1));
        commit.commit(1, write.prepareCommit(true, 1));

        result = getResult(read, toSplits(snapshotSplitReader.splits()), rowToString);
        assertThat(result).containsExactlyInAnyOrder("+I[1, 1, 2, 3]");
    }

    @Test
    public void testAggMergeFuncNotAllowRetract() throws Exception {
        RowType rowType =
                RowType.of(
                        new DataType[] {
                            DataTypes.INT(), DataTypes.INT(), DataTypes.INT(), DataTypes.INT()
                        },
                        new String[] {"pt", "a", "b", "c"});
        FileStoreTable table =
                createFileStoreTable(
                        options -> {
                            options.set("merge-engine", "aggregation");
                            options.set("fields.b.aggregate-function", "sum");
                            options.set("fields.c.aggregate-function", "max");
                        },
                        rowType);
        StreamTableWrite write = table.newWrite("");
        write.write(GenericRow.of(1, 1, 3, 3));
        write.write(GenericRow.ofKind(RowKind.UPDATE_BEFORE, 1, 1, 3, 3));
        assertThatThrownBy(() -> write.prepareCommit(true, 0))
                .hasMessageContaining(
                        "Aggregate function 'max' does not support retraction,"
                                + " If you allow this function to ignore retraction messages,"
                                + " you can configure 'fields.${field_name}.ignore-retract'='true'");
    }

    @Override
    protected FileStoreTable createFileStoreTable(Consumer<Options> configure) throws Exception {
        return createFileStoreTable(configure, ROW_TYPE);
    }

    private FileStoreTable createFileStoreTable(Consumer<Options> configure, RowType rowType)
            throws Exception {
        Options conf = new Options();
        conf.set(CoreOptions.PATH, tablePath.toString());
        conf.set(CoreOptions.WRITE_MODE, WriteMode.CHANGE_LOG);
        configure.accept(conf);
        TableSchema tableSchema =
                SchemaUtils.forceCommit(
                        new SchemaManager(LocalFileIO.create(), tablePath),
                        new Schema(
                                rowType.getFields(),
                                Collections.singletonList("pt"),
                                Arrays.asList("pt", "a"),
                                conf.toMap(),
                                ""));
        return new ChangelogWithKeyFileStoreTable(LocalFileIO.create(), tablePath, tableSchema);
    }
}
