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

package org.apache.flink.table.store.table.system;

import org.apache.flink.table.store.data.BinaryString;
import org.apache.flink.table.store.data.GenericRow;
import org.apache.flink.table.store.data.InternalRow;
import org.apache.flink.table.store.file.predicate.Predicate;
import org.apache.flink.table.store.file.schema.SchemaManager;
import org.apache.flink.table.store.file.schema.TableSchema;
import org.apache.flink.table.store.file.utils.IteratorRecordReader;
import org.apache.flink.table.store.file.utils.JsonSerdeUtil;
import org.apache.flink.table.store.file.utils.SerializationUtils;
import org.apache.flink.table.store.fs.FileIO;
import org.apache.flink.table.store.fs.Path;
import org.apache.flink.table.store.reader.RecordReader;
import org.apache.flink.table.store.table.ReadonlyTable;
import org.apache.flink.table.store.table.Table;
import org.apache.flink.table.store.table.source.InnerTableRead;
import org.apache.flink.table.store.table.source.InnerTableScan;
import org.apache.flink.table.store.table.source.Split;
import org.apache.flink.table.store.table.source.TableRead;
import org.apache.flink.table.store.types.BigIntType;
import org.apache.flink.table.store.types.DataField;
import org.apache.flink.table.store.types.RowType;
import org.apache.flink.table.store.utils.ProjectedRow;

import org.apache.flink.shaded.guava30.com.google.common.collect.Iterators;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

import static org.apache.flink.table.store.file.catalog.Catalog.SYSTEM_TABLE_SPLITTER;

/** A {@link Table} for showing schemas of table. */
public class SchemasTable implements ReadonlyTable {

    private static final long serialVersionUID = 1L;

    public static final String SCHEMAS = "schemas";

    public static final RowType TABLE_TYPE =
            new RowType(
                    Arrays.asList(
                            new DataField(0, "schema_id", new BigIntType(false)),
                            new DataField(1, "fields", SerializationUtils.newStringType(false)),
                            new DataField(
                                    2, "partition_keys", SerializationUtils.newStringType(false)),
                            new DataField(
                                    3, "primary_keys", SerializationUtils.newStringType(false)),
                            new DataField(4, "options", SerializationUtils.newStringType(false)),
                            new DataField(5, "comment", SerializationUtils.newStringType(true))));

    private final FileIO fileIO;
    private final Path location;

    public SchemasTable(FileIO fileIO, Path location) {
        this.fileIO = fileIO;
        this.location = location;
    }

    @Override
    public String name() {
        return location.getName() + SYSTEM_TABLE_SPLITTER + SCHEMAS;
    }

    @Override
    public RowType rowType() {
        return TABLE_TYPE;
    }

    @Override
    public InnerTableScan newScan() {
        return new SchemasScan();
    }

    @Override
    public InnerTableRead newRead() {
        return new SchemasRead(fileIO);
    }

    @Override
    public Table copy(Map<String, String> dynamicOptions) {
        return new SchemasTable(fileIO, location);
    }

    private class SchemasScan implements InnerTableScan {

        @Override
        public InnerTableScan withFilter(Predicate predicate) {
            return this;
        }

        @Override
        public Plan plan() {
            return () -> Collections.singletonList(new SchemasSplit(fileIO, location));
        }
    }

    /** {@link Split} implementation for {@link SchemasTable}. */
    private static class SchemasSplit implements Split {

        private static final long serialVersionUID = 1L;

        private final FileIO fileIO;
        private final Path location;

        private SchemasSplit(FileIO fileIO, Path location) {
            this.fileIO = fileIO;
            this.location = location;
        }

        @Override
        public long rowCount() {
            return new SchemaManager(fileIO, location).listAllIds().size();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            SchemasSplit that = (SchemasSplit) o;
            return Objects.equals(location, that.location);
        }

        @Override
        public int hashCode() {
            return Objects.hash(location);
        }
    }

    /** {@link TableRead} implementation for {@link SchemasTable}. */
    private static class SchemasRead implements InnerTableRead {

        private final FileIO fileIO;
        private int[][] projection;

        public SchemasRead(FileIO fileIO) {
            this.fileIO = fileIO;
        }

        @Override
        public InnerTableRead withFilter(Predicate predicate) {
            return this;
        }

        @Override
        public InnerTableRead withProjection(int[][] projection) {
            this.projection = projection;
            return this;
        }

        @Override
        public RecordReader<InternalRow> createReader(Split split) throws IOException {
            if (!(split instanceof SchemasSplit)) {
                throw new IllegalArgumentException("Unsupported split: " + split.getClass());
            }
            Path location = ((SchemasSplit) split).location;
            Iterator<TableSchema> schemas =
                    new SchemaManager(fileIO, location).listAll().iterator();
            Iterator<InternalRow> rows = Iterators.transform(schemas, this::toRow);
            if (projection != null) {
                rows =
                        Iterators.transform(
                                rows, row -> ProjectedRow.from(projection).replaceRow(row));
            }
            return new IteratorRecordReader<>(rows);
        }

        private InternalRow toRow(TableSchema schema) {
            return GenericRow.of(
                    schema.id(),
                    toJson(schema.fields()),
                    toJson(schema.partitionKeys()),
                    toJson(schema.primaryKeys()),
                    toJson(schema.options()),
                    BinaryString.fromString(schema.comment()));
        }

        private BinaryString toJson(Object obj) {
            return BinaryString.fromString(JsonSerdeUtil.toFlatJson(obj));
        }
    }
}
