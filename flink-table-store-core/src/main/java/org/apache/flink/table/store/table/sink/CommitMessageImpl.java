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

package org.apache.flink.table.store.table.sink;

import org.apache.flink.table.store.data.BinaryRow;
import org.apache.flink.table.store.file.io.CompactIncrement;
import org.apache.flink.table.store.file.io.NewFilesIncrement;
import org.apache.flink.table.store.io.DataInputViewStreamWrapper;
import org.apache.flink.table.store.io.DataOutputViewStreamWrapper;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Objects;

import static org.apache.flink.table.store.file.utils.SerializationUtils.deserializedBytes;
import static org.apache.flink.table.store.file.utils.SerializationUtils.serializeBytes;

/** File committable for sink. */
public class CommitMessageImpl implements CommitMessage {

    private static final long serialVersionUID = 1L;

    private static final ThreadLocal<CommitMessageSerializer> CACHE =
            ThreadLocal.withInitial(CommitMessageSerializer::new);

    private transient BinaryRow partition;
    private transient int bucket;
    private transient NewFilesIncrement newFilesIncrement;
    private transient CompactIncrement compactIncrement;

    public CommitMessageImpl(
            BinaryRow partition,
            int bucket,
            NewFilesIncrement newFilesIncrement,
            CompactIncrement compactIncrement) {
        this.partition = partition;
        this.bucket = bucket;
        this.newFilesIncrement = newFilesIncrement;
        this.compactIncrement = compactIncrement;
    }

    @Override
    public BinaryRow partition() {
        return partition;
    }

    @Override
    public int bucket() {
        return bucket;
    }

    public NewFilesIncrement newFilesIncrement() {
        return newFilesIncrement;
    }

    public CompactIncrement compactIncrement() {
        return compactIncrement;
    }

    public boolean isEmpty() {
        return newFilesIncrement.isEmpty() && compactIncrement.isEmpty();
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        CommitMessageSerializer serializer = CACHE.get();
        out.writeInt(serializer.getVersion());
        serializeBytes(new DataOutputViewStreamWrapper(out), serializer.serialize(this));
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        int version = in.readInt();
        byte[] bytes = deserializedBytes(new DataInputViewStreamWrapper(in));
        CommitMessageImpl message = (CommitMessageImpl) CACHE.get().deserialize(version, bytes);
        this.partition = message.partition;
        this.bucket = message.bucket;
        this.newFilesIncrement = message.newFilesIncrement;
        this.compactIncrement = message.compactIncrement;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        CommitMessageImpl that = (CommitMessageImpl) o;
        return bucket == that.bucket
                && Objects.equals(partition, that.partition)
                && Objects.equals(newFilesIncrement, that.newFilesIncrement)
                && Objects.equals(compactIncrement, that.compactIncrement);
    }

    @Override
    public int hashCode() {
        return Objects.hash(partition, bucket, newFilesIncrement, compactIncrement);
    }

    @Override
    public String toString() {
        return String.format(
                "FileCommittable {"
                        + "partition = %s, "
                        + "bucket = %d, "
                        + "newFilesIncrement = %s, "
                        + "compactIncrement = %s}",
                partition, bucket, newFilesIncrement, compactIncrement);
    }
}
