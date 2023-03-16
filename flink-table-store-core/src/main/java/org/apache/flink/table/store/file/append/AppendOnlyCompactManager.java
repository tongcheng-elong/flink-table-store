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

package org.apache.flink.table.store.file.append;

import org.apache.flink.table.store.annotation.VisibleForTesting;
import org.apache.flink.table.store.file.compact.CompactFutureManager;
import org.apache.flink.table.store.file.compact.CompactResult;
import org.apache.flink.table.store.file.compact.CompactTask;
import org.apache.flink.table.store.file.io.DataFileMeta;
import org.apache.flink.table.store.file.io.DataFilePathFactory;
import org.apache.flink.table.store.fs.FileIO;
import org.apache.flink.table.store.utils.Preconditions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

/** Compact manager for {@link org.apache.flink.table.store.file.AppendOnlyFileStore}. */
public class AppendOnlyCompactManager extends CompactFutureManager {

    private final FileIO fileIO;
    private final ExecutorService executor;
    private final LinkedList<DataFileMeta> toCompact;
    private final int minFileNum;
    private final int maxFileNum;
    private final long targetFileSize;
    private final CompactRewriter rewriter;
    private final DataFilePathFactory pathFactory;

    public AppendOnlyCompactManager(
            FileIO fileIO,
            ExecutorService executor,
            LinkedList<DataFileMeta> toCompact,
            int minFileNum,
            int maxFileNum,
            long targetFileSize,
            CompactRewriter rewriter,
            DataFilePathFactory pathFactory) {
        this.fileIO = fileIO;
        this.executor = executor;
        this.toCompact = toCompact;
        this.minFileNum = minFileNum;
        this.maxFileNum = maxFileNum;
        this.targetFileSize = targetFileSize;
        this.rewriter = rewriter;
        this.pathFactory = pathFactory;
    }

    @Override
    public void triggerCompaction(boolean fullCompaction) {
        if (fullCompaction) {
            triggerFullCompaction();
        } else {
            triggerCompactionWithBestEffort();
        }
    }

    private void triggerFullCompaction() {
        Preconditions.checkState(
                taskFuture == null,
                "A compaction task is still running while the user "
                        + "forces a new compaction. This is unexpected.");
        taskFuture =
                executor.submit(
                        new AppendOnlyCompactManager.IterativeCompactTask(
                                fileIO,
                                toCompact,
                                targetFileSize,
                                minFileNum,
                                maxFileNum,
                                rewriter,
                                pathFactory));
    }

    private void triggerCompactionWithBestEffort() {
        if (taskFuture != null) {
            return;
        }
        pickCompactBefore()
                .ifPresent(
                        (inputs) ->
                                taskFuture =
                                        executor.submit(new AutoCompactTask(inputs, rewriter)));
    }

    @Override
    public boolean shouldWaitCompaction() {
        return false;
    }

    @Override
    public void addNewFile(DataFileMeta file) {
        toCompact.add(file);
    }

    /** Finish current task, and update result files to {@link #toCompact}. */
    @Override
    public Optional<CompactResult> getCompactionResult(boolean blocking)
            throws ExecutionException, InterruptedException {
        Optional<CompactResult> result = innerGetCompactionResult(blocking);
        result.ifPresent(
                r -> {
                    if (!r.after().isEmpty()) {
                        // if the last compacted file is still small,
                        // add it back to the head
                        DataFileMeta lastFile = r.after().get(r.after().size() - 1);
                        if (lastFile.fileSize() < targetFileSize) {
                            toCompact.offerFirst(lastFile);
                        }
                    }
                });
        return result;
    }

    @VisibleForTesting
    Optional<List<DataFileMeta>> pickCompactBefore() {
        return pick(toCompact, targetFileSize, minFileNum, maxFileNum);
    }

    private static Optional<List<DataFileMeta>> pick(
            LinkedList<DataFileMeta> toCompact,
            long targetFileSize,
            int minFileNum,
            int maxFileNum) {
        if (toCompact.isEmpty()) {
            return Optional.empty();
        }

        long totalFileSize = 0L;
        int fileNum = 0;
        LinkedList<DataFileMeta> candidates = new LinkedList<>();

        while (!toCompact.isEmpty()) {
            DataFileMeta file = toCompact.pollFirst();
            candidates.add(file);
            totalFileSize += file.fileSize();
            fileNum++;
            if ((totalFileSize >= targetFileSize && fileNum >= minFileNum)
                    || fileNum >= maxFileNum) {
                return Optional.of(candidates);
            } else if (totalFileSize >= targetFileSize) {
                // let pointer shift one pos to right
                DataFileMeta removed = candidates.pollFirst();
                assert removed != null;
                totalFileSize -= removed.fileSize();
                fileNum--;
            }
        }
        toCompact.addAll(candidates);
        return Optional.empty();
    }

    @VisibleForTesting
    LinkedList<DataFileMeta> getToCompact() {
        return toCompact;
    }

    @Override
    public void close() throws IOException {}

    /**
     * A {@link CompactTask} impl for full compaction of append-only table.
     *
     * <p>This task accepts a pre-scanned file list as input and pick the candidate files to compact
     * iteratively until reach the end of the input. There might be multiple times of rewrite
     * happens during one task.
     */
    public static class IterativeCompactTask extends CompactTask {

        private final FileIO fileIO;
        private final long targetFileSize;
        private final int minFileNum;
        private final int maxFileNum;
        private final CompactRewriter rewriter;
        private final DataFilePathFactory factory;

        public IterativeCompactTask(
                FileIO fileIO,
                List<DataFileMeta> inputs,
                long targetFileSize,
                int minFileNum,
                int maxFileNum,
                CompactRewriter rewriter,
                DataFilePathFactory factory) {
            super(inputs);
            this.fileIO = fileIO;
            this.targetFileSize = targetFileSize;
            this.minFileNum = minFileNum;
            this.maxFileNum = maxFileNum;
            this.rewriter = rewriter;
            this.factory = factory;
        }

        @Override
        protected CompactResult doCompact(List<DataFileMeta> inputs) throws Exception {
            LinkedList<DataFileMeta> toCompact = new LinkedList<>(inputs);
            Set<DataFileMeta> compactBefore = new LinkedHashSet<>();
            List<DataFileMeta> compactAfter = new ArrayList<>();
            while (!toCompact.isEmpty()) {
                Optional<List<DataFileMeta>> candidates =
                        AppendOnlyCompactManager.pick(
                                toCompact, targetFileSize, minFileNum, maxFileNum);
                if (candidates.isPresent()) {
                    List<DataFileMeta> before = candidates.get();
                    compactBefore.addAll(before);
                    List<DataFileMeta> after = rewriter.rewrite(before);
                    compactAfter.addAll(after);
                    DataFileMeta lastFile = after.get(after.size() - 1);
                    if (lastFile.fileSize() < targetFileSize) {
                        toCompact.offerFirst(lastFile);
                    }
                } else {
                    break;
                }
            }
            // remove and delete intermediate files
            Iterator<DataFileMeta> afterIterator = compactAfter.iterator();
            while (afterIterator.hasNext()) {
                DataFileMeta file = afterIterator.next();
                if (compactBefore.contains(file)) {
                    compactBefore.remove(file);
                    afterIterator.remove();
                    delete(file);
                }
            }
            return result(new ArrayList<>(compactBefore), compactAfter);
        }

        @VisibleForTesting
        void delete(DataFileMeta tmpFile) {
            fileIO.deleteQuietly(factory.toPath(tmpFile.fileName()));
        }
    }

    /**
     * A {@link CompactTask} impl for append-only table auto-compaction.
     *
     * <p>This task accepts an already-picked candidate to perform one-time rewrite. And for the
     * rest of input files, it is the duty of {@link AppendOnlyWriter} to invoke the next time
     * compaction.
     */
    public static class AutoCompactTask extends CompactTask {

        private final CompactRewriter rewriter;

        public AutoCompactTask(List<DataFileMeta> toCompact, CompactRewriter rewriter) {
            super(toCompact);
            this.rewriter = rewriter;
        }

        @Override
        protected CompactResult doCompact(List<DataFileMeta> inputs) throws Exception {
            return result(inputs, rewriter.rewrite(inputs));
        }
    }

    private static CompactResult result(List<DataFileMeta> before, List<DataFileMeta> after) {
        return new CompactResult() {
            @Override
            public List<DataFileMeta> before() {
                return before;
            }

            @Override
            public List<DataFileMeta> after() {
                return after;
            }
        };
    }

    /** Compact rewriter for append-only table. */
    public interface CompactRewriter {
        List<DataFileMeta> rewrite(List<DataFileMeta> compactBefore) throws Exception;
    }
}
