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

package org.apache.flink.table.store.connector.lookup;

import org.apache.flink.table.store.data.InternalRow;
import org.apache.flink.table.store.data.serializer.Serializer;
import org.apache.flink.table.store.io.DataInputDeserializer;
import org.apache.flink.table.store.io.DataOutputSerializer;

import org.apache.flink.shaded.guava30.com.google.common.cache.Cache;
import org.apache.flink.shaded.guava30.com.google.common.cache.CacheBuilder;

import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.WriteOptions;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.Arrays;

/** Rocksdb state for key value. */
public abstract class RocksDBState<CacheV> {

    protected final RocksDB db;

    protected final WriteOptions writeOptions;

    protected final ColumnFamilyHandle columnFamily;

    protected final Serializer<InternalRow> keySerializer;

    protected final Serializer<InternalRow> valueSerializer;

    protected final DataOutputSerializer keyOutView;

    protected final DataInputDeserializer valueInputView;

    protected final DataOutputSerializer valueOutputView;

    protected final Cache<ByteArray, CacheV> cache;

    public RocksDBState(
            RocksDB db,
            ColumnFamilyHandle columnFamily,
            Serializer<InternalRow> keySerializer,
            Serializer<InternalRow> valueSerializer,
            long lruCacheSize) {
        this.db = db;
        this.columnFamily = columnFamily;
        this.keySerializer = keySerializer;
        this.valueSerializer = valueSerializer;
        this.keyOutView = new DataOutputSerializer(32);
        this.valueInputView = new DataInputDeserializer();
        this.valueOutputView = new DataOutputSerializer(32);
        this.writeOptions = new WriteOptions().setDisableWAL(true);
        this.cache = CacheBuilder.newBuilder().maximumSize(lruCacheSize).build();
    }

    protected byte[] serializeKey(InternalRow key) throws IOException {
        keyOutView.clear();
        keySerializer.serialize(key, keyOutView);
        return keyOutView.getCopyOfBuffer();
    }

    protected ByteArray wrap(byte[] bytes) {
        return new ByteArray(bytes);
    }

    protected Reference ref(byte[] bytes) {
        return new Reference(bytes);
    }

    /** A class wraps byte[] to implement equals and hashCode. */
    protected static class ByteArray {

        protected final byte[] bytes;

        protected ByteArray(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ByteArray byteArray = (ByteArray) o;
            return Arrays.equals(bytes, byteArray.bytes);
        }
    }

    /** A class wraps byte[] to indicate contain or not contain. */
    protected static class Reference {

        @Nullable protected final byte[] bytes;

        protected Reference(@Nullable byte[] bytes) {
            this.bytes = bytes;
        }

        public boolean isPresent() {
            return bytes != null;
        }
    }
}
