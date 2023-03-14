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

package org.apache.flink.table.store.connector.action;

import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.api.java.utils.MultipleParameterTool;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.conversion.DataStructureConverter;
import org.apache.flink.table.data.conversion.DataStructureConverters;
import org.apache.flink.table.store.connector.LogicalTypeConversion;
import org.apache.flink.table.store.table.FileStoreTable;
import org.apache.flink.table.store.types.DataField;
import org.apache.flink.table.store.types.DataType;
import org.apache.flink.table.types.utils.TypeConversions;
import org.apache.flink.types.RowKind;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.flink.table.store.connector.action.Action.getTablePath;
import static org.apache.flink.table.store.connector.action.Action.parseKeyValues;

/**
 * Flink action for 'MERGE INTO', which references the syntax as follows (we use 'upsert' semantics
 * instead of 'update'):
 *
 * <pre><code>
 *  MERGE INTO target-table
 *  USING source-table | source-expr AS source-alias
 *  ON merge-condition
 *  WHEN MATCHED [AND matched-condition]
 *    THEN UPDATE SET xxx
 *  WHEN MATCHED [AND matched-condition]
 *    THEN DELETE
 *  WHEN NOT MATCHED [AND not-matched-condition]
 *    THEN INSERT VALUES (xxx)
 *  WHEN NOT MATCHED BY SOURCE [AND not-matched-by-source-condition]
 *    THEN UPDATE SET xxx
 *  WHEN NOT MATCHED BY SOURCE [AND not-matched-by-source-condition]
 *    THEN DELETE
 * </code></pre>
 *
 * <p>It builds a query to find the rows to be changed. INNER JOIN with merge-condition is used to
 * find MATCHED rows, and NOT EXISTS with merge-condition is used to find NOT MATCHED rows, then the
 * condition of each action is used to filter the rows.
 */
public class MergeIntoAction extends ActionBase {

    private static final Logger LOG = LoggerFactory.getLogger(MergeIntoAction.class);

    // primary keys of target table
    private final List<String> primaryKeys;

    // converters for Row to RowData
    private final List<DataStructureConverter<Object, Object>> converters;

    // field names of target table
    private final List<String> targetFieldNames;

    // target table
    @Nullable private String targetAlias;

    // source table
    @Nullable private String sourceTable;
    @Nullable private String[] sourceSqls;
    @Nullable private String sourceAlias;

    // merge condition
    private String mergeCondition;

    // actions to be taken
    private boolean matchedUpsert;
    private boolean notMatchedUpsert;
    private boolean matchedDelete;
    private boolean notMatchedDelete;
    private boolean insert;

    // upsert
    @Nullable private String matchedUpsertCondition;
    @Nullable private String matchedUpsertSet;

    @Nullable private String notMatchedBySourceUpsertCondition;
    @Nullable private String notMatchedBySourceUpsertSet;

    // delete
    @Nullable private String matchedDeleteCondition;
    @Nullable private String notMatchedBySourceDeleteCondition;

    // insert
    @Nullable private String notMatchedInsertCondition;
    @Nullable private String notMatchedInsertValues;

    MergeIntoAction(String warehouse, String database, String tableName) {
        super(warehouse, database, tableName);

        if (!(table instanceof FileStoreTable)) {
            throw new UnsupportedOperationException(
                    String.format(
                            "Only FileStoreTable supports merge-into action. The table type is '%s'.",
                            table.getClass().getName()));
        }

        // init primaryKeys of target table
        primaryKeys = ((FileStoreTable) table).schema().primaryKeys();
        if (primaryKeys.isEmpty()) {
            throw new UnsupportedOperationException(
                    "merge-into action doesn't support table with no primary keys defined.");
        }

        // init DataStructureConverters
        converters =
                table.rowType().getFieldTypes().stream()
                        .map(LogicalTypeConversion::toLogicalType)
                        .map(TypeConversions::fromLogicalToDataType)
                        .map(DataStructureConverters::getConverter)
                        .collect(Collectors.toList());

        // init field names of target table
        targetFieldNames =
                table.rowType().getFields().stream()
                        .map(DataField::name)
                        .collect(Collectors.toList());
    }

    public MergeIntoAction withTargetAlias(String targetAlias) {
        this.targetAlias = targetAlias;
        return this;
    }

    public MergeIntoAction withSourceTable(@Nullable String sourceAlias, String sourceTable) {
        this.sourceAlias = sourceAlias;
        this.sourceTable = sourceTable;
        return this;
    }

    public MergeIntoAction withSourceSqls(String sourceAlias, String... sourceSqls) {
        this.sourceAlias = sourceAlias;
        this.sourceSqls = sourceSqls;
        return this;
    }

    public MergeIntoAction withMergeCondition(String mergeCondition) {
        this.mergeCondition = mergeCondition;
        return this;
    }

    public MergeIntoAction withMatchedUpsert(
            @Nullable String matchedUpsertCondition, String matchedUpsertSet) {
        this.matchedUpsert = true;
        this.matchedUpsertCondition = matchedUpsertCondition;
        this.matchedUpsertSet = matchedUpsertSet;
        return this;
    }

    public MergeIntoAction withNotMatchedBySourceUpsert(
            @Nullable String notMatchedBySourceUpsertCondition,
            String notMatchedBySourceUpsertSet) {
        this.notMatchedUpsert = true;
        this.notMatchedBySourceUpsertCondition = notMatchedBySourceUpsertCondition;
        this.notMatchedBySourceUpsertSet = notMatchedBySourceUpsertSet;
        return this;
    }

    public MergeIntoAction withMatchedDelete(@Nullable String matchedDeleteCondition) {
        this.matchedDelete = true;
        this.matchedDeleteCondition = matchedDeleteCondition;
        return this;
    }

    public MergeIntoAction withNotMatchedBySourceDelete(
            @Nullable String notMatchedBySourceDeleteCondition) {
        this.notMatchedDelete = true;
        this.notMatchedBySourceDeleteCondition = notMatchedBySourceDeleteCondition;
        return this;
    }

    public MergeIntoAction withNotMatchedInsert(
            @Nullable String notMatchedInsertCondition, String notMatchedInsertValues) {
        this.insert = true;
        this.notMatchedInsertCondition = notMatchedInsertCondition;
        this.notMatchedInsertValues = notMatchedInsertValues;
        return this;
    }

    public static Optional<Action> create(String[] args) {
        LOG.info("merge-into job args: {}", String.join(" ", args));

        MultipleParameterTool params = MultipleParameterTool.fromArgs(args);

        if (params.has("help")) {
            printHelp();
            return Optional.empty();
        }

        Tuple3<String, String, String> tablePath = getTablePath(params);
        if (tablePath == null) {
            return Optional.empty();
        }

        MergeIntoAction action = new MergeIntoAction(tablePath.f0, tablePath.f1, tablePath.f2);

        if (params.has("target-as")) {
            action.withTargetAlias(params.get("target-as"));
        }

        if (!initSource(params, action)) {
            return Optional.empty();
        }

        if (argumentAbsent(params, "on")) {
            return Optional.empty();
        }
        action.withMergeCondition(params.get("on"));

        List<String> actions =
                Arrays.stream(params.get("merge-actions").split(","))
                        .map(String::trim)
                        .collect(Collectors.toList());
        if (actions.contains("matched-upsert")) {
            if (argumentAbsent(params, "matched-upsert-set")) {
                return Optional.empty();
            }
            action.withMatchedUpsert(
                    params.get("matched-upsert-condition"), params.get("matched-upsert-set"));
        }
        if (actions.contains("not-matched-by-source-upsert")) {
            if (argumentAbsent(params, "not-matched-by-source-upsert-set")) {
                return Optional.empty();
            }
            action.withNotMatchedBySourceUpsert(
                    params.get("not-matched-by-source-upsert-condition"),
                    params.get("not-matched-by-source-upsert-set"));
        }
        if (actions.contains("matched-delete")) {
            action.withMatchedDelete(params.get("matched-delete-condition"));
        }
        if (actions.contains("not-matched-by-source-delete")) {
            action.withNotMatchedBySourceDelete(
                    params.get("not-matched-by-source-delete-condition"));
        }
        if (actions.contains("not-matched-insert")) {
            if (argumentAbsent(params, "not-matched-insert-values")) {
                return Optional.empty();
            }
            action.withNotMatchedInsert(
                    params.get("not-matched-insert-condition"),
                    params.get("not-matched-insert-values"));
        }

        if (!validate(action)) {
            return Optional.empty();
        }

        return Optional.of(action);
    }

    private static boolean initSource(MultipleParameterTool params, MergeIntoAction action) {
        String sourceTable = params.get("source-table");
        Collection<String> sourceSqls = params.getMultiParameter("source-sql");
        String sourceAlias = params.get("source-as");

        int count = 0;
        if (sourceTable != null) {
            action.withSourceTable(sourceAlias, sourceTable);
            count++;
        }

        if (sourceSqls != null) {
            if (sourceAlias == null) {
                System.err.println(
                        "--source-sql and --source-as must be specified together.\n"
                                + "Run <action> --help for help.");
                return false;
            }
            action.withSourceSqls(sourceAlias, sourceSqls.toArray(new String[0]));
            count++;
        }

        if (count != 1) {
            System.err.println(
                    "Please specify source as either \"source-table\" or \"source-sql\".\n"
                            + "Run <action> --help for help.");
            return false;
        }

        return true;
    }

    private static boolean argumentAbsent(MultipleParameterTool params, String key) {
        if (!params.has(key)) {
            System.err.println(key + " is absent.\nRun <action> --help for help.");
            return true;
        }

        return false;
    }

    private static boolean validate(MergeIntoAction action) {
        if (!action.matchedUpsert
                && !action.notMatchedUpsert
                && !action.matchedDelete
                && !action.notMatchedDelete
                && !action.insert) {
            System.err.println(
                    "Must specify at least one merge action.\nRun <action> --help for help.");
            return false;
        }

        if ((action.matchedUpsert && action.matchedDelete)
                && (action.matchedUpsertCondition == null
                        || action.matchedDeleteCondition == null)) {
            System.err.println(
                    "If both matched-upsert and matched-delete actions are present, their conditions must both be present too.\n"
                            + "Run <action> --help for help.");
            return false;
        }

        if ((action.notMatchedUpsert && action.notMatchedDelete)
                && (action.notMatchedBySourceUpsertCondition == null
                        || action.notMatchedBySourceDeleteCondition == null)) {
            System.err.println(
                    "If both not-matched-by-source-upsert and not-matched-by--source-delete actions are present, their conditions must both be present too.\n"
                            + "Run <action> --help for help.");
            return false;
        }

        if (action.notMatchedBySourceUpsertSet != null
                && action.notMatchedBySourceUpsertSet.equals("*")) {
            System.err.println("The '*' cannot be used in not-matched-by-source-upsert-set");
            return false;
        }

        return true;
    }

    private static void printHelp() {
        System.out.println("Action \"merge-into\" simulates the \"MERGE INTO\" syntax.");
        System.out.println();

        System.out.println("Syntax:");
        System.out.println(
                "  merge-into --warehouse <warehouse-path>\n"
                        + "             --database <database-name>\n"
                        + "             --table <target-table-name>\n"
                        + "             [--target-as <target-table-alias>]\n"
                        + "             --source-table <source-table>\n"
                        + "             [--source-as <source-table-alias>]\n"
                        + "             --on <merge-condition>\n"
                        + "             --merge-actions <matched-upsert,matched-delete,not-matched-insert,not-matched-by-source-upsert,not-matched-by-source-delete>\n"
                        + "             --matched-upsert-condition <matched-condition>\n"
                        + "             --matched-upsert-set <upsert-changes>\n"
                        + "             --matched-delete-condition <matched-condition>\n"
                        + "             --not-matched-insert-condition <not-matched-condition>\n"
                        + "             --not-matched-insert-values <insert-values>\n"
                        + "             --not-matched-by-source-upsert-condition <not-matched-by-source-condition>\n"
                        + "             --not-matched-by-source-upsert-set <not-matched-upsert-changes>\n"
                        + "             --not-matched-by-source-delete-condition <not-matched-by-source-condition>");

        System.out.println("  matched-upsert-changes format:");
        System.out.println(
                "    col=<source-table>.col | expression [, ...] (do not add '<target-table>.' before 'col')");
        System.out.println(
                "    * (upsert with all source cols; require target table's schema is equal to source's)");

        System.out.println("  not-matched-upsert-changes format:");
        System.out.println("    col=expression (cannot use source table's col)");

        System.out.println("  insert-values format:");
        System.out.println(
                "    col1,col2,...,col_end (must specify values of all columns; can use <source-table>.col or expression)");
        System.out.println(
                "    * (insert with all source cols; require target table's schema is equal to source's)");

        System.out.println(
                "  not-matched-condition: cannot use target table's columns to construct condition expression.");
        System.out.println(
                "  not-matched-by-source-condition: cannot use source table's columns to construct condition expression.");

        System.out.println("  alternative arguments:");
        System.out.println("    --path <table-path> to represent the table path.");
        System.out.println(
                "    --source-sql <sql>[, --source-sql <sql> ...] can create a new table as source table at runtime.");
        System.out.println();

        System.out.println("Note: ");
        System.out.println("  1. Target table must has primary keys.");
        System.out.println(
                "  2. All conditions, set changes and values should use Flink SQL syntax. Please quote them with \" to escape special characters.");
        System.out.println(
                "  3. source-alias cannot be duplicated with existed table name. "
                        + "If you use --source-sql, source-alias must be specified and equal to the table name in \"CREATE\" statement.");
        System.out.println(
                "  4. If the source table is not in the same place as target table, "
                        + "the source-table-name or the source-alias should be qualified (database.table or catalog.database.table if in different catalog).");
        System.out.println("  5. At least one merge action must be specified.");
        System.out.println("  6. How to determine the changed rows with different \"matched\":");
        System.out.println(
                "    matched: changed rows are from target table and each can match a source table row "
                        + "based on merge-condition and optional matched-condition.");
        System.out.println(
                "    not-matched: changed rows are from source table and all rows cannot match any target table row "
                        + "based on merge-condition and optional not-matched-condition.");
        System.out.println(
                "    not-matched-by-source: changed rows are from target table and all row cannot match any source table row "
                        + "based on merge-condition and optional not-matched-by-source-condition.");
        System.out.println(
                "  7. If both matched-upsert and matched-delete actions are present, their conditions must both be present too "
                        + "(same to not-matched-by-source-upsert and not-matched-by-source-delete). Otherwise, all conditions are optional.");
        System.out.println();

        System.out.println("Examples:");
        System.out.println(
                "  merge-into --path hdfs:///path/to/T\n"
                        + "             --source-table S\n"
                        + "             --on \"T.k = S.k\"\n"
                        + "             --merge-actions matched-upsert\n"
                        + "             --matched-upsert-condition \"T.v <> S.v\"\n"
                        + "             --matched-upsert-set \"v = S.v\"");
        System.out.println(
                "  It will find matched rows of target table that meet condition (T.k = S.k), then update T.v with S.v where (T.v <> S.v).");
    }

    @Override
    public void run() throws Exception {
        // handle target alias
        if (targetAlias != null) {
            tEnv.registerTable(targetAlias, tEnv.from(identifier.getObjectName()));
        }

        // prepare source table
        if (sourceTable != null) {
            sourceAlias = sourceTable;
        } else {
            // NOTE: sql may change current catalog and database
            try {
                for (String sql : sourceSqls) {
                    tEnv.executeSql(sql).await();
                }
            } catch (Throwable t) {
                LOG.error("Error occurs when executing sql in --source-sql.", t);
                throw new RuntimeException("Error occurs when executing sql in --source-sql.", t);
            }
        }

        List<DataStream<RowData>> dataStreams =
                Stream.of(
                                getMatchedUpsertDataStream(),
                                getNotMatchedUpsertDataStream(),
                                getMatchedDeleteDataStream(),
                                getNotMatchedDeleteDataStream(),
                                getInsertDataStream())
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(Collectors.toList());

        DataStream<RowData> firstDs = dataStreams.get(0);
        batchSink(firstDs.union(dataStreams.stream().skip(1).toArray(DataStream[]::new)));
    }

    private Optional<DataStream<RowData>> getMatchedUpsertDataStream() {
        if (!matchedUpsert) {
            return Optional.empty();
        }

        List<String> project;
        // extract project
        if (matchedUpsertSet.equals("*")) {
            project = Collections.singletonList(sourceAlias + "." + "*");
        } else {
            // validate upsert changes
            // no need to check primary keys changes because merge condition must contain all pks
            // of the target table
            Map<String, String> changes = parseKeyValues(matchedUpsertSet);
            if (changes == null) {
                throw new IllegalArgumentException(
                        "matched-upsert-set is invalid.\nRun <action> --help for help.");
            }
            for (String targetField : changes.keySet()) {
                if (!targetFieldNames.contains(targetField)) {
                    throw new RuntimeException(
                            String.format(
                                    "Invalid column reference '%s' of table '%s' at matched-upsert action.",
                                    targetField, identifier.getFullName()));
                }
            }

            // replace field names
            // the table name is added before column name to avoid ambiguous column reference
            project =
                    targetFieldNames.stream()
                            .map(name -> changes.getOrDefault(name, targetTableName() + "." + name))
                            .collect(Collectors.toList());
        }

        // use inner join to find matched records
        String query =
                String.format(
                        "SELECT %s FROM %s INNER JOIN %s ON %s %s",
                        String.join(",", project),
                        escapedTargetName(),
                        escapedSourceName(),
                        mergeCondition,
                        matchedUpsertCondition == null ? "" : "WHERE " + matchedUpsertCondition);
        LOG.info("Query used for matched-update:\n{}", query);

        Table source = tEnv.sqlQuery(query);
        checkSchema("matched-upsert", source);

        return Optional.of(toDataStream(source, RowKind.UPDATE_AFTER, converters));
    }

    private Optional<DataStream<RowData>> getNotMatchedUpsertDataStream() {
        if (!notMatchedUpsert) {
            return Optional.empty();
        }

        // validate upsert change
        Map<String, String> changes = parseKeyValues(notMatchedBySourceUpsertSet);
        if (changes == null) {
            throw new IllegalArgumentException(
                    "matched-upsert-set is invalid.\nRun <action> --help for help.");
        }
        for (String targetField : changes.keySet()) {
            if (!targetFieldNames.contains(targetField)) {
                throw new RuntimeException(
                        String.format(
                                "Invalid column reference '%s' of table '%s' at not-matched-by-source-upsert action.\nRun <action> --help for help.",
                                targetField, identifier.getFullName()));
            }

            if (primaryKeys.contains(targetField)) {
                throw new RuntimeException(
                        "Not allowed to change primary key in not-matched-by-source-upsert-set.\nRun <action> --help for help.");
            }
        }

        // replace field names (won't be ambiguous here)
        List<String> project =
                targetFieldNames.stream()
                        .map(name -> changes.getOrDefault(name, name))
                        .collect(Collectors.toList());

        // use not exists to find not matched records
        String query =
                String.format(
                        "SELECT %s FROM %s WHERE NOT EXISTS (SELECT * FROM %s WHERE %s) %s",
                        String.join(",", project),
                        escapedTargetName(),
                        escapedSourceName(),
                        mergeCondition,
                        notMatchedBySourceUpsertCondition == null
                                ? ""
                                : String.format("AND (%s)", notMatchedBySourceUpsertCondition));

        LOG.info("Query used for not-matched-by-source-upsert:\n{}", query);

        Table source = tEnv.sqlQuery(query);
        checkSchema("not-matched-by-source-upsert", source);

        return Optional.of(toDataStream(source, RowKind.UPDATE_AFTER, converters));
    }

    private Optional<DataStream<RowData>> getMatchedDeleteDataStream() {
        if (!matchedDelete) {
            return Optional.empty();
        }

        // the table name is added before column name to avoid ambiguous column reference
        List<String> project =
                targetFieldNames.stream()
                        .map(name -> targetTableName() + "." + name)
                        .collect(Collectors.toList());

        // use inner join to find matched records
        String query =
                String.format(
                        "SELECT %s FROM %s INNER JOIN %s ON %s %s",
                        String.join(",", project),
                        escapedTargetName(),
                        escapedSourceName(),
                        mergeCondition,
                        matchedDeleteCondition == null ? "" : "WHERE " + matchedDeleteCondition);
        LOG.info("Query used by matched-delete:\n{}", query);

        Table source = tEnv.sqlQuery(query);
        checkSchema("matched-delete", source);

        return Optional.of(toDataStream(source, RowKind.DELETE, converters));
    }

    private Optional<DataStream<RowData>> getNotMatchedDeleteDataStream() {
        if (!notMatchedDelete) {
            return Optional.empty();
        }

        // use not exists to find not matched records
        String query =
                String.format(
                        "SELECT %s FROM %s WHERE NOT EXISTS (SELECT * FROM %s WHERE %s) %s",
                        String.join(",", targetFieldNames),
                        escapedTargetName(),
                        escapedSourceName(),
                        mergeCondition,
                        notMatchedBySourceDeleteCondition == null
                                ? ""
                                : String.format("AND (%s)", notMatchedBySourceDeleteCondition));
        LOG.info("Query used by not-matched-by-source-delete:\n{}", query);

        Table source = tEnv.sqlQuery(query);
        checkSchema("not-matched-by-source-delete", source);

        return Optional.of(toDataStream(source, RowKind.DELETE, converters));
    }

    private Optional<DataStream<RowData>> getInsertDataStream() {
        if (!insert) {
            return Optional.empty();
        }

        // use not exist to find rows to insert
        String query =
                String.format(
                        "SELECT %s FROM %s WHERE NOT EXISTS (SELECT * FROM %s WHERE %s) %s",
                        notMatchedInsertValues,
                        escapedSourceName(),
                        escapedTargetName(),
                        mergeCondition,
                        notMatchedInsertCondition == null
                                ? ""
                                : String.format("AND (%s)", notMatchedInsertCondition));
        LOG.info("Query used by not-matched-insert:\n{}", query);

        Table source = tEnv.sqlQuery(query);
        checkSchema("not-matched-insert", source);

        return Optional.of(toDataStream(source, RowKind.INSERT, converters));
    }

    private void checkSchema(String action, Table source) {
        List<DataType> actualTypes =
                toTableStoreDataTypes(source.getResolvedSchema().getColumnDataTypes());
        List<DataType> expectedTypes = this.table.rowType().getFieldTypes();
        if (!compatibleCheck(actualTypes, expectedTypes)) {
            throw new IllegalStateException(
                    String.format(
                            "The schema of result in action '%s' is invalid.\n"
                                    + "Result schema:   [%s]\n"
                                    + "Expected schema: [%s]",
                            action,
                            actualTypes.stream()
                                    .map(DataType::asSQLString)
                                    .collect(Collectors.joining(", ")),
                            expectedTypes.stream()
                                    .map(DataType::asSQLString)
                                    .collect(Collectors.joining(", "))));
        }
    }

    // pass converters to avoid "not serializable" exception
    private DataStream<RowData> toDataStream(
            Table source, RowKind kind, List<DataStructureConverter<Object, Object>> converters) {
        return tEnv.toChangelogStream(source)
                .map(
                        row -> {
                            int arity = row.getArity();
                            GenericRowData rowData = new GenericRowData(kind, arity);
                            for (int i = 0; i < arity; i++) {
                                rowData.setField(
                                        i, converters.get(i).toInternalOrNull(row.getField(i)));
                            }
                            return rowData;
                        });
    }

    private String targetTableName() {
        return targetAlias == null ? identifier.getObjectName() : targetAlias;
    }

    private String escapedTargetName() {
        return String.format(
                "`%s`.`%s`.`%s`", catalogName, identifier.getDatabaseName(), targetTableName());
    }

    private String escapedSourceName() {
        return Arrays.stream(sourceAlias.split("\\."))
                .map(s -> String.format("`%s`", s))
                .collect(Collectors.joining("."));
    }
}
