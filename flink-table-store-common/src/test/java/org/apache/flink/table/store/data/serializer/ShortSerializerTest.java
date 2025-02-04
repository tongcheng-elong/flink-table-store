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

package org.apache.flink.table.store.data.serializer;

import java.util.Random;

/** Test for {@link ShortSerializer}. */
public class ShortSerializerTest extends SerializerTestBase<Short> {

    @Override
    protected Serializer<Short> createSerializer() {
        return ShortSerializer.INSTANCE;
    }

    @Override
    protected boolean deepEquals(Short t1, Short t2) {
        return t1.equals(t2);
    }

    @Override
    protected Short[] getTestData() {
        Random rnd = new Random();
        short rndShort = (short) rnd.nextInt();

        return new Short[] {
            0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, rndShort, (short) -rndShort
        };
    }
}
