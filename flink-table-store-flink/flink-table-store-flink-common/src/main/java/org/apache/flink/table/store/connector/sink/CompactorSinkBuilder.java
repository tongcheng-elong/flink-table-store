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

package org.apache.flink.table.store.connector.sink;

import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSink;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.transformations.PartitionTransformation;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.store.file.operation.Lock;
import org.apache.flink.table.store.table.FileStoreTable;
import org.apache.flink.table.store.table.system.BucketsTable;

/** Builder for {@link CompactorSink}. */
public class CompactorSinkBuilder {

    private final FileStoreTable table;

    private DataStream<RowData> input;
    private Lock.Factory lockFactory = Lock.emptyFactory();

    public CompactorSinkBuilder(FileStoreTable table) {
        this.table = table;
    }

    public CompactorSinkBuilder withInput(DataStream<RowData> input) {
        this.input = input;
        return this;
    }

    public CompactorSinkBuilder withLockFactory(Lock.Factory lockFactory) {
        this.lockFactory = lockFactory;
        return this;
    }

    public DataStreamSink<?> build() {
        OffsetRowDataHashStreamPartitioner partitioner =
                new OffsetRowDataHashStreamPartitioner(
                        BucketsTable.partitionWithBucketRowType(
                                table.schema().logicalPartitionType()),
                        1);
        PartitionTransformation<RowData> partitioned =
                new PartitionTransformation<>(input.getTransformation(), partitioner);

        StreamExecutionEnvironment env = input.getExecutionEnvironment();
        CompactorSink sink = new CompactorSink(table, lockFactory);
        return sink.sinkFrom(new DataStream<>(env, partitioned));
    }
}
