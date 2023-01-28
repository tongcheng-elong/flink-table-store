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

package org.apache.flink.table.store.hive;

import org.apache.flink.table.store.types.ArrayType;
import org.apache.flink.table.store.types.CharType;
import org.apache.flink.table.store.types.DataType;
import org.apache.flink.table.store.types.DecimalType;
import org.apache.flink.table.store.types.MapType;
import org.apache.flink.table.store.types.VarCharType;

import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoFactory;

/** Utils for converting types related classes between Flink and Hive. */
public class HiveTypeUtils {

    public static TypeInfo logicalTypeToTypeInfo(DataType logicalType) {
        switch (logicalType.getTypeRoot()) {
            case BOOLEAN:
                return TypeInfoFactory.booleanTypeInfo;
            case TINYINT:
                return TypeInfoFactory.byteTypeInfo;
            case SMALLINT:
                return TypeInfoFactory.shortTypeInfo;
            case INTEGER:
                return TypeInfoFactory.intTypeInfo;
            case BIGINT:
                return TypeInfoFactory.longTypeInfo;
            case FLOAT:
                return TypeInfoFactory.floatTypeInfo;
            case DOUBLE:
                return TypeInfoFactory.doubleTypeInfo;
            case DECIMAL:
                DecimalType decimalType = (DecimalType) logicalType;
                return TypeInfoFactory.getDecimalTypeInfo(
                        decimalType.getPrecision(), decimalType.getScale());
            case CHAR:
                CharType charType = (CharType) logicalType;
                return TypeInfoFactory.getCharTypeInfo(charType.getLength());
            case VARCHAR:
                VarCharType varCharType = (VarCharType) logicalType;
                if (varCharType.getLength() == VarCharType.MAX_LENGTH) {
                    return TypeInfoFactory.stringTypeInfo;
                } else {
                    return TypeInfoFactory.getVarcharTypeInfo(varCharType.getLength());
                }
            case BINARY:
            case VARBINARY:
                return TypeInfoFactory.binaryTypeInfo;
            case DATE:
                return TypeInfoFactory.dateTypeInfo;
            case TIMESTAMP_WITHOUT_TIME_ZONE:
                return TypeInfoFactory.timestampTypeInfo;
            case ARRAY:
                ArrayType arrayType = (ArrayType) logicalType;
                return TypeInfoFactory.getListTypeInfo(
                        logicalTypeToTypeInfo(arrayType.getElementType()));
            case MAP:
                MapType mapType = (MapType) logicalType;
                return TypeInfoFactory.getMapTypeInfo(
                        logicalTypeToTypeInfo(mapType.getKeyType()),
                        logicalTypeToTypeInfo(mapType.getValueType()));
            default:
                throw new UnsupportedOperationException(
                        "Unsupported logical type " + logicalType.asSQLString());
        }
    }
}
