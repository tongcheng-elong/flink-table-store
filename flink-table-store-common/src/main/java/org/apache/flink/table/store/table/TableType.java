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

package org.apache.flink.table.store.table;

import org.apache.flink.table.store.options.description.DescribedEnum;
import org.apache.flink.table.store.options.description.InlineElement;

import static org.apache.flink.table.store.options.description.TextElement.text;

/** Enum of catalog table type. */
public enum TableType implements DescribedEnum {
    MANAGED(
            "managed",
            "Table Store owned table where the entire lifecycle of the table data is managed."),
    EXTERNAL(
            "external",
            "The table where Table Store has loose coupling with the data stored in external locations.");

    private final String value;
    private final String description;

    TableType(String value, String description) {
        this.value = value;
        this.description = description;
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public InlineElement getDescription() {
        return text(description);
    }
}
