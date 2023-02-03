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

package org.apache.flink.table.store.table;

import org.apache.flink.table.store.CoreOptions;
import org.apache.flink.table.store.file.WriteMode;
import org.apache.flink.table.store.file.io.DataFileMeta;
import org.apache.flink.table.store.file.predicate.Predicate;
import org.apache.flink.table.store.file.predicate.PredicateBuilder;
import org.apache.flink.table.store.file.schema.SchemaManager;
import org.apache.flink.table.store.file.schema.TableSchema;
import org.apache.flink.table.store.file.stats.BinaryTableStats;
import org.apache.flink.table.store.table.source.DataTableScan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

/** Tests for meta files in {@link ChangelogWithKeyFileStoreTable} with schema evolution. */
public class ChangelogWithKeyFileMetaFilterTest extends FileMetaFilterTestBase {

    @BeforeEach
    public void before() throws Exception {
        super.before();
        tableConfig.set(CoreOptions.WRITE_MODE, WriteMode.CHANGE_LOG);
    }

    @Test
    @Override
    public void testTableScan() throws Exception {
        writeAndCheckFileResult(
                schemas -> {
                    FileStoreTable table = createFileStoreTable(schemas);
                    DataTableScan.DataFilePlan plan = table.newScan().plan();
                    checkFilterRowCount(plan, 6L);
                    return plan.splits.stream()
                            .flatMap(s -> s.files().stream())
                            .collect(Collectors.toList());
                },
                (files, schemas) -> {
                    FileStoreTable table = createFileStoreTable(schemas);
                    DataTableScan.DataFilePlan plan = table.newScan().plan();
                    checkFilterRowCount(plan, 12L);

                    /**
                     * TODO ChangelogWithKeyFileStoreTable doesn't support value predicate and can't
                     * get value stats. The test for filtering the primary key and partition already
                     * exists.
                     */
                },
                getPrimaryKeyNames(),
                tableConfig,
                this::createFileStoreTable);
    }

    @Test
    @Override
    public void testTableScanFilterExistFields() throws Exception {
        writeAndCheckFileResult(
                schemas -> {
                    FileStoreTable table = createFileStoreTable(schemas);
                    // results of field "b" in [14, 19] in SCHEMA_0_FIELDS, "b" is renamed to "d" in
                    // SCHEMA_1_FIELDS
                    Predicate predicate =
                            new PredicateBuilder(table.schema().logicalRowType())
                                    .between(2, 14, 19);
                    DataTableScan.DataFilePlan plan = table.newScan().withFilter(predicate).plan();
                    checkFilterRowCount(plan, 6L);
                    return plan.splits.stream()
                            .flatMap(s -> s.files().stream())
                            .collect(Collectors.toList());
                },
                (files, schemas) -> {
                    FileStoreTable table = createFileStoreTable(schemas);
                    PredicateBuilder builder =
                            new PredicateBuilder(table.schema().logicalRowType());
                    // results of field "d" in [14, 19] in SCHEMA_1_FIELDS
                    Predicate predicate = builder.between(1, 14, 19);
                    DataTableScan.DataFilePlan plan = table.newScan().withFilter(predicate).plan();
                    checkFilterRowCount(plan, 12L);

                    /**
                     * TODO ChangelogWithKeyFileStoreTable doesn't support value predicate and can't
                     * get value stats. The test for filtering the primary key and partition already
                     * exists.
                     */
                },
                getPrimaryKeyNames(),
                tableConfig,
                this::createFileStoreTable);
    }

    @Test
    @Override
    public void testTableScanFilterNewFields() throws Exception {
        writeAndCheckFileResult(
                schemas -> {
                    FileStoreTable table = createFileStoreTable(schemas);
                    DataTableScan.DataFilePlan plan = table.newScan().plan();
                    checkFilterRowCount(plan, 6L);
                    return plan.splits.stream()
                            .flatMap(s -> s.files().stream())
                            .collect(Collectors.toList());
                },
                (files, schemas) -> {
                    FileStoreTable table = createFileStoreTable(schemas);
                    PredicateBuilder builder =
                            new PredicateBuilder(table.schema().logicalRowType());
                    // results of field "a" in (1120, -] in SCHEMA_1_FIELDS, "a" is not existed in
                    // SCHEMA_0_FIELDS
                    Predicate predicate = builder.greaterThan(3, 1120);
                    DataTableScan.DataFilePlan plan = table.newScan().withFilter(predicate).plan();
                    checkFilterRowCount(plan, 12L);

                    /**
                     * TODO ChangelogWithKeyFileStoreTable doesn't support value predicate and can't
                     * get value stats. The test for filtering the primary key and partition already
                     * exists.
                     */
                },
                getPrimaryKeyNames(),
                tableConfig,
                this::createFileStoreTable);
    }

    @Override
    protected FileStoreTable createFileStoreTable(Map<Long, TableSchema> tableSchemas) {
        SchemaManager schemaManager = new TestingSchemaManager(tablePath, tableSchemas);
        return new ChangelogWithKeyFileStoreTable(fileIO, tablePath, schemaManager.latest().get()) {
            @Override
            protected SchemaManager schemaManager() {
                return schemaManager;
            }
        };
    }

    @Override
    protected BinaryTableStats getTableValueStats(DataFileMeta fileMeta) {
        throw new UnsupportedOperationException();
    }
}
