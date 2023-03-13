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

package org.apache.flink.table.store.benchmark;

import org.apache.flink.table.store.CoreOptions;
import org.apache.flink.table.store.catalog.CatalogContext;
import org.apache.flink.table.store.data.BinaryString;
import org.apache.flink.table.store.data.GenericRow;
import org.apache.flink.table.store.data.InternalRow;
import org.apache.flink.table.store.file.catalog.Catalog;
import org.apache.flink.table.store.file.catalog.CatalogFactory;
import org.apache.flink.table.store.file.catalog.Identifier;
import org.apache.flink.table.store.file.schema.Schema;
import org.apache.flink.table.store.options.CatalogOptions;
import org.apache.flink.table.store.options.Options;
import org.apache.flink.table.store.table.Table;
import org.apache.flink.table.store.types.DataField;
import org.apache.flink.table.store.types.DataTypes;
import org.apache.flink.table.store.types.IntType;

import org.apache.commons.math3.random.RandomDataGenerator;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.util.Collections.singletonList;

/** Base class for table benchmark. */
public class TableBenchmark {

    private static final int VALUE_COUNT = 20;

    @TempDir java.nio.file.Path tempFile;

    private final RandomDataGenerator random = new RandomDataGenerator();

    protected Table createTable(Options tableOptions) throws Exception {
        Options catalogOptions = new Options();
        catalogOptions.set(CatalogOptions.WAREHOUSE, tempFile.toUri().toString());
        Catalog catalog = CatalogFactory.createCatalog(CatalogContext.create(catalogOptions));
        String database = "default";
        catalog.createDatabase(database, true);

        List<DataField> fields = new ArrayList<>();
        fields.add(new DataField(0, "k", new IntType()));
        for (int i = 1; i <= VALUE_COUNT; i++) {
            fields.add(new DataField(i, "f" + i, DataTypes.STRING()));
        }
        tableOptions.set(CoreOptions.SNAPSHOT_NUM_RETAINED_MAX, 10);
        Schema schema =
                new Schema(
                        fields,
                        Collections.emptyList(),
                        singletonList("k"),
                        tableOptions.toMap(),
                        "");
        Identifier identifier = Identifier.create(database, "T");
        catalog.createTable(identifier, schema, false);
        return catalog.getTable(identifier);
    }

    protected InternalRow newRandomRow() {
        GenericRow row = new GenericRow(1 + VALUE_COUNT);
        row.setField(0, random.nextInt(0, Integer.MAX_VALUE));
        for (int i = 1; i <= VALUE_COUNT; i++) {
            row.setField(i, BinaryString.fromString(random.nextHexString(10)));
        }
        return row;
    }
}
