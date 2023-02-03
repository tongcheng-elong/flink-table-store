/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.store.spark;

import org.apache.flink.table.store.file.predicate.Predicate;
import org.apache.flink.table.store.table.Table;
import org.apache.flink.table.store.table.source.Split;
import org.apache.flink.table.store.utils.TypeUtils;

import org.apache.spark.sql.connector.read.Batch;
import org.apache.spark.sql.connector.read.InputPartition;
import org.apache.spark.sql.connector.read.PartitionReaderFactory;
import org.apache.spark.sql.connector.read.Scan;
import org.apache.spark.sql.connector.read.Statistics;
import org.apache.spark.sql.connector.read.SupportsReportStatistics;
import org.apache.spark.sql.types.StructType;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * A Spark {@link Scan} for table store.
 *
 * <p>TODO Introduce a SparkRFScan to implement SupportsRuntimeFiltering.
 */
public class SparkScan implements Scan, SupportsReportStatistics {

    protected final Table table;
    private final List<Predicate> predicates;
    private final int[] projectedFields;

    private List<Split> splits;

    public SparkScan(Table table, List<Predicate> predicates, int[] projectedFields) {
        this.table = table;
        this.predicates = predicates;
        this.projectedFields = projectedFields;
    }

    @Override
    public String description() {
        // TODO add filters
        return String.format("tablestore(%s)", table.name());
    }

    @Override
    public StructType readSchema() {
        return SparkTypeUtils.fromFlinkRowType(TypeUtils.project(table.rowType(), projectedFields));
    }

    @Override
    public Batch toBatch() {
        return new Batch() {
            @Override
            public InputPartition[] planInputPartitions() {
                return splits().stream()
                        .map(SparkInputPartition::new)
                        .toArray(InputPartition[]::new);
            }

            @Override
            public PartitionReaderFactory createReaderFactory() {
                return new SparkReaderFactory(table, projectedFields, predicates);
            }
        };
    }

    protected List<Split> splits() {
        if (splits == null) {
            this.splits = table.newScan().withFilter(predicates).plan().splits();
        }
        return splits;
    }

    @Override
    public Statistics estimateStatistics() {
        long rowCount = 0L;

        for (Split split : splits()) {
            rowCount += split.rowCount();
        }

        final long numRows = rowCount;
        final long sizeInBytes = readSchema().defaultSize() * numRows;

        return new Statistics() {
            @Override
            public OptionalLong sizeInBytes() {
                return OptionalLong.of(sizeInBytes);
            }

            @Override
            public OptionalLong numRows() {
                return OptionalLong.of(numRows);
            }
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        SparkScan that = (SparkScan) o;
        return table.name().equals(that.table.name())
                && readSchema().equals(that.readSchema())
                && predicates.equals(that.predicates);
    }

    @Override
    public int hashCode() {
        return Objects.hash(table.name(), readSchema(), predicates);
    }
}
