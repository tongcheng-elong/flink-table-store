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

package org.apache.flink.table.store.file.schema;

import org.apache.flink.table.store.annotation.Experimental;
import org.apache.flink.table.store.types.DataField;
import org.apache.flink.table.store.types.DataType;
import org.apache.flink.table.store.types.RowType;
import org.apache.flink.table.store.utils.Preconditions;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Schema of a table.
 *
 * @since 0.4.0
 */
@Experimental
public class Schema {

    private final List<DataField> fields;

    private final List<String> partitionKeys;

    private final List<String> primaryKeys;

    private final Map<String, String> options;

    private final String comment;

    public Schema(
            List<DataField> fields,
            List<String> partitionKeys,
            List<String> primaryKeys,
            Map<String, String> options,
            String comment) {
        this.fields = normalizeFields(fields, primaryKeys, partitionKeys);
        this.partitionKeys = partitionKeys;
        this.primaryKeys = primaryKeys;
        this.options = new HashMap<>(options);
        this.comment = comment;
    }

    public RowType rowType() {
        return new RowType(false, fields);
    }

    public List<DataField> fields() {
        return fields;
    }

    public List<String> partitionKeys() {
        return partitionKeys;
    }

    public List<String> primaryKeys() {
        return primaryKeys;
    }

    public Map<String, String> options() {
        return options;
    }

    public String comment() {
        return comment;
    }

    private static List<DataField> normalizeFields(
            List<DataField> fields, List<String> primaryKeys, List<String> partitionKeys) {
        List<String> fieldNames = fields.stream().map(DataField::name).collect(Collectors.toList());
        Set<String> allFields = new HashSet<>(fieldNames);
        Preconditions.checkState(
                allFields.containsAll(partitionKeys),
                "Table column %s should include all partition fields %s",
                fieldNames,
                partitionKeys);

        if (primaryKeys.isEmpty()) {
            return fields;
        }
        Preconditions.checkState(
                allFields.containsAll(primaryKeys),
                "Table column %s should include all primary key constraint %s",
                fieldNames,
                primaryKeys);
        Set<String> pkSet = new HashSet<>(primaryKeys);
        Preconditions.checkState(
                pkSet.containsAll(partitionKeys),
                "Primary key constraint %s should include all partition fields %s",
                primaryKeys,
                partitionKeys);

        // primary key should not nullable
        List<DataField> newFields = new ArrayList<>();
        for (DataField field : fields) {
            if (pkSet.contains(field.name()) && field.type().isNullable()) {
                newFields.add(
                        new DataField(
                                field.id(),
                                field.name(),
                                field.type().copy(false),
                                field.description()));
            } else {
                newFields.add(field);
            }
        }
        return newFields;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Schema that = (Schema) o;
        return Objects.equals(fields, that.fields)
                && Objects.equals(partitionKeys, that.partitionKeys)
                && Objects.equals(primaryKeys, that.primaryKeys)
                && Objects.equals(options, that.options)
                && Objects.equals(comment, that.comment);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fields, partitionKeys, primaryKeys, options, comment);
    }

    @Override
    public String toString() {
        return "UpdateSchema{"
                + "fields="
                + fields
                + ", partitionKeys="
                + partitionKeys
                + ", primaryKeys="
                + primaryKeys
                + ", options="
                + options
                + ", comment="
                + comment
                + '}';
    }

    /** Builder for configuring and creating instances of {@link Schema}. */
    public static Schema.Builder newBuilder() {
        return new Builder();
    }

    /** A builder for constructing an immutable but still unresolved {@link Schema}. */
    public static final class Builder {

        private final List<DataField> columns = new ArrayList<>();

        private List<String> partitionKeys = new ArrayList<>();

        private List<String> primaryKeys = new ArrayList<>();

        private final Map<String, String> options = new HashMap<>();

        @Nullable private String comment;

        private int highestFieldId = -1;

        public int getHighestFieldId() {
            return highestFieldId;
        }

        /**
         * Declares a column that is appended to this schema.
         *
         * @param columnName column name
         * @param dataType data type of the column
         */
        public Builder column(String columnName, DataType dataType) {
            return column(columnName, dataType, null);
        }

        /**
         * Declares a column that is appended to this schema.
         *
         * @param columnName column name
         * @param dataType data type of the column
         * @param description description of the column
         */
        public Builder column(String columnName, DataType dataType, @Nullable String description) {
            Preconditions.checkNotNull(columnName, "Column name must not be null.");
            Preconditions.checkNotNull(dataType, "Data type must not be null.");
            columns.add(new DataField(++highestFieldId, columnName, dataType, description));
            return this;
        }

        /** Declares partition keys. */
        public Builder partitionKeys(String... columnNames) {
            return partitionKeys(Arrays.asList(columnNames));
        }

        /** Declares partition keys. */
        public Builder partitionKeys(List<String> columnNames) {
            this.partitionKeys = new ArrayList<>(columnNames);
            return this;
        }

        /**
         * Declares a primary key constraint for a set of given columns. Primary key uniquely
         * identify a row in a table. Neither of columns in a primary can be nullable.
         *
         * @param columnNames columns that form a unique primary key
         */
        public Builder primaryKey(String... columnNames) {
            return primaryKey(Arrays.asList(columnNames));
        }

        /**
         * Declares a primary key constraint for a set of given columns. Primary key uniquely
         * identify a row in a table. Neither of columns in a primary can be nullable.
         *
         * @param columnNames columns that form a unique primary key
         */
        public Builder primaryKey(List<String> columnNames) {
            this.primaryKeys = new ArrayList<>(columnNames);
            return this;
        }

        /** Declares options. */
        public Builder options(Map<String, String> options) {
            this.options.putAll(options);
            return this;
        }

        /** Declares an option. */
        public Builder option(String key, String value) {
            this.options.put(key, value);
            return this;
        }

        /** Declares table comment. */
        public Builder comment(String comment) {
            this.comment = comment;
            return this;
        }

        /** Returns an instance of an unresolved {@link Schema}. */
        public Schema build() {
            return new Schema(columns, partitionKeys, primaryKeys, options, comment);
        }
    }
}
