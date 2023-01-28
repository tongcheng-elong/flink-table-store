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

package org.apache.flink.table.store.format.avro;

import org.apache.flink.table.store.data.InternalRow;
import org.apache.flink.table.store.types.DataType;
import org.apache.flink.table.store.types.DataTypes;
import org.apache.flink.table.store.types.RowType;

import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

import java.util.function.Function;

/** Testing utils for tests related to {@link AbstractAvroBulkFormat}. */
public class AvroBulkFormatTestUtils {

    public static final RowType ROW_TYPE =
            (RowType)
                    RowType.builder()
                            .fields(
                                    new DataType[] {DataTypes.STRING(), DataTypes.STRING()},
                                    new String[] {"a", "b"})
                            .build()
                            .notNull();

    /** {@link AbstractAvroBulkFormat} for tests. */
    public static class TestingAvroBulkFormat extends AbstractAvroBulkFormat<GenericRecord> {

        protected TestingAvroBulkFormat() {
            super(AvroSchemaConverter.convertToSchema(ROW_TYPE));
        }

        @Override
        protected GenericRecord createReusedAvroRecord() {
            return new GenericData.Record(readerSchema);
        }

        @Override
        protected Function<GenericRecord, InternalRow> createConverter() {
            AvroToRowDataConverters.AvroToRowDataConverter converter =
                    AvroToRowDataConverters.createRowConverter(ROW_TYPE);
            return record -> record == null ? null : (InternalRow) converter.convert(record);
        }
    }
}
