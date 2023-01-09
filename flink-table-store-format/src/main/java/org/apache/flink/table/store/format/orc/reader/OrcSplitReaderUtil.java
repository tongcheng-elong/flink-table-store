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

package org.apache.flink.table.store.format.orc.reader;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.types.logical.ArrayType;
import org.apache.flink.table.types.logical.CharType;
import org.apache.flink.table.types.logical.DecimalType;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.MapType;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.table.types.logical.VarCharType;

import org.apache.orc.TypeDescription;

/** Util for orc types. */
public class OrcSplitReaderUtil {

    /** See {@code org.apache.flink.table.catalog.hive.util.HiveTypeUtil}. */
    public static TypeDescription logicalTypeToOrcType(LogicalType type) {
        type = type.copy(true);
        switch (type.getTypeRoot()) {
            case CHAR:
                return TypeDescription.createChar().withMaxLength(((CharType) type).getLength());
            case VARCHAR:
                int len = ((VarCharType) type).getLength();
                if (len == VarCharType.MAX_LENGTH) {
                    return TypeDescription.createString();
                } else {
                    return TypeDescription.createVarchar().withMaxLength(len);
                }
            case BOOLEAN:
                return TypeDescription.createBoolean();
            case VARBINARY:
                if (type.equals(DataTypes.BYTES().getLogicalType())) {
                    return TypeDescription.createBinary();
                } else {
                    throw new UnsupportedOperationException(
                            "Not support other binary type: " + type);
                }
            case DECIMAL:
                DecimalType decimalType = (DecimalType) type;
                return TypeDescription.createDecimal()
                        .withScale(decimalType.getScale())
                        .withPrecision(decimalType.getPrecision());
            case TINYINT:
                return TypeDescription.createByte();
            case SMALLINT:
                return TypeDescription.createShort();
            case INTEGER:
                return TypeDescription.createInt();
            case BIGINT:
                return TypeDescription.createLong();
            case FLOAT:
                return TypeDescription.createFloat();
            case DOUBLE:
                return TypeDescription.createDouble();
            case DATE:
                return TypeDescription.createDate();
            case TIMESTAMP_WITHOUT_TIME_ZONE:
                return TypeDescription.createTimestamp();
            case ARRAY:
                ArrayType arrayType = (ArrayType) type;
                return TypeDescription.createList(logicalTypeToOrcType(arrayType.getElementType()));
            case MAP:
                MapType mapType = (MapType) type;
                return TypeDescription.createMap(
                        logicalTypeToOrcType(mapType.getKeyType()),
                        logicalTypeToOrcType(mapType.getValueType()));
            case ROW:
                RowType rowType = (RowType) type;
                TypeDescription struct = TypeDescription.createStruct();
                for (int i = 0; i < rowType.getFieldCount(); i++) {
                    struct.addField(
                            rowType.getFieldNames().get(i),
                            logicalTypeToOrcType(rowType.getChildren().get(i)));
                }
                return struct;
            default:
                throw new UnsupportedOperationException("Unsupported type: " + type);
        }
    }
}
