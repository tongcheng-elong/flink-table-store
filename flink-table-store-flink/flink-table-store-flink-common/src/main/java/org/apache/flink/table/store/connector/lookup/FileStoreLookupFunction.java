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

import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.functions.FunctionContext;
import org.apache.flink.table.functions.TableFunction;
import org.apache.flink.table.store.CoreOptions;
import org.apache.flink.table.store.connector.FlinkRowData;
import org.apache.flink.table.store.connector.FlinkRowWrapper;
import org.apache.flink.table.store.data.InternalRow;
import org.apache.flink.table.store.file.predicate.Predicate;
import org.apache.flink.table.store.file.predicate.PredicateFilter;
import org.apache.flink.table.store.file.schema.TableSchema;
import org.apache.flink.table.store.options.Options;
import org.apache.flink.table.store.table.FileStoreTable;
import org.apache.flink.table.store.table.source.StreamDataTableScan;
import org.apache.flink.table.store.table.source.TableStreamingReader;
import org.apache.flink.table.store.types.RowType;
import org.apache.flink.table.store.utils.FileIOUtils;
import org.apache.flink.table.store.utils.TypeUtils;

import org.apache.flink.shaded.guava30.com.google.common.primitives.Ints;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.apache.flink.table.store.connector.RocksDBOptions.LOOKUP_CACHE_ROWS;
import static org.apache.flink.table.store.file.predicate.PredicateBuilder.transformFieldMapping;
import static org.apache.flink.table.store.utils.Preconditions.checkArgument;

/** A lookup {@link TableFunction} for file store. */
public class FileStoreLookupFunction implements Serializable, Closeable {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(FileStoreLookupFunction.class);

    private final FileStoreTable table;
    private final List<String> projectFields;
    private final List<String> joinKeys;
    @Nullable private final Predicate predicate;

    private transient Duration refreshInterval;
    private transient File path;
    private transient RocksDBStateFactory stateFactory;
    private transient LookupTable lookupTable;

    // timestamp when cache expires
    private transient long nextLoadTime;
    private transient TableStreamingReader streamingReader;

    public FileStoreLookupFunction(
            FileStoreTable table,
            int[] projection,
            int[] joinKeyIndex,
            @Nullable Predicate predicate) {
        TableSchema schema = table.schema();
        checkArgument(
                schema.partitionKeys().isEmpty(), "Currently only support non-partitioned table.");
        checkArgument(schema.primaryKeys().size() > 0, "Currently only support primary key table.");
        StreamDataTableScan.validate(table.schema());

        this.table = table;

        // join keys are based on projection fields
        this.joinKeys =
                Arrays.stream(joinKeyIndex)
                        .mapToObj(i -> schema.fieldNames().get(projection[i]))
                        .collect(Collectors.toList());

        this.projectFields =
                Arrays.stream(projection)
                        .mapToObj(i -> schema.fieldNames().get(i))
                        .collect(Collectors.toList());

        // add primary keys
        for (String field : schema.primaryKeys()) {
            if (!projectFields.contains(field)) {
                projectFields.add(field);
            }
        }

        this.predicate = predicate;
    }

    public void open(FunctionContext context) throws Exception {
        String tmpDirectory = getTmpDirectory(context);
        this.path = new File(tmpDirectory, "lookup-" + UUID.randomUUID());

        Options options = Options.fromMap(table.schema().options());
        this.refreshInterval = options.get(CoreOptions.CONTINUOUS_DISCOVERY_INTERVAL);
        this.stateFactory = new RocksDBStateFactory(path.toString(), options);

        List<String> fieldNames = table.schema().logicalRowType().getFieldNames();
        int[] projection = projectFields.stream().mapToInt(fieldNames::indexOf).toArray();
        RowType rowType = TypeUtils.project(table.schema().logicalRowType(), projection);

        PredicateFilter recordFilter = createRecordFilter(projection);
        this.lookupTable =
                LookupTable.create(
                        stateFactory,
                        rowType,
                        table.schema().primaryKeys(),
                        joinKeys,
                        recordFilter,
                        options.get(LOOKUP_CACHE_ROWS));
        this.nextLoadTime = -1;
        this.streamingReader = new TableStreamingReader(table, projection, this.predicate);

        // do first load
        refresh();
    }

    private PredicateFilter createRecordFilter(int[] projection) {
        Predicate adjustedPredicate = null;
        if (predicate != null) {
            // adjust to projection index
            adjustedPredicate =
                    transformFieldMapping(
                                    this.predicate,
                                    IntStream.range(0, table.schema().fields().size())
                                            .map(i -> Ints.indexOf(projection, i))
                                            .toArray())
                            .orElse(null);
        }
        return new PredicateFilter(
                TypeUtils.project(table.schema().logicalRowType(), projection), adjustedPredicate);
    }

    public Collection<RowData> lookup(RowData keyRow) {
        try {
            checkRefresh();
            List<InternalRow> results = lookupTable.get(new FlinkRowWrapper(keyRow));
            List<RowData> rows = new ArrayList<>(results.size());
            for (InternalRow matchedRow : results) {
                rows.add(new FlinkRowData(matchedRow));
            }
            return rows;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void checkRefresh() throws Exception {
        if (nextLoadTime > System.currentTimeMillis()) {
            return;
        }
        if (nextLoadTime > 0) {
            LOG.info(
                    "Lookup table has refreshed after {} second(s), refreshing",
                    refreshInterval.toMillis() / 1000);
        }

        refresh();

        nextLoadTime = System.currentTimeMillis() + refreshInterval.toMillis();
    }

    private void refresh() throws Exception {
        while (true) {
            Iterator<InternalRow> batch = streamingReader.nextBatch();
            if (batch == null) {
                return;
            }
            this.lookupTable.refresh(batch);
        }
    }

    @Override
    public void close() throws IOException {
        if (stateFactory != null) {
            stateFactory.close();
            stateFactory = null;
        }

        if (path != null) {
            FileIOUtils.deleteDirectoryQuietly(path);
        }
    }

    private static String getTmpDirectory(FunctionContext context) {
        try {
            Field field = context.getClass().getDeclaredField("context");
            field.setAccessible(true);
            StreamingRuntimeContext runtimeContext = (StreamingRuntimeContext) field.get(context);
            String[] tmpDirectories =
                    runtimeContext.getTaskManagerRuntimeInfo().getTmpDirectories();
            return tmpDirectories[ThreadLocalRandom.current().nextInt(tmpDirectories.length)];
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
