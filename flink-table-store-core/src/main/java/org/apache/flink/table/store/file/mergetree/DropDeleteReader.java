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

package org.apache.flink.table.store.file.mergetree;

import org.apache.flink.table.store.file.KeyValue;
import org.apache.flink.table.store.reader.RecordReader;
import org.apache.flink.table.store.types.RowKind;

import javax.annotation.Nullable;

import java.io.IOException;

/**
 * A {@link RecordReader} which drops {@link KeyValue} of {@link RowKind#DELETE} kind from the
 * wrapped reader.
 */
public class DropDeleteReader implements RecordReader<KeyValue> {

    private final RecordReader<KeyValue> reader;

    public DropDeleteReader(RecordReader<KeyValue> reader) {
        this.reader = reader;
    }

    @Nullable
    @Override
    public RecordIterator<KeyValue> readBatch() throws IOException {
        RecordIterator<KeyValue> batch = reader.readBatch();

        if (batch == null) {
            return null;
        }

        return new RecordIterator<KeyValue>() {
            @Override
            public KeyValue next() throws IOException {
                while (true) {
                    KeyValue kv = batch.next();
                    if (kv == null) {
                        return null;
                    }

                    if (kv.valueKind() == RowKind.INSERT
                            || kv.valueKind() == RowKind.UPDATE_AFTER) {
                        return kv;
                    }
                }
            }

            @Override
            public void releaseBatch() {
                batch.releaseBatch();
            }
        };
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
