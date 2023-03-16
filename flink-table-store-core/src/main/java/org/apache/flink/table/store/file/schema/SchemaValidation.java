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

import org.apache.flink.table.store.CoreOptions;
import org.apache.flink.table.store.file.WriteMode;
import org.apache.flink.table.store.options.ConfigOption;
import org.apache.flink.table.store.types.ArrayType;
import org.apache.flink.table.store.types.DataField;
import org.apache.flink.table.store.types.DataType;
import org.apache.flink.table.store.types.MapType;
import org.apache.flink.table.store.types.MultisetType;
import org.apache.flink.table.store.types.RowType;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.apache.flink.table.store.CoreOptions.SCAN_MODE;
import static org.apache.flink.table.store.CoreOptions.SCAN_SNAPSHOT_ID;
import static org.apache.flink.table.store.CoreOptions.SCAN_TIMESTAMP_MILLIS;
import static org.apache.flink.table.store.CoreOptions.SNAPSHOT_NUM_RETAINED_MAX;
import static org.apache.flink.table.store.CoreOptions.SNAPSHOT_NUM_RETAINED_MIN;
import static org.apache.flink.table.store.file.WriteMode.APPEND_ONLY;
import static org.apache.flink.table.store.file.schema.SystemColumns.KEY_FIELD_PREFIX;
import static org.apache.flink.table.store.file.schema.SystemColumns.SYSTEM_FIELD_NAMES;
import static org.apache.flink.table.store.utils.Preconditions.checkArgument;
import static org.apache.flink.table.store.utils.Preconditions.checkState;

/** Validation utils for {@link TableSchema}. */
public class SchemaValidation {

    public static final List<Class<? extends DataType>> PRIMARY_KEY_UNSUPPORTED_LOGICAL_TYPES =
            Arrays.asList(MapType.class, ArrayType.class, RowType.class, MultisetType.class);

    /**
     * Validate the {@link TableSchema} and {@link CoreOptions}.
     *
     * <p>TODO validate all items in schema and all keys in options.
     *
     * @param schema the schema to be validated
     */
    public static void validateTableSchema(TableSchema schema) {
        validatePrimaryKeysType(schema.fields(), schema.primaryKeys());

        CoreOptions options = new CoreOptions(schema.options());
        if (options.startupMode() == CoreOptions.StartupMode.FROM_TIMESTAMP) {
            checkOptionExistInMode(
                    options, SCAN_TIMESTAMP_MILLIS, CoreOptions.StartupMode.FROM_TIMESTAMP);
            checkOptionsConflict(options, SCAN_SNAPSHOT_ID, SCAN_TIMESTAMP_MILLIS);
        } else if (options.startupMode() == CoreOptions.StartupMode.FROM_SNAPSHOT) {
            checkOptionExistInMode(
                    options, SCAN_SNAPSHOT_ID, CoreOptions.StartupMode.FROM_SNAPSHOT);
            checkOptionsConflict(options, SCAN_TIMESTAMP_MILLIS, SCAN_SNAPSHOT_ID);
        } else {
            checkOptionNotExistInMode(options, SCAN_TIMESTAMP_MILLIS, options.startupMode());
            checkOptionNotExistInMode(options, SCAN_SNAPSHOT_ID, options.startupMode());
        }

        checkArgument(
                options.snapshotNumRetainMin() > 0,
                SNAPSHOT_NUM_RETAINED_MIN.key() + " should be at least 1");
        checkArgument(
                options.snapshotNumRetainMin() <= options.snapshotNumRetainMax(),
                SNAPSHOT_NUM_RETAINED_MIN.key()
                        + " should not be larger than "
                        + SNAPSHOT_NUM_RETAINED_MAX.key());

        // Only changelog tables with primary keys support full compaction or lookup changelog
        // producer
        if (options.writeMode() == WriteMode.CHANGE_LOG) {
            switch (options.changelogProducer()) {
                case FULL_COMPACTION:
                case LOOKUP:
                    if (schema.primaryKeys().isEmpty()) {
                        throw new UnsupportedOperationException(
                                "Changelog table with full compaction must have primary keys");
                    }
                    break;
                default:
            }
        }

        // Check column names in schema
        schema.fieldNames()
                .forEach(
                        f -> {
                            checkState(
                                    !SYSTEM_FIELD_NAMES.contains(f),
                                    String.format(
                                            "Field name[%s] in schema cannot be exist in [%s]",
                                            f, SYSTEM_FIELD_NAMES));
                            checkState(
                                    !f.startsWith(KEY_FIELD_PREFIX),
                                    String.format(
                                            "Field name[%s] in schema cannot start with [%s]",
                                            f, KEY_FIELD_PREFIX));
                        });

        // Cannot define any primary key in an append-only table.
        if (!schema.primaryKeys().isEmpty() && Objects.equals(APPEND_ONLY, options.writeMode())) {
            throw new RuntimeException(
                    "Cannot define any primary key in an append-only table. Set 'write-mode'='change-log' if "
                            + "still want to keep the primary key definition.");
        }

        if (schema.primaryKeys().isEmpty() && options.streamingReadOverwrite()) {
            throw new RuntimeException(
                    "Doesn't support streaming read the changes from overwrite when the primary keys are not defined.");
        }

        if (schema.options().containsKey(CoreOptions.PARTITION_EXPIRATION_TIME.key())) {
            if (schema.partitionKeys().isEmpty()) {
                throw new IllegalArgumentException(
                        "Can not set 'partition.expiration-time' for non-partitioned table.");
            }
        }

        Optional<String> sequenceField = options.sequenceField();
        sequenceField.ifPresent(
                field ->
                        checkArgument(
                                schema.fieldNames().contains(field),
                                "Nonexistent sequence field: '%s'",
                                field));
    }

    private static void validatePrimaryKeysType(List<DataField> fields, List<String> primaryKeys) {
        if (!primaryKeys.isEmpty()) {
            Map<String, DataField> rowFields = new HashMap<>();
            for (DataField rowField : fields) {
                rowFields.put(rowField.name(), rowField);
            }
            for (String primaryKeyName : primaryKeys) {
                DataField rowField = rowFields.get(primaryKeyName);
                DataType dataType = rowField.type();
                if (PRIMARY_KEY_UNSUPPORTED_LOGICAL_TYPES.stream()
                        .anyMatch(c -> c.isInstance(dataType))) {
                    throw new UnsupportedOperationException(
                            String.format(
                                    "The type %s in primary key field %s is unsupported",
                                    dataType.getClass().getSimpleName(), primaryKeyName));
                }
            }
        }
    }

    private static void checkOptionExistInMode(
            CoreOptions options, ConfigOption<?> option, CoreOptions.StartupMode startupMode) {
        checkArgument(
                options.toConfiguration().contains(option),
                String.format(
                        "%s can not be null when you use %s for %s",
                        option.key(), startupMode, SCAN_MODE.key()));
    }

    private static void checkOptionNotExistInMode(
            CoreOptions options, ConfigOption<?> option, CoreOptions.StartupMode startupMode) {
        checkArgument(
                !options.toConfiguration().contains(option),
                String.format(
                        "%s must be null when you use %s for %s",
                        option.key(), startupMode, SCAN_MODE.key()));
    }

    private static void checkOptionsConflict(
            CoreOptions options, ConfigOption<?> illegalOption, ConfigOption<?> legalOption) {
        checkArgument(
                !options.toConfiguration().contains(illegalOption),
                String.format(
                        "%s must be null when you set %s", illegalOption.key(), legalOption.key()));
    }
}
