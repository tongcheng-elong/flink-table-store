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

package org.apache.flink.table.store.file.catalog;

import org.apache.flink.table.store.fs.FileIO;
import org.apache.flink.table.store.fs.Path;
import org.apache.flink.table.store.options.CatalogOptions;
import org.apache.flink.table.store.table.TableType;

import static org.apache.flink.table.store.options.CatalogOptions.TABLE_TYPE;

/** Factory to create {@link FileSystemCatalog}. */
public class FileSystemCatalogFactory implements CatalogFactory {

    public static final String IDENTIFIER = "filesystem";

    @Override
    public String identifier() {
        return IDENTIFIER;
    }

    @Override
    public Catalog create(FileIO fileIO, Path warehouse, CatalogOptions options) {
        if (!TableType.MANAGED.equals(options.get(TABLE_TYPE))) {
            throw new IllegalArgumentException(
                    "Only managed table is supported in File system catalog.");
        }
        return new FileSystemCatalog(fileIO, warehouse);
    }
}
