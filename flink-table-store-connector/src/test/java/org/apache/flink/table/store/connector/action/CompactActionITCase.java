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

package org.apache.flink.table.store.connector.action;

import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.store.CoreOptions;
import org.apache.flink.table.store.data.BinaryString;
import org.apache.flink.table.store.file.Snapshot;
import org.apache.flink.table.store.table.FileStoreTable;
import org.apache.flink.table.store.table.source.DataSplit;
import org.apache.flink.table.store.table.source.DataTableScan;
import org.apache.flink.table.store.table.source.snapshot.ContinuousDataFileSnapshotEnumerator;
import org.apache.flink.table.store.table.source.snapshot.SnapshotEnumerator;
import org.apache.flink.table.store.types.DataType;
import org.apache.flink.table.store.types.DataTypes;
import org.apache.flink.table.store.types.RowType;
import org.apache.flink.table.store.utils.CommonTestUtils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;

/** IT cases for {@link CompactAction}. */
public class CompactActionITCase extends ActionITCaseBase {

    private static final DataType[] FIELD_TYPES =
            new DataType[] {DataTypes.INT(), DataTypes.INT(), DataTypes.INT(), DataTypes.STRING()};

    private static final RowType ROW_TYPE =
            RowType.of(FIELD_TYPES, new String[] {"k", "v", "hh", "dt"});

    @Test
    @Timeout(60)
    public void testBatchCompact() throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put(CoreOptions.WRITE_ONLY.key(), "true");

        FileStoreTable table =
                createFileStoreTable(
                        ROW_TYPE,
                        Arrays.asList("dt", "hh"),
                        Arrays.asList("dt", "hh", "k"),
                        options);
        snapshotManager = table.snapshotManager();
        write = table.newWrite(commitUser);
        commit = table.newCommit(commitUser);

        writeData(
                rowData(1, 100, 15, BinaryString.fromString("20221208")),
                rowData(1, 100, 16, BinaryString.fromString("20221208")),
                rowData(1, 100, 15, BinaryString.fromString("20221209")));

        writeData(
                rowData(2, 100, 15, BinaryString.fromString("20221208")),
                rowData(2, 100, 16, BinaryString.fromString("20221208")),
                rowData(2, 100, 15, BinaryString.fromString("20221209")));

        Snapshot snapshot = snapshotManager.snapshot(snapshotManager.latestSnapshotId());
        Assertions.assertEquals(2, snapshot.id());
        Assertions.assertEquals(Snapshot.CommitKind.APPEND, snapshot.commitKind());

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setRuntimeMode(RuntimeExecutionMode.BATCH);
        env.setParallelism(ThreadLocalRandom.current().nextInt(2) + 1);
        new CompactAction(tablePath).withPartitions(getSpecifiedPartitions()).build(env);
        env.execute();

        snapshot = snapshotManager.snapshot(snapshotManager.latestSnapshotId());
        Assertions.assertEquals(3, snapshot.id());
        Assertions.assertEquals(Snapshot.CommitKind.COMPACT, snapshot.commitKind());

        DataTableScan.DataFilePlan plan = table.newScan().plan();
        Assertions.assertEquals(3, plan.splits().size());
        for (DataSplit split : plan.splits) {
            if (split.partition().getInt(1) == 15) {
                // compacted
                Assertions.assertEquals(1, split.files().size());
            } else {
                // not compacted
                Assertions.assertEquals(2, split.files().size());
            }
        }
    }

    @Test
    public void testStreamingCompact() throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put(CoreOptions.CHANGELOG_PRODUCER.key(), "full-compaction");
        options.put(CoreOptions.CHANGELOG_PRODUCER_FULL_COMPACTION_TRIGGER_INTERVAL.key(), "1s");
        options.put(CoreOptions.CONTINUOUS_DISCOVERY_INTERVAL.key(), "1s");
        options.put(CoreOptions.WRITE_ONLY.key(), "true");
        // test that dedicated compact job will expire snapshots
        options.put(CoreOptions.SNAPSHOT_NUM_RETAINED_MIN.key(), "3");
        options.put(CoreOptions.SNAPSHOT_NUM_RETAINED_MAX.key(), "3");

        FileStoreTable table =
                createFileStoreTable(
                        ROW_TYPE,
                        Arrays.asList("dt", "hh"),
                        Arrays.asList("dt", "hh", "k"),
                        options);
        snapshotManager = table.snapshotManager();
        write = table.newWrite(commitUser);
        commit = table.newCommit(commitUser);

        // base records
        writeData(
                rowData(1, 100, 15, BinaryString.fromString("20221208")),
                rowData(1, 100, 16, BinaryString.fromString("20221208")),
                rowData(1, 100, 15, BinaryString.fromString("20221209")));

        Snapshot snapshot = snapshotManager.snapshot(snapshotManager.latestSnapshotId());
        Assertions.assertEquals(1, snapshot.id());
        Assertions.assertEquals(Snapshot.CommitKind.APPEND, snapshot.commitKind());

        // no full compaction has happened, so plan should be empty
        SnapshotEnumerator snapshotEnumerator =
                ContinuousDataFileSnapshotEnumerator.create(table, table.newScan(), null);
        DataTableScan.DataFilePlan plan = snapshotEnumerator.enumerate();
        Assertions.assertEquals(1, (long) plan.snapshotId);
        Assertions.assertTrue(plan.splits().isEmpty());

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setRuntimeMode(RuntimeExecutionMode.STREAMING);
        env.getCheckpointConfig().setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);
        env.getCheckpointConfig().setCheckpointInterval(500);
        env.setParallelism(ThreadLocalRandom.current().nextInt(2) + 1);
        new CompactAction(tablePath).withPartitions(getSpecifiedPartitions()).build(env);
        JobClient client = env.executeAsync();

        // first full compaction
        validateResult(
                table,
                snapshotEnumerator,
                Arrays.asList("+I[1, 100, 15, 20221208]", "+I[1, 100, 15, 20221209]"),
                60_000);

        // incremental records
        writeData(
                rowData(1, 101, 15, BinaryString.fromString("20221208")),
                rowData(1, 101, 16, BinaryString.fromString("20221208")),
                rowData(1, 101, 15, BinaryString.fromString("20221209")));

        // second full compaction
        validateResult(
                table,
                snapshotEnumerator,
                Arrays.asList(
                        "+U[1, 101, 15, 20221208]",
                        "+U[1, 101, 15, 20221209]",
                        "-U[1, 100, 15, 20221208]",
                        "-U[1, 100, 15, 20221209]"),
                60_000);

        // assert dedicated compact job will expire snapshots
        CommonTestUtils.waitUtil(
                () ->
                        snapshotManager.latestSnapshotId() - 2
                                == snapshotManager.earliestSnapshotId(),
                Duration.ofSeconds(60_000),
                Duration.ofSeconds(100),
                String.format("Cannot validate snapshot expiration in %s milliseconds.", 60_000));

        client.cancel();
    }

    private List<Map<String, String>> getSpecifiedPartitions() {
        Map<String, String> partition1 = new HashMap<>();
        partition1.put("dt", "20221208");
        partition1.put("hh", "15");

        Map<String, String> partition2 = new HashMap<>();
        partition2.put("dt", "20221209");
        partition2.put("hh", "15");

        return Arrays.asList(partition1, partition2);
    }

    private void validateResult(
            FileStoreTable table,
            SnapshotEnumerator snapshotEnumerator,
            List<String> expected,
            long timeout)
            throws Exception {
        List<String> actual = new ArrayList<>();
        long start = System.currentTimeMillis();
        while (actual.size() != expected.size()) {
            DataTableScan.DataFilePlan plan = snapshotEnumerator.enumerate();
            if (plan != null) {
                actual.addAll(getResult(table.newRead(), plan.splits(), ROW_TYPE));
            }
            if (System.currentTimeMillis() - start > timeout) {
                break;
            }
        }
        if (actual.size() != expected.size()) {
            throw new TimeoutException(
                    String.format(
                            "Cannot collect %s records in %s milliseconds.",
                            expected.size(), timeout));
        }
        actual.sort(String::compareTo);
        Assertions.assertEquals(expected, actual);
    }
}
