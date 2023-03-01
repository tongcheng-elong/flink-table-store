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

import org.apache.flink.api.connector.source.SourceReader;
import org.apache.flink.api.connector.source.SourceReaderContext;
import org.apache.flink.connector.base.source.reader.SingleThreadMultiplexSourceReaderBase;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.store.table.source.TableRead;

import javax.annotation.Nullable;

import java.util.Map;

/** A {@link SourceReader} that read records from {@link FileStoreSourceSplit}. */
public final class FileStoreSourceReader<T>
        extends SingleThreadMultiplexSourceReaderBase<
                T, RowData, FileStoreSourceSplit, FileStoreSourceSplitState> {

    public FileStoreSourceReader(
            RecordsFunction<T> recordsFunction,
            SourceReaderContext readerContext,
            TableRead tableRead,
            @Nullable Long limit) {
        super(
                () -> new FileStoreSourceSplitReader<>(recordsFunction, tableRead, limit),
                recordsFunction,
                readerContext.getConfiguration(),
                readerContext);
    }

    @Override
    public void start() {
        // we request a split only if we did not get splits during the checkpoint restore
        if (getNumberOfCurrentlyAssignedSplits() == 0) {
            context.sendSplitRequest();
        }
    }

    @Override
    protected void onSplitFinished(Map<String, FileStoreSourceSplitState> finishedSplitIds) {
        context.sendSplitRequest();
    }

    @Override
    protected FileStoreSourceSplitState initializedState(FileStoreSourceSplit split) {
        return new FileStoreSourceSplitState(split);
    }

    @Override
    protected FileStoreSourceSplit toSplitType(
            String splitId, FileStoreSourceSplitState splitState) {
        return splitState.toSourceSplit();
    }
}
