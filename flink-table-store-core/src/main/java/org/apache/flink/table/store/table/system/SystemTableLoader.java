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

package org.apache.flink.table.store.table.system;

import org.apache.flink.table.store.fs.FileIO;
import org.apache.flink.table.store.fs.Path;
import org.apache.flink.table.store.table.FileStoreTable;
import org.apache.flink.table.store.table.Table;

import javax.annotation.Nullable;

import static org.apache.flink.table.store.table.system.AuditLogTable.AUDIT_LOG;
import static org.apache.flink.table.store.table.system.FilesTable.FILES;
import static org.apache.flink.table.store.table.system.OptionsTable.OPTIONS;
import static org.apache.flink.table.store.table.system.SchemasTable.SCHEMAS;
import static org.apache.flink.table.store.table.system.SnapshotsTable.SNAPSHOTS;

/** Loader to load system {@link Table}s. */
public class SystemTableLoader {

    @Nullable
    public static Table load(String type, FileIO fileIO, FileStoreTable dataTable) {
        Path location = dataTable.location();
        switch (type.toLowerCase()) {
            case SNAPSHOTS:
                return new SnapshotsTable(fileIO, location);
            case OPTIONS:
                return new OptionsTable(fileIO, location);
            case SCHEMAS:
                return new SchemasTable(fileIO, location);
            case AUDIT_LOG:
                return new AuditLogTable(dataTable);
            case FILES:
                return new FilesTable(dataTable);
            default:
                return null;
        }
    }
}
