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

package org.apache.flink.table.store.format.parquet;

import org.apache.flink.table.store.format.FieldStats;
import org.apache.flink.table.store.format.FileFormat;
import org.apache.flink.table.store.format.FileStatsExtractorTestBase;
import org.apache.flink.table.store.options.Options;
import org.apache.flink.table.store.types.ArrayType;
import org.apache.flink.table.store.types.BigIntType;
import org.apache.flink.table.store.types.BinaryType;
import org.apache.flink.table.store.types.BooleanType;
import org.apache.flink.table.store.types.CharType;
import org.apache.flink.table.store.types.DataType;
import org.apache.flink.table.store.types.DateType;
import org.apache.flink.table.store.types.DecimalType;
import org.apache.flink.table.store.types.DoubleType;
import org.apache.flink.table.store.types.FloatType;
import org.apache.flink.table.store.types.IntType;
import org.apache.flink.table.store.types.MapType;
import org.apache.flink.table.store.types.MultisetType;
import org.apache.flink.table.store.types.RowType;
import org.apache.flink.table.store.types.SmallIntType;
import org.apache.flink.table.store.types.TimestampType;
import org.apache.flink.table.store.types.TinyIntType;
import org.apache.flink.table.store.types.VarBinaryType;
import org.apache.flink.table.store.types.VarCharType;

/** Tests for {@link ParquetFileStatsExtractor}. */
public class ParquetFileStatsExtractorTest extends FileStatsExtractorTestBase {

    @Override
    protected FileFormat createFormat() {
        return FileFormat.fromIdentifier("parquet", new Options());
    }

    @Override
    protected RowType rowType() {
        return RowType.builder()
                .fields(
                        new CharType(8),
                        new VarCharType(8),
                        new BooleanType(),
                        new BinaryType(8),
                        new VarBinaryType(8),
                        new TinyIntType(),
                        new SmallIntType(),
                        new IntType(),
                        new BigIntType(),
                        new FloatType(),
                        new DoubleType(),
                        new DecimalType(5, 2),
                        new DecimalType(15, 2),
                        new DecimalType(38, 18),
                        new DateType(),
                        new TimestampType(3),
                        new TimestampType(6),
                        new TimestampType(9),
                        new ArrayType(new IntType()),
                        new MapType(new VarCharType(8), new VarCharType(8)),
                        new MultisetType(new VarCharType(8)))
                .build();
    }

    @Override
    protected FieldStats regenerate(FieldStats stats, DataType type) {
        switch (type.getTypeRoot()) {
            case TIMESTAMP_WITHOUT_TIME_ZONE:
                TimestampType timestampType = (TimestampType) type;
                if (timestampType.getPrecision() > 6) {
                    return new FieldStats(null, null, stats.nullCount());
                }
                break;
            case ARRAY:
            case MAP:
            case MULTISET:
            case ROW:
                return new FieldStats(null, null, null);
        }
        return stats;
    }
}
