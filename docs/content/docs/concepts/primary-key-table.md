---
title: "Primary Key Table"
weight: 6
type: docs
aliases:
- /concepts/primary-key-table.html
---
<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Primary Key Table

Changelog table is the default table type when creating a table. Users can insert, update or delete records in the table.

Primary keys are a set of columns that are unique for each record. Table Store imposes an ordering of data, which means the system will sort the primary key within each bucket. Using this feature, users can achieve high performance by adding filter conditions on the primary key.

By [defining primary keys]({{< ref "docs/how-to/creating-tables#tables-with-primary-keys" >}}) on a changelog table, users can access the following features.

## Merge Engines

When Table Store sink receives two or more records with the same primary keys, it will merge them into one record to keep primary keys unique. By specifying the `merge-engine` table property, users can choose how records are merged together.

{{< hint info >}}
Set `table.exec.sink.upsert-materialize` to `NONE` always in Flink SQL TableConfig, sink upsert-materialize may
result in strange behavior. When the input is out of order, we recommend that you use
[Sequence Field]({{< ref "docs/concepts/primary-key-table#sequence-field" >}}) to correct disorder.
{{< /hint >}}

### Deduplicate

`deduplicate` merge engine is the default merge engine. Table Store will only keep the latest record and throw away other records with the same primary keys.

Specifically, if the latest record is a `DELETE` record, all records with the same primary keys will be deleted.

### Partial Update

By specifying `'merge-engine' = 'partial-update'`, users can set columns of a record across multiple updates and finally get a complete record. Specifically, value fields are updated to the latest data one by one under the same primary key, but null values are not overwritten.

For example, let's say Table Store receives three records:
- `<1, 23.0, 10, NULL>`-
- `<1, NULL, NULL, 'This is a book'>`
- `<1, 25.2, NULL, NULL>`

If the first column is the primary key. The final result will be `<1, 25.2, 10, 'This is a book'>`.

{{< hint info >}}
For streaming queries, `partial-update` merge engine must be used together with `lookup` or `full-compaction` [changelog producer]({{< ref "docs/concepts/primary-key-table#changelog-producers" >}}).
{{< /hint >}}

{{< hint info >}}
Partial cannot receive `DELETE` messages because the behavior cannot be defined. You can configure `partial-update.ignore-delete` to ignore `DELETE` messages.
{{< /hint >}}

### Aggregation

Sometimes users only care about aggregated results. The `aggregation` merge engine aggregates each value field with the latest data one by one under the same primary key according to the aggregate function.

Each field not part of the primary keys can be given an aggregate function, specified by the `fields.<field-name>.aggregate-function` table property, otherwise it will use `last_non_null_value` aggregation as default. For example, consider the following table definition.

{{< tabs "aggregation-merge-engine-example" >}}

{{< tab "Flink" >}}

```sql
CREATE TABLE MyTable (
    product_id BIGINT,
    price DOUBLE,
    sales BIGINT,
    PRIMARY KEY (product_id) NOT ENFORCED
) WITH (
    'merge-engine' = 'aggregation',
    'fields.price.aggregate-function' = 'max',
    'fields.sales.aggregate-function' = 'sum'
);
```

{{< /tab >}}

{{< /tabs >}}

Field `price` will be aggregated by the `max` function, and field `sales` will be aggregated by the `sum` function. Given two input records `<1, 23.0, 15>` and `<1, 30.2, 20>`, the final result will be `<1, 30.2, 35>`.

Current supported aggregate functions and data types are:

* `sum`: supports DECIMAL, TINYINT, SMALLINT, INTEGER, BIGINT, FLOAT and DOUBLE.
* `min`/`max`: support DECIMAL, TINYINT, SMALLINT, INTEGER, BIGINT, FLOAT, DOUBLE, DATE, TIME, TIMESTAMP and TIMESTAMP_LTZ.
* `last_value` / `last_non_null_value`: support all data types.
* `listagg`: supports STRING data type.
* `bool_and` / `bool_or`: support BOOLEAN data type.

Only `sum` supports retraction (`UPDATE_BEFORE` and `DELETE`), others aggregate functions do not support retraction.
If you allow some functions to ignore retraction messages, you can configure:
`'fields.${field_name}.ignore-retract'='true'`.

{{< hint info >}}
For streaming queries, `aggregation` merge engine must be used together with `lookup` or `full-compaction` [changelog producer]({{< ref "docs/concepts/primary-key-table#changelog-producers" >}}).
{{< /hint >}}

## Changelog Producers

Streaming queries will continuously produce latest changes. These changes can come from the underlying table files or from an [external log system]({{< ref "docs/concepts/external-log-systems" >}}) like Kafka. Compared to the external log system, changes from table files have lower cost but higher latency (depending on how often snapshots are created).

By specifying the `changelog-producer` table property when creating the table, users can choose the pattern of changes produced from files.

{{< hint info >}}

The `changelog-producer` table property only affects changelog from files. It does not affect the external log system.

{{< /hint >}}

### None

By default, no extra changelog producer will be applied to the writer of table. Table Store source can only see the merged changes across snapshots, like what keys are removed and what are the new values of some keys.

However, these merged changes cannot form a complete changelog, because we can't read the old values of the keys directly from them. Merged changes require the consumers to "remember" the values of each key and to rewrite the values without seeing the old ones. Some consumers, however, need the old values to ensure correctness or efficiency.

Consider a consumer which calculates the sum on some grouping keys (might not be equal to the primary keys). If the consumer only sees a new value `5`, it cannot determine what values should be added to the summing result. For example, if the old value is `4`, it should add `1` to the result. But if the old value is `6`, it should in turn subtract `1` from the result. Old values are important for these types of consumers.

To conclude, `none` changelog producers are best suited for consumers such as a database system. Flink also has a built-in "normalize" operator which persists the values of each key in states. As one can easily tell, this operator will be very costly and should be avoided.

{{< img src="/img/changelog-producer-none.png">}}

### Input

By specifying `'changelog-producer' = 'input'`, Table Store writers rely on their inputs as a source of complete changelog. All input records will be saved in separated [changelog files]({{< ref "docs/concepts/file-layouts" >}}) and will be given to the consumers by Table Store sources.

`input` changelog producer can be used when Table Store writers' inputs are complete changelog, such as from a database CDC, or generated by Flink stateful computation.

{{< img src="/img/changelog-producer-input.png">}}

### Lookup

If your input can’t produce a complete changelog but you still want to get rid of the costly normalized operator, you may consider using the `'lookup'` changelog producer.

By specifying `'changelog-producer' = 'lookup'`, Table Store will generate changelog through `'lookup'` before committing the data writing.

{{< img src="/img/changelog-producer-lookup.png">}}

Lookup will cache data on the memory and local disk, you can use the following options to tune performance:

<table class="table table-bordered">
    <thead>
    <tr>
      <th class="text-left" style="width: 20%">Option</th>
      <th class="text-left" style="width: 5%">Default</th>
      <th class="text-left" style="width: 10%">Type</th>
      <th class="text-left" style="width: 60%">Description</th>
    </tr>
    </thead>
    <tbody>
    <tr>
        <td><h5>lookup.cache-file-retention</h5></td>
        <td style="word-wrap: break-word;">1 h</td>
        <td>Duration</td>
        <td>The cached files retention time for lookup. After the file expires, if there is a need for access, it will be re-read from the DFS to build an index on the local disk.</td>
    </tr>
    <tr>
        <td><h5>lookup.cache-max-disk-size</h5></td>
        <td style="word-wrap: break-word;">unlimited</td>
        <td>MemorySize</td>
        <td>Max disk size for lookup cache, you can use this option to limit the use of local disks.</td>
    </tr>
    <tr>
        <td><h5>lookup.cache-max-memory-size</h5></td>
        <td style="word-wrap: break-word;">256 mb</td>
        <td>MemorySize</td>
        <td>Max memory size for lookup cache.</td>
    </tr>
    </tbody>
</table>

### Full Compaction

If you think the resource consumption of 'lookup' is too large, you can consider using 'full-compaction' changelog producer,
which can decouple data writing and changelog generation, and is more suitable for scenarios with high latency (For example, 10 minutes).

By specifying `'changelog-producer' = 'full-compaction'`, Table Store will compare the results between full compactions and produce the differences as changelog. The latency of changelog is affected by the frequency of full compactions.

By specifying `changelog-producer.compaction-interval` table property (default value `0s`), users can define the maximum interval between two full compactions to ensure latency. This is set to 0 by default, so each checkpoint will have a full compression and generate a change log.

{{< img src="/img/changelog-producer-full-compaction.png">}}

{{< hint info >}}

Full compaction changelog producer can produce complete changelog for any type of source. However it is not as efficient as the input changelog producer and the latency to produce changelog might be high.

{{< /hint >}}

## Sequence Field

By default, the primary key table determines the merge order according to the input order (the last input record will be the last to merge). However, in distributed computing,
there will be some cases that lead to data disorder. At this time, you can use a time field as `sequence.field`, for example:

{{< hint info >}}
When the record is updated or deleted, the `sequence.field` must become larger and cannot remain unchanged. For example,
you can use [Mysql Binlog operation time](https://ververica.github.io/flink-cdc-connectors/master/content/connectors/mysql-cdc.html#available-metadata) as `sequence.field`.
{{< /hint >}}

{{< tabs "sequence.field" >}}

{{< tab "Flink" >}}

```sql
CREATE TABLE MyTable (
    pk BIGINT PRIMARY KEY NOT ENFORCED,
    v1 DOUBLE,
    v2 BIGINT,
    dt TIMESTAMP
) WITH (
    'sequence.field' = 'dt'
);
```

{{< /tab >}}

{{< /tabs >}}

The record with the largest `sequence.field` value will be the last to merge, regardless of the input order.
