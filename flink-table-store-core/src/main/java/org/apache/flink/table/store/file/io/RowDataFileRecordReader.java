/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.store.file.io;

import org.apache.flink.table.store.data.InternalRow;
import org.apache.flink.table.store.file.casting.CastFieldGetter;
import org.apache.flink.table.store.file.utils.FileUtils;
import org.apache.flink.table.store.format.FormatReaderFactory;
import org.apache.flink.table.store.fs.FileIO;
import org.apache.flink.table.store.fs.Path;
import org.apache.flink.table.store.reader.RecordReader;

import javax.annotation.Nullable;

import java.io.IOException;

/** Reads {@link InternalRow} from data files. */
public class RowDataFileRecordReader implements RecordReader<InternalRow> {

    private final RecordReader<InternalRow> reader;
    @Nullable private final int[] indexMapping;
    @Nullable private final CastFieldGetter[] castMapping;

    public RowDataFileRecordReader(
            FileIO fileIO,
            Path path,
            FormatReaderFactory readerFactory,
            @Nullable int[] indexMapping,
            @Nullable CastFieldGetter[] castMapping)
            throws IOException {
        this.reader = FileUtils.createFormatReader(fileIO, readerFactory, path);
        this.indexMapping = indexMapping;
        this.castMapping = castMapping;
    }

    @Nullable
    @Override
    public RecordReader.RecordIterator<InternalRow> readBatch() throws IOException {
        RecordIterator<InternalRow> iterator = reader.readBatch();
        return iterator == null
                ? null
                : new RowDataFileRecordIterator(iterator, indexMapping, castMapping);
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }

    private static class RowDataFileRecordIterator extends AbstractFileRecordIterator<InternalRow> {

        private final RecordIterator<InternalRow> iterator;

        private RowDataFileRecordIterator(
                RecordIterator<InternalRow> iterator,
                @Nullable int[] indexMapping,
                @Nullable CastFieldGetter[] castMapping) {
            super(indexMapping, castMapping);
            this.iterator = iterator;
        }

        @Override
        public InternalRow next() throws IOException {
            InternalRow result = iterator.next();

            return result == null ? null : mappingRowData(result);
        }

        @Override
        public void releaseBatch() {
            iterator.releaseBatch();
        }
    }
}
