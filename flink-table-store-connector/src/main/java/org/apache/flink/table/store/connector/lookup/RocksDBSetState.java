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

import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.table.store.data.InternalRow;

import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.apache.flink.util.Preconditions.checkArgument;

/** Rocksdb state for key -> Set values. */
public class RocksDBSetState extends RocksDBState<List<byte[]>> {

    private static final byte[] EMPTY = new byte[0];

    public RocksDBSetState(
            RocksDB db,
            ColumnFamilyHandle columnFamily,
            TypeSerializer<InternalRow> keySerializer,
            TypeSerializer<InternalRow> valueSerializer,
            long lruCacheSize) {
        super(db, columnFamily, keySerializer, valueSerializer, lruCacheSize);
    }

    public List<InternalRow> get(InternalRow key) throws IOException {
        ByteArray keyBytes = wrap(serializeKey(key));
        List<byte[]> valueBytes = cache.getIfPresent(keyBytes);
        if (valueBytes == null) {
            valueBytes = new ArrayList<>();
            try (RocksIterator iterator = db.newIterator(columnFamily)) {
                iterator.seek(keyBytes.bytes);

                while (iterator.isValid() && startWithKeyPrefix(keyBytes.bytes, iterator.key())) {
                    byte[] rawKeyBytes = iterator.key();
                    byte[] value =
                            Arrays.copyOfRange(
                                    rawKeyBytes, keyBytes.bytes.length, rawKeyBytes.length);
                    valueBytes.add(value);
                    iterator.next();
                }
            }
            cache.put(keyBytes, valueBytes);
        }

        List<InternalRow> values = new ArrayList<>(valueBytes.size());
        for (byte[] value : valueBytes) {
            valueInputView.setBuffer(value);
            values.add(valueSerializer.deserialize(valueInputView));
        }
        return values;
    }

    public void retract(InternalRow key, InternalRow value) throws IOException {
        try {
            byte[] bytes = invalidKeyAndGetKVBytes(key, value);
            if (db.get(columnFamily, bytes) != null) {
                db.delete(columnFamily, writeOptions, bytes);
            }
        } catch (RocksDBException e) {
            throw new IOException(e);
        }
    }

    public void add(InternalRow key, InternalRow value) throws IOException {
        try {
            byte[] bytes = invalidKeyAndGetKVBytes(key, value);
            db.put(columnFamily, writeOptions, bytes, EMPTY);
        } catch (RocksDBException e) {
            throw new IOException(e);
        }
    }

    private byte[] invalidKeyAndGetKVBytes(InternalRow key, InternalRow value) throws IOException {
        checkArgument(value != null);

        keyOutView.clear();
        keySerializer.serialize(key, keyOutView);

        // it is hard to maintain cache, invalidate the key.
        cache.invalidate(wrap(keyOutView.getCopyOfBuffer()));

        valueSerializer.serialize(value, keyOutView);
        return keyOutView.getCopyOfBuffer();
    }

    private boolean startWithKeyPrefix(byte[] keyPrefixBytes, byte[] rawKeyBytes) {
        if (rawKeyBytes.length < keyPrefixBytes.length) {
            return false;
        }

        for (int i = keyPrefixBytes.length; --i >= 0; ) {
            if (rawKeyBytes[i] != keyPrefixBytes[i]) {
                return false;
            }
        }

        return true;
    }
}
