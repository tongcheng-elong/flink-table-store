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

import org.apache.flink.table.store.connector.RocksDBOptions;
import org.apache.flink.table.store.data.InternalRow;
import org.apache.flink.table.store.data.serializer.Serializer;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Factory to create state. */
public class RocksDBStateFactory implements Closeable {

    private RocksDB db;

    private final ColumnFamilyOptions columnFamilyOptions;

    public RocksDBStateFactory(String path, org.apache.flink.table.store.options.Options conf)
            throws IOException {
        DBOptions dbOptions =
                RocksDBOptions.createDBOptions(
                        new DBOptions()
                                .setUseFsync(false)
                                .setStatsDumpPeriodSec(0)
                                .setCreateIfMissing(true),
                        conf);
        this.columnFamilyOptions =
                RocksDBOptions.createColumnOptions(new ColumnFamilyOptions(), conf);

        try {
            this.db = RocksDB.open(new Options(dbOptions, columnFamilyOptions), path);
        } catch (RocksDBException e) {
            throw new IOException("Error while opening RocksDB instance.", e);
        }
    }

    public RocksDBValueState valueState(
            String name,
            Serializer<InternalRow> keySerializer,
            Serializer<InternalRow> valueSerializer,
            long lruCacheSize)
            throws IOException {
        return new RocksDBValueState(
                db, createColumnFamily(name), keySerializer, valueSerializer, lruCacheSize);
    }

    public RocksDBSetState setState(
            String name,
            Serializer<InternalRow> keySerializer,
            Serializer<InternalRow> valueSerializer,
            long lruCacheSize)
            throws IOException {
        return new RocksDBSetState(
                db, createColumnFamily(name), keySerializer, valueSerializer, lruCacheSize);
    }

    private ColumnFamilyHandle createColumnFamily(String name) throws IOException {
        try {
            return db.createColumnFamily(
                    new ColumnFamilyDescriptor(
                            name.getBytes(StandardCharsets.UTF_8), columnFamilyOptions));
        } catch (RocksDBException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        if (db != null) {
            db.close();
            db = null;
        }
    }
}
