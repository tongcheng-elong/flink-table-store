/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.store.codegen.codesplit;

import org.apache.flink.table.store.codegen.codesplit.JavaParser.StatementContext;

/** Simple parser that counts combined number of 'return', 'continue' and 'break' kay words. */
public class ReturnAndJumpCounter extends JavaParserBaseVisitor<Void> {

    private int counter = 0;

    @Override
    public Void visitStatement(StatementContext ctx) {
        if (ctx.RETURN() != null || ctx.BREAK() != null || ctx.CONTINUE() != null) {
            counter++;
        }
        return visitChildren(ctx);
    }

    public int getCounter() {
        return counter;
    }
}
