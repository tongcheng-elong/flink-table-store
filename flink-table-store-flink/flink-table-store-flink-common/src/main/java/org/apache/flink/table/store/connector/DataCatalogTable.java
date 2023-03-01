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

package org.apache.flink.table.store.connector;

import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.catalog.CatalogBaseTable;
import org.apache.flink.table.catalog.CatalogTable;
import org.apache.flink.table.catalog.CatalogTableImpl;
import org.apache.flink.table.store.table.FileStoreTable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A {@link CatalogTableImpl} to wrap {@link FileStoreTable}. */
public class DataCatalogTable extends CatalogTableImpl {

    private final FileStoreTable table;

    public DataCatalogTable(
            FileStoreTable table,
            TableSchema tableSchema,
            List<String> partitionKeys,
            Map<String, String> properties,
            String comment) {
        super(tableSchema, partitionKeys, properties, comment);
        this.table = table;
    }

    public FileStoreTable table() {
        return table;
    }

    @Override
    public CatalogBaseTable copy() {
        return new DataCatalogTable(
                table,
                getSchema().copy(),
                new ArrayList<>(getPartitionKeys()),
                new HashMap<>(getOptions()),
                getComment());
    }

    @Override
    public CatalogTable copy(Map<String, String> options) {
        return new DataCatalogTable(table, getSchema(), getPartitionKeys(), options, getComment());
    }
}
