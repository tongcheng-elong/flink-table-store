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

import org.apache.flink.table.store.data.BinaryString;
import org.apache.flink.table.store.file.Snapshot;
import org.apache.flink.table.store.table.FileStoreTable;
import org.apache.flink.table.store.table.source.DataTableScan;
import org.apache.flink.table.store.types.DataType;
import org.apache.flink.table.store.types.DataTypes;
import org.apache.flink.table.store.types.RowType;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** IT cases for {@link DropPartitionAction}. */
public class DropPartitionActionITCase extends ActionITCaseBase {

    private static final DataType[] FIELD_TYPES =
            new DataType[] {DataTypes.INT(), DataTypes.INT(), DataTypes.STRING(), DataTypes.INT()};

    private static final RowType ROW_TYPE =
            RowType.of(FIELD_TYPES, new String[] {"partKey0", "partKey1", "dt", "value"});

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testDropPartitionWithSinglePartitionKey(boolean hasPk) throws Exception {
        FileStoreTable table = prepareTable(hasPk);

        new DropPartitionAction(
                        warehouse,
                        database,
                        tableName,
                        Collections.singletonList(Collections.singletonMap("partKey0", "0")))
                .run();

        Snapshot snapshot = snapshotManager.snapshot(snapshotManager.latestSnapshotId());
        assertThat(snapshot.id()).isEqualTo(5);
        assertThat(snapshot.commitKind()).isEqualTo(Snapshot.CommitKind.OVERWRITE);

        DataTableScan.DataFilePlan plan = table.newScan().plan();
        assertThat(plan.splits().size()).isEqualTo(2);
        List<String> actual = getResult(table.newRead(), plan.splits(), ROW_TYPE);

        List<String> expected;
        if (hasPk) {
            expected =
                    Arrays.asList(
                            "+I[1, 0, 2023-01-17, 5]",
                            "+I[1, 1, 2023-01-18, 82]",
                            "+I[1, 1, 2023-01-19, 90]",
                            "+I[1, 1, 2023-01-20, 97]");
        } else {
            expected =
                    Arrays.asList(
                            "+I[1, 0, 2023-01-17, 2]",
                            "+I[1, 0, 2023-01-17, 3]",
                            "+I[1, 0, 2023-01-17, 5]",
                            "+I[1, 1, 2023-01-18, 82]",
                            "+I[1, 1, 2023-01-19, 90]",
                            "+I[1, 1, 2023-01-20, 97]");
        }

        assertThat(actual).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testDropPartitionWithMultiplePartitionKey(boolean hasPk) throws Exception {
        FileStoreTable table = prepareTable(hasPk);

        Map<String, String> partitions0 = new HashMap<>();
        partitions0.put("partKey0", "0");
        partitions0.put("partKey1", "1");

        Map<String, String> partitions1 = new HashMap<>();
        partitions1.put("partKey0", "1");
        partitions1.put("partKey1", "0");

        new DropPartitionAction(
                        warehouse, database, tableName, Arrays.asList(partitions0, partitions1))
                .run();

        Snapshot snapshot = snapshotManager.snapshot(snapshotManager.latestSnapshotId());
        assertThat(snapshot.id()).isEqualTo(5);
        assertThat(snapshot.commitKind()).isEqualTo(Snapshot.CommitKind.OVERWRITE);

        DataTableScan.DataFilePlan plan = table.newScan().plan();
        assertThat(plan.splits().size()).isEqualTo(2);
        List<String> actual = getResult(table.newRead(), plan.splits(), ROW_TYPE);

        List<String> expected;
        if (hasPk) {
            expected =
                    Arrays.asList(
                            "+I[0, 0, 2023-01-12, 102]",
                            "+I[0, 0, 2023-01-13, 103]",
                            "+I[1, 1, 2023-01-18, 82]",
                            "+I[1, 1, 2023-01-19, 90]",
                            "+I[1, 1, 2023-01-20, 97]");
        } else {
            expected =
                    Arrays.asList(
                            "+I[0, 0, 2023-01-12, 101]",
                            "+I[0, 0, 2023-01-12, 102]",
                            "+I[0, 0, 2023-01-13, 103]",
                            "+I[1, 1, 2023-01-18, 82]",
                            "+I[1, 1, 2023-01-19, 90]",
                            "+I[1, 1, 2023-01-20, 97]");
        }

        assertThat(actual).isEqualTo(expected);
    }

    private FileStoreTable prepareTable(boolean hasPk) throws Exception {
        FileStoreTable table =
                createFileStoreTable(
                        ROW_TYPE,
                        Arrays.asList("partKey0", "partKey1"),
                        hasPk
                                ? Arrays.asList("partKey0", "partKey1", "dt")
                                : Collections.emptyList(),
                        new HashMap<>());
        snapshotManager = table.snapshotManager();
        write = table.newWrite(commitUser);
        commit = table.newCommit(commitUser);

        // prepare data
        writeData(
                rowData(0, 0, BinaryString.fromString("2023-01-12"), 101),
                rowData(0, 0, BinaryString.fromString("2023-01-12"), 102),
                rowData(0, 0, BinaryString.fromString("2023-01-13"), 103));

        writeData(
                rowData(0, 1, BinaryString.fromString("2023-01-14"), 110),
                rowData(0, 1, BinaryString.fromString("2023-01-15"), 120),
                rowData(0, 1, BinaryString.fromString("2023-01-16"), 130));

        writeData(
                rowData(1, 0, BinaryString.fromString("2023-01-17"), 2),
                rowData(1, 0, BinaryString.fromString("2023-01-17"), 3),
                rowData(1, 0, BinaryString.fromString("2023-01-17"), 5));

        writeData(
                rowData(1, 1, BinaryString.fromString("2023-01-18"), 82),
                rowData(1, 1, BinaryString.fromString("2023-01-19"), 90),
                rowData(1, 1, BinaryString.fromString("2023-01-20"), 97));

        Snapshot snapshot = snapshotManager.snapshot(snapshotManager.latestSnapshotId());

        assertThat(snapshot.id()).isEqualTo(4);
        assertThat(snapshot.commitKind()).isEqualTo(Snapshot.CommitKind.APPEND);

        return table;
    }
}
