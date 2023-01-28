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

package org.apache.flink.table.store.format.orc;

import org.apache.flink.core.fs.Path;
import org.apache.flink.table.store.data.InternalRow;
import org.apache.flink.table.store.file.utils.RecordReader;
import org.apache.flink.table.store.file.utils.RecordReaderUtils;
import org.apache.flink.table.store.format.orc.filter.OrcFilters;
import org.apache.flink.table.store.types.DataType;
import org.apache.flink.table.store.types.DataTypes;
import org.apache.flink.table.store.types.DecimalType;
import org.apache.flink.table.store.types.RowType;
import org.apache.flink.table.store.utils.DecimalUtils;

import org.apache.hadoop.conf.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** Test for {@link OrcReaderFactory}. */
class OrcReaderFactoryTest {

    /** Small batch size for test more boundary conditions. */
    protected static final int BATCH_SIZE = 9;

    private static final RowType FLAT_FILE_TYPE =
            RowType.builder()
                    .fields(
                            new DataType[] {
                                DataTypes.INT(),
                                DataTypes.STRING(),
                                DataTypes.STRING(),
                                DataTypes.STRING(),
                                DataTypes.INT(),
                                DataTypes.STRING(),
                                DataTypes.INT(),
                                DataTypes.INT(),
                                DataTypes.INT()
                            },
                            new String[] {
                                "_col0", "_col1", "_col2", "_col3", "_col4", "_col5", "_col6",
                                "_col7", "_col8"
                            })
                    .build();

    private static final RowType DECIMAL_FILE_TYPE =
            RowType.builder()
                    .fields(new DataType[] {new DecimalType(10, 5)}, new String[] {"_col0"})
                    .build();

    private static Path flatFile;
    private static Path decimalFile;

    @BeforeAll
    static void setupFiles(@TempDir java.nio.file.Path tmpDir) {
        flatFile = copyFileFromResource("test-data-flat.orc", tmpDir.resolve("test-data-flat.orc"));
        decimalFile =
                copyFileFromResource(
                        "test-data-decimal.orc", tmpDir.resolve("test-data-decimal.orc"));
    }

    @Test
    void testReadFileInSplits() throws IOException {
        OrcReaderFactory format = createFormat(FLAT_FILE_TYPE, new int[] {0, 1});

        AtomicInteger cnt = new AtomicInteger(0);
        AtomicLong totalF0 = new AtomicLong(0);

        forEach(
                format,
                flatFile,
                row -> {
                    assertThat(row.isNullAt(0)).isFalse();
                    assertThat(row.isNullAt(1)).isFalse();
                    totalF0.addAndGet(row.getInt(0));
                    assertThat(row.getString(1).toString()).isNotNull();
                    cnt.incrementAndGet();
                });

        // check that all rows have been read
        assertThat(cnt.get()).isEqualTo(1920800);
        assertThat(totalF0.get()).isEqualTo(1844737280400L);
    }

    @Test
    void testReadFileWithSelectFields() throws IOException {
        OrcReaderFactory format = createFormat(FLAT_FILE_TYPE, new int[] {2, 0, 1});

        AtomicInteger cnt = new AtomicInteger(0);
        AtomicLong totalF0 = new AtomicLong(0);

        forEach(
                format,
                flatFile,
                row -> {
                    assertThat(row.isNullAt(0)).isFalse();
                    assertThat(row.isNullAt(1)).isFalse();
                    assertThat(row.isNullAt(2)).isFalse();
                    assertThat(row.getString(0).toString()).isNotNull();
                    totalF0.addAndGet(row.getInt(1));
                    assertThat(row.getString(2).toString()).isNotNull();
                    cnt.incrementAndGet();
                });

        // check that all rows have been read
        assertThat(cnt.get()).isEqualTo(1920800);
        assertThat(totalF0.get()).isEqualTo(1844737280400L);
    }

    @Test
    void testReadDecimalTypeFile() throws IOException {
        OrcReaderFactory format = createFormat(DECIMAL_FILE_TYPE, new int[] {0});

        AtomicInteger cnt = new AtomicInteger(0);
        AtomicInteger nullCount = new AtomicInteger(0);

        forEach(
                format,
                decimalFile,
                row -> {
                    if (cnt.get() == 0) {
                        // validate first row
                        assertThat(row).isNotNull();
                        assertThat(row.getFieldCount()).isEqualTo(1);
                        assertThat(row.getDecimal(0, 10, 5))
                                .isEqualTo(DecimalUtils.castFrom(-1000.5d, 10, 5));
                    } else {
                        if (!row.isNullAt(0)) {
                            assertThat(row.getDecimal(0, 10, 5)).isNotNull();
                        } else {
                            nullCount.incrementAndGet();
                        }
                    }
                    cnt.incrementAndGet();
                });

        assertThat(cnt.get()).isEqualTo(6000);
        assertThat(nullCount.get()).isEqualTo(2000);
    }

    protected OrcReaderFactory createFormat(RowType formatType, int[] selectedFields) {
        return createFormat(formatType, selectedFields, new ArrayList<>());
    }

    protected OrcReaderFactory createFormat(
            RowType formatType,
            int[] selectedFields,
            List<OrcFilters.Predicate> conjunctPredicates) {
        return new OrcReaderFactory(
                new Configuration(), formatType, selectedFields, conjunctPredicates, BATCH_SIZE);
    }

    private RecordReader<InternalRow> createReader(OrcReaderFactory format, Path split)
            throws IOException {
        return format.createReader(split);
    }

    private void forEach(OrcReaderFactory format, Path file, Consumer<InternalRow> action)
            throws IOException {
        RecordReaderUtils.forEachRemaining(format.createReader(file), action);
    }

    static Path copyFileFromResource(String resourceName, java.nio.file.Path file) {
        try (InputStream resource =
                OrcReaderFactoryTest.class
                        .getClassLoader()
                        .getResource(resourceName)
                        .openStream()) {
            Files.createDirectories(file.getParent());
            Files.copy(resource, file);
            return new Path(file.toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
