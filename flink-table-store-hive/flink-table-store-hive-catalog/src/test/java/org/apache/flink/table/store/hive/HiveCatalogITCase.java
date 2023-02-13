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

import org.apache.flink.connectors.hive.FlinkEmbeddedHiveRunner;
import org.apache.flink.core.fs.Path;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.internal.TableEnvironmentImpl;
import org.apache.flink.table.store.connector.FlinkCatalog;
import org.apache.flink.table.store.file.catalog.Catalog;
import org.apache.flink.table.store.file.catalog.CatalogLock;
import org.apache.flink.table.store.file.catalog.Identifier;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;
import org.apache.flink.util.ExceptionUtils;

import com.klarna.hiverunner.HiveShell;
import com.klarna.hiverunner.annotations.HiveRunnerSetup;
import com.klarna.hiverunner.annotations.HiveSQL;
import com.klarna.hiverunner.config.HiveRunnerConfig;
import org.apache.hadoop.hive.ql.lockmgr.DbTxnManager;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.HIVE_IN_TEST;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.HIVE_SUPPORT_CONCURRENCY;
import static org.apache.hadoop.hive.conf.HiveConf.ConfVars.HIVE_TXN_MANAGER;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

/** IT cases for {@link HiveCatalog}. */
@RunWith(FlinkEmbeddedHiveRunner.class)
public class HiveCatalogITCase {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private String path;
    private TableEnvironment tEnv;

    @HiveSQL(files = {})
    private static HiveShell hiveShell;

    @HiveRunnerSetup
    private static final HiveRunnerConfig CONFIG =
            new HiveRunnerConfig() {
                {
                    // catalog lock needs txn manager
                    // hive-3.x requires a proper txn manager to create ACID table
                    getHiveConfSystemOverride()
                            .put(HIVE_TXN_MANAGER.varname, DbTxnManager.class.getName());
                    getHiveConfSystemOverride().put(HIVE_SUPPORT_CONCURRENCY.varname, "true");
                    // tell TxnHandler to prepare txn DB
                    getHiveConfSystemOverride().put(HIVE_IN_TEST.varname, "true");
                }
            };

    @Before
    public void before() throws Exception {
        hiveShell.execute("CREATE DATABASE IF NOT EXISTS test_db");
        hiveShell.execute("USE test_db");
        hiveShell.execute("CREATE TABLE hive_table ( a INT, b STRING )");
        hiveShell.execute("INSERT INTO hive_table VALUES (100, 'Hive'), (200, 'Table')");

        path = folder.newFolder().toURI().toString();
        EnvironmentSettings settings = EnvironmentSettings.newInstance().inBatchMode().build();
        tEnv = TableEnvironmentImpl.create(settings);
        tEnv.executeSql(
                        String.join(
                                "\n",
                                "CREATE CATALOG my_hive WITH (",
                                "  'type' = 'table-store',",
                                "  'metastore' = 'hive',",
                                "  'uri' = '',",
                                "  'warehouse' = '" + path + "',",
                                "  'lock.enabled' = 'true'",
                                ")"))
                .await();
        tEnv.executeSql("USE CATALOG my_hive").await();
        tEnv.executeSql("USE test_db").await();
    }

    @After
    public void after() {
        hiveShell.execute("DROP DATABASE IF EXISTS test_db CASCADE");
        hiveShell.execute("DROP DATABASE IF EXISTS test_db2 CASCADE");
    }

    @Test
    public void testDatabaseOperations() throws Exception {
        // create database
        tEnv.executeSql("CREATE DATABASE test_db2").await();
        Assert.assertEquals(
                Arrays.asList(Row.of("default"), Row.of("test_db"), Row.of("test_db2")),
                collect("SHOW DATABASES"));
        tEnv.executeSql("CREATE DATABASE IF NOT EXISTS test_db2").await();
        try {
            tEnv.executeSql("CREATE DATABASE test_db2").await();
            Assert.fail("No exception is thrown");
        } catch (Throwable t) {
            ExceptionUtils.assertThrowableWithMessage(
                    t, "Database test_db2 already exists in Catalog my_hive");
        }

        // drop database
        tEnv.executeSql("DROP DATABASE test_db2").await();
        Assert.assertEquals(
                Arrays.asList(Row.of("default"), Row.of("test_db")), collect("SHOW DATABASES"));
        tEnv.executeSql("DROP DATABASE IF EXISTS test_db2").await();
        try {
            tEnv.executeSql("DROP DATABASE test_db2").await();
            Assert.fail("No exception is thrown");
        } catch (Throwable t) {
            ExceptionUtils.assertThrowableWithMessage(
                    t, "Database test_db2 does not exist in Catalog my_hive");
        }

        // drop non-empty database
        tEnv.executeSql("CREATE DATABASE test_db2").await();
        tEnv.executeSql("USE test_db2").await();
        tEnv.executeSql("CREATE TABLE t ( a INT, b STRING ) WITH ( 'file.format' = 'avro' )")
                .await();
        tEnv.executeSql("INSERT INTO t VALUES (1, 'Hi'), (2, 'Hello')").await();
        Path tablePath = new Path(path, "test_db2.db/t");
        Assert.assertTrue(tablePath.getFileSystem().exists(tablePath));
        try {
            tEnv.executeSql("DROP DATABASE test_db2").await();
            Assert.fail("No exception is thrown");
        } catch (Throwable t) {
            ExceptionUtils.assertThrowableWithMessage(
                    t, "Database test_db2 in catalog my_hive is not empty");
        }
        tEnv.executeSql("DROP DATABASE test_db2 CASCADE").await();
        Assert.assertEquals(
                Arrays.asList(Row.of("default"), Row.of("test_db")), collect("SHOW DATABASES"));
        Assert.assertFalse(tablePath.getFileSystem().exists(tablePath));
    }

    @Test
    public void testTableOperations() throws Exception {
        // create table
        tEnv.executeSql("CREATE TABLE t ( a INT, b STRING ) WITH ( 'file.format' = 'avro' )")
                .await();
        tEnv.executeSql("CREATE TABLE s ( a INT, b STRING ) WITH ( 'file.format' = 'avro' )")
                .await();
        Assert.assertEquals(Arrays.asList(Row.of("s"), Row.of("t")), collect("SHOW TABLES"));
        tEnv.executeSql(
                        "CREATE TABLE IF NOT EXISTS s ( a INT, b STRING ) WITH ( 'file.format' = 'avro' )")
                .await();
        try {
            tEnv.executeSql("CREATE TABLE s ( a INT, b STRING ) WITH ( 'file.format' = 'avro' )")
                    .await();
            Assert.fail("No exception is thrown");
        } catch (Throwable t) {
            ExceptionUtils.assertThrowableWithMessage(
                    t, "Table (or view) test_db.s already exists in Catalog my_hive");
        }

        // drop table
        tEnv.executeSql("INSERT INTO s VALUES (1, 'Hi'), (2, 'Hello')").await();
        Path tablePath = new Path(path, "test_db.db/s");
        Assert.assertTrue(tablePath.getFileSystem().exists(tablePath));
        tEnv.executeSql("DROP TABLE s").await();
        Assert.assertEquals(Collections.singletonList(Row.of("t")), collect("SHOW TABLES"));
        Assert.assertFalse(tablePath.getFileSystem().exists(tablePath));
        tEnv.executeSql("DROP TABLE IF EXISTS s").await();
        try {
            tEnv.executeSql("DROP TABLE s").await();
            Assert.fail("No exception is thrown");
        } catch (Throwable t) {
            ExceptionUtils.assertThrowableWithMessage(
                    t, "Table with identifier 'my_hive.test_db.s' does not exist");
        }
        try {
            tEnv.executeSql("DROP TABLE hive_table").await();
            Assert.fail("No exception is thrown");
        } catch (Throwable t) {
            ExceptionUtils.assertThrowableWithMessage(
                    t, "Table test_db.hive_table is not a table store table");
        }

        // alter table
        tEnv.executeSql("ALTER TABLE t SET ( 'manifest.target-file-size' = '16MB' )").await();
        List<Row> actual = collect("SHOW CREATE TABLE t");
        Assert.assertEquals(1, actual.size());
        Assert.assertTrue(
                actual.get(0)
                        .getField(0)
                        .toString()
                        .contains("'manifest.target-file-size' = '16MB'"));
        try {
            tEnv.executeSql("ALTER TABLE s SET ( 'manifest.target-file-size' = '16MB' )").await();
            Assert.fail("No exception is thrown");
        } catch (Throwable t) {
            ExceptionUtils.assertThrowableWithMessage(
                    t, "Table `my_hive`.`test_db`.`s` doesn't exist or is a temporary table");
        }
        try {
            tEnv.executeSql("ALTER TABLE hive_table SET ( 'manifest.target-file-size' = '16MB' )")
                    .await();
            Assert.fail("No exception is thrown");
        } catch (Throwable t) {
            ExceptionUtils.assertThrowableWithMessage(
                    t, "Table test_db.hive_table is not a table store table");
        }
    }

    @Test
    public void testCreateExternalTable() throws Exception {
        tEnv.executeSql(
                        String.join(
                                "\n",
                                "CREATE CATALOG my_hive_external WITH (",
                                "  'type' = 'table-store',",
                                "  'metastore' = 'hive',",
                                "  'uri' = '',",
                                "  'warehouse' = '" + path + "',",
                                "  'lock.enabled' = 'true',",
                                "  'table.type' = 'EXTERNAL'",
                                ")"))
                .await();
        tEnv.executeSql("USE CATALOG my_hive_external").await();
        tEnv.executeSql("USE test_db").await();
        tEnv.executeSql("CREATE TABLE t ( a INT, b STRING ) WITH ( 'file.format' = 'avro' )")
                .await();
        Assert.assertTrue(
                hiveShell
                        .executeQuery("DESC FORMATTED t")
                        .contains("Table Type:         \tEXTERNAL_TABLE      \tNULL"));
        tEnv.executeSql("DROP TABLE t").await();
        Path tablePath = new Path(path, "test_db.db/t");
        Assert.assertTrue(tablePath.getFileSystem().exists(tablePath));
    }

    @Test
    public void testFlinkWriteAndHiveRead() throws Exception {
        tEnv.executeSql("CREATE TABLE t ( a INT, b STRING ) WITH ( 'file.format' = 'avro' )")
                .await();
        tEnv.executeSql("INSERT INTO t VALUES (1, 'Hi'), (2, 'Hello')").await();
        Assert.assertEquals(
                Arrays.asList("1\tHi", "2\tHello"),
                hiveShell.executeQuery("SELECT * FROM t ORDER BY a"));

        try {
            tEnv.executeSql("INSERT INTO hive_table VALUES (1, 'Hi'), (2, 'Hello')").await();
            Assert.fail("No exception is thrown");
        } catch (Throwable t) {
            ExceptionUtils.assertThrowableWithMessage(
                    t, "Table test_db.hive_table is not a table store table");
        }
    }

    @Test
    public void testRenameTable() throws Exception {
        tEnv.executeSql("CREATE TABLE t1 (a INT)").await();
        tEnv.executeSql("CREATE TABLE t2 (a INT)").await();
        tEnv.executeSql("INSERT INTO t1 SELECT 1");
        // the source table do not exist.
        assertThatThrownBy(() -> tEnv.executeSql("ALTER TABLE t3 RENAME TO t4"))
                .hasMessage(
                        "Table `my_hive`.`test_db`.`t3` doesn't exist or is a temporary table.");

        // the target table has existed.
        assertThatThrownBy(() -> tEnv.executeSql("ALTER TABLE t1 RENAME TO t2"))
                .hasMessage(
                        "Could not execute ALTER TABLE my_hive.test_db.t1 RENAME TO my_hive.test_db.t2");

        tEnv.executeSql("ALTER TABLE t1 RENAME TO t3").await();
        List<String> tables = hiveShell.executeQuery("SHOW TABLES");
        Assert.assertTrue(tables.contains("t3"));
        Assert.assertFalse(tables.contains("t1"));

        Identifier identifier = new Identifier("test_db", "t3");
        Catalog catalog =
                ((FlinkCatalog) tEnv.getCatalog(tEnv.getCurrentCatalog()).get()).catalog();
        org.apache.flink.table.store.fs.Path tablePath = catalog.getTableLocation(identifier);
        Assert.assertEquals(tablePath.toString(), path + "test_db.db" + File.separator + "t3");

        // TODO: the hiverunner (4.0) has a bug ,it can not rename the table path correctly ,
        // we should upgrade it to the 6.0 later ,and  update the test case for query.
        assertThatThrownBy(() -> tEnv.executeSql("SELECT * FROM t3"))
                .hasMessageContaining("SQL validation failed. There is no table stored in");
    }

    @Test
    public void testHiveLock() throws InterruptedException {
        tEnv.executeSql("CREATE TABLE t (a INT)");
        CatalogLock.Factory lockFactory =
                ((FlinkCatalog) tEnv.getCatalog(tEnv.getCurrentCatalog()).get())
                        .catalog()
                        .lockFactory()
                        .get();

        AtomicInteger count = new AtomicInteger(0);
        List<Thread> threads = new ArrayList<>();
        Callable<Void> unsafeIncrement =
                () -> {
                    int nextCount = count.get() + 1;
                    Thread.sleep(1);
                    count.set(nextCount);
                    return null;
                };
        for (int i = 0; i < 10; i++) {
            Thread thread =
                    new Thread(
                            () -> {
                                CatalogLock lock = lockFactory.create();
                                for (int j = 0; j < 10; j++) {
                                    try {
                                        lock.runWithLock("test_db", "t", unsafeIncrement);
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                }
                            });
            thread.start();
            threads.add(thread);
        }

        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(count.get()).isEqualTo(100);
    }

    @Test
    public void testUpperCase() {
        assertThatThrownBy(
                        () ->
                                tEnv.executeSql(
                                                "CREATE TABLE T ( a INT, b STRING ) WITH ( 'file.format' = 'avro' )")
                                        .await())
                .hasRootCauseMessage(
                        String.format("Table name[%s] cannot contain upper case", "T"));

        assertThatThrownBy(
                        () ->
                                tEnv.executeSql(
                                                "CREATE TABLE t (A INT, b STRING, C STRING) WITH ( 'file.format' = 'avro')")
                                        .await())
                .hasRootCauseMessage(
                        String.format("Field names %s cannot contain upper case", "[A, C]"));
    }

    private List<Row> collect(String sql) throws Exception {
        List<Row> result = new ArrayList<>();
        try (CloseableIterator<Row> it = tEnv.executeSql(sql).collect()) {
            while (it.hasNext()) {
                result.add(it.next());
            }
        }
        return result;
    }
}
