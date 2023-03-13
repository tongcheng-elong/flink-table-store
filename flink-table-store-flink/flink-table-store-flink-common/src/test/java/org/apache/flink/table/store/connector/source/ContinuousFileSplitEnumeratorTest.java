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

import org.apache.flink.api.connector.source.SplitEnumeratorContext;
import org.apache.flink.connector.testutils.source.reader.TestingSplitEnumeratorContext;
import org.apache.flink.table.store.file.io.DataFileMeta;
import org.apache.flink.table.store.table.source.DataSplit;
import org.apache.flink.table.store.table.source.DataTableScan.DataFilePlan;
import org.apache.flink.table.store.table.source.snapshot.SnapshotEnumerator;
import org.apache.flink.table.store.table.source.snapshot.SnapshotEnumerator.Result;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

import static org.apache.flink.connector.testutils.source.reader.TestingSplitEnumeratorContext.SplitAssignmentState;
import static org.apache.flink.table.store.file.mergetree.compact.MergeTreeCompactManagerTest.row;
import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the {@link ContinuousFileSplitEnumerator}. */
public class ContinuousFileSplitEnumeratorTest {

    @Test
    public void testSplitAllocationIsOrdered() throws Exception {
        final TestingSplitEnumeratorContext<FileStoreSourceSplit> context =
                new TestingSplitEnumeratorContext<>(1);
        context.registerReader(0, "test-host");

        List<FileStoreSourceSplit> initialSplits = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            initialSplits.add(createSnapshotSplit(i, 0, Collections.emptyList()));
        }
        List<FileStoreSourceSplit> expectedSplits = new ArrayList<>(initialSplits);
        final ContinuousFileSplitEnumerator enumerator =
                new Builder()
                        .setSplitEnumeratorContext(context)
                        .setInitialSplits(initialSplits)
                        .setDiscoveryInterval(3)
                        .build();

        // The first time split is allocated, split1 and split2 should be allocated
        enumerator.handleSplitRequest(0, "test-host");
        enumerator.handleSplitRequest(0, "test-host");
        Map<Integer, SplitAssignmentState<FileStoreSourceSplit>> assignments =
                context.getSplitAssignments();
        // Only subtask-0 is allocated.
        assertThat(assignments).containsOnlyKeys(0);
        List<FileStoreSourceSplit> assignedSplits = assignments.get(0).getAssignedSplits();
        assertThat(assignedSplits).hasSameElementsAs(expectedSplits.subList(0, 2));

        // split1 and split2 is added back
        enumerator.addSplitsBack(assignedSplits, 0);
        context.getSplitAssignments().clear();
        assertThat(context.getSplitAssignments()).isEmpty();

        // The split is allocated for the second time, and split1 is allocated first
        enumerator.handleSplitRequest(0, "test-host");
        enumerator.handleSplitRequest(0, "test-host");
        assignments = context.getSplitAssignments();
        // Only subtask-0 is allocated.
        assertThat(assignments).containsOnlyKeys(0);
        assignedSplits = assignments.get(0).getAssignedSplits();
        assertThat(assignedSplits).hasSameElementsAs(expectedSplits.subList(0, 2));

        // continuing to allocate split
        context.getSplitAssignments().clear();
        enumerator.handleSplitRequest(0, "test-host");
        enumerator.handleSplitRequest(0, "test-host");
        assignments = context.getSplitAssignments();
        // Only subtask-0 is allocated.
        assertThat(assignments).containsOnlyKeys(0);
        assignedSplits = assignments.get(0).getAssignedSplits();
        assertThat(assignedSplits).hasSameElementsAs(expectedSplits.subList(2, 4));
    }

    @Test
    public void testSplitAllocationIsFair() throws Exception {
        final TestingSplitEnumeratorContext<FileStoreSourceSplit> context =
                new TestingSplitEnumeratorContext<>(1);
        context.registerReader(0, "test-host");

        List<FileStoreSourceSplit> initialSplits = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            initialSplits.add(createSnapshotSplit(i, 0, Collections.emptyList()));
            initialSplits.add(createSnapshotSplit(i, 1, Collections.emptyList()));
        }

        List<FileStoreSourceSplit> expectedSplits = new ArrayList<>(initialSplits);

        final ContinuousFileSplitEnumerator enumerator =
                new Builder()
                        .setSplitEnumeratorContext(context)
                        .setInitialSplits(initialSplits)
                        .setDiscoveryInterval(3)
                        .build();

        // each time a split is allocated from bucket-0 and bucket-1
        enumerator.handleSplitRequest(0, "test-host");
        Map<Integer, SplitAssignmentState<FileStoreSourceSplit>> assignments =
                context.getSplitAssignments();
        // Only subtask-0 is allocated.
        assertThat(assignments).containsOnlyKeys(0);
        List<FileStoreSourceSplit> assignedSplits = assignments.get(0).getAssignedSplits();
        assertThat(assignedSplits).hasSameElementsAs(expectedSplits.subList(0, 2));

        // clear assignments
        context.getSplitAssignments().clear();
        assertThat(context.getSplitAssignments()).isEmpty();

        // continuing to allocate the rest splits
        enumerator.handleSplitRequest(0, "test-host");
        assignments = context.getSplitAssignments();
        // Only subtask-0 is allocated.
        assertThat(assignments).containsOnlyKeys(0);
        assignedSplits = assignments.get(0).getAssignedSplits();
        assertThat(assignedSplits).hasSameElementsAs(expectedSplits.subList(2, 4));
    }

    @Test
    public void testSnapshotEnumerator() {
        final TestingSplitEnumeratorContext<FileStoreSourceSplit> context =
                new TestingSplitEnumeratorContext<>(2);
        context.registerReader(0, "test-host");
        context.registerReader(1, "test-host");

        Queue<Result> results = new LinkedBlockingQueue<>();
        SnapshotEnumerator snapshotEnumerator = results::poll;
        ContinuousFileSplitEnumerator enumerator =
                new Builder()
                        .setSplitEnumeratorContext(context)
                        .setInitialSplits(Collections.emptyList())
                        .setDiscoveryInterval(1)
                        .setSnapshotEnumerator(snapshotEnumerator)
                        .build();
        enumerator.start();

        long snapshot = 0;
        List<DataSplit> splits = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            splits.add(createDataSplit(snapshot, i, Collections.emptyList()));
        }
        results.add(snapshotEnumerator.planResult(new DataFilePlan(snapshot, splits)));
        context.triggerAllActions();

        // assign to task 0
        enumerator.handleSplitRequest(0, "test-host");
        Map<Integer, SplitAssignmentState<FileStoreSourceSplit>> assignments =
                context.getSplitAssignments();
        assertThat(assignments).containsOnlyKeys(0);
        assertThat(toDataSplits(assignments.get(0).getAssignedSplits()))
                .containsExactly(splits.get(0), splits.get(2));

        // no more splits task 0
        enumerator.handleSplitRequest(0, "test-host");
        results.add(snapshotEnumerator.finishedResult());
        context.triggerAllActions();
        assignments = context.getSplitAssignments();
        assertThat(assignments).containsOnlyKeys(0);
        assertThat(assignments.get(0).hasReceivedNoMoreSplitsSignal()).isTrue();
        assignments.clear();

        // assign to task 1
        enumerator.handleSplitRequest(1, "test-host");
        assignments = context.getSplitAssignments();
        assertThat(assignments).containsOnlyKeys(1);
        assertThat(toDataSplits(assignments.get(1).getAssignedSplits()))
                .containsExactly(splits.get(1), splits.get(3));

        // no more splits task 1
        enumerator.handleSplitRequest(1, "test-host");
        assignments = context.getSplitAssignments();
        assertThat(assignments).containsOnlyKeys(1);
        assertThat(assignments.get(1).hasReceivedNoMoreSplitsSignal()).isTrue();
    }

    private static List<DataSplit> toDataSplits(List<FileStoreSourceSplit> splits) {
        return splits.stream()
                .map(FileStoreSourceSplit::split)
                .map(split -> (DataSplit) split)
                .collect(Collectors.toList());
    }

    public static FileStoreSourceSplit createSnapshotSplit(
            int snapshotId, int bucket, List<DataFileMeta> files) {
        return new FileStoreSourceSplit(
                UUID.randomUUID().toString(),
                new DataSplit(snapshotId, row(1), bucket, files, true),
                0);
    }

    private static DataSplit createDataSplit(
            long snapshotId, int bucket, List<DataFileMeta> files) {
        return new DataSplit(snapshotId, row(1), bucket, files, true);
    }

    private static class Builder {
        private SplitEnumeratorContext<FileStoreSourceSplit> context;
        private Collection<FileStoreSourceSplit> initialSplits = Collections.emptyList();
        private Long nextSnapshotId;
        private long discoveryInterval = Long.MAX_VALUE;
        private SnapshotEnumerator snapshotEnumerator;

        public Builder setSplitEnumeratorContext(
                SplitEnumeratorContext<FileStoreSourceSplit> context) {
            this.context = context;
            return this;
        }

        public Builder setInitialSplits(Collection<FileStoreSourceSplit> initialSplits) {
            this.initialSplits = initialSplits;
            return this;
        }

        public Builder setNextSnapshotId(Long nextSnapshotId) {
            this.nextSnapshotId = nextSnapshotId;
            return this;
        }

        public Builder setDiscoveryInterval(long discoveryInterval) {
            this.discoveryInterval = discoveryInterval;
            return this;
        }

        public Builder setSnapshotEnumerator(SnapshotEnumerator snapshotEnumerator) {
            this.snapshotEnumerator = snapshotEnumerator;
            return this;
        }

        public ContinuousFileSplitEnumerator build() {
            return new ContinuousFileSplitEnumerator(
                    context, initialSplits, nextSnapshotId, discoveryInterval, snapshotEnumerator);
        }
    }
}
