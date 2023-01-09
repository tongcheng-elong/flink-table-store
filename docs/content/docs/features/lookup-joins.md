---
title: "Lookup Joins"
weight: 3
type: docs
aliases:
- /features/lookup-joins.html
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

# Lookup Joins

[Lookup Joins](https://nightlies.apache.org/flink/flink-docs-stable/docs/dev/table/sql/queries/joins/) are a type of join in streaming queries. It is used to enrich a table with data that is queried from Table Store. The join requires one table to have a processing time attribute and the other table to be backed by a lookup source connector.

Table Store supports lookup joins on unpartitioned tables with primary keys in Flink. The following example illustrates this feature.

First, let's create a Table Store table and update it in real-time.

```sql
-- Create a table store catalog
CREATE CATALOG my_catalog WITH (
  'type'='table-store',
  'warehouse'='hdfs://nn:8020/warehouse/path' -- or 'file://tmp/foo/bar'
);

USE CATALOG my_catalog;

-- Create a table in table-store catalog
CREATE TABLE customers (
    id INT PRIMARY KEY NOT ENFORCED,
    name STRING,
    country STRING,
    zip STRING
);

-- Launch a streaming job to update customers table
INSERT INTO customers ...

-- Create a temporary left table, like from kafka
CREATE TEMPORARY TABLE Orders (
    order_id INT,
    total INT,
    customer_id INT,
    proc_time AS PROCTIME()
) WITH (
    'connector' = 'kafka',
    'topic' = '...',
    'properties.bootstrap.servers' = '...',
    'format' = 'csv'
    ...
);
```

You can now use `customers` in a lookup join query.

```sql
-- enrich each order with customer information
SELECT o.order_id, o.total, c.country, c.zip
FROM Orders AS o
JOIN customers
FOR SYSTEM_TIME AS OF o.proc_time AS c
ON o.customer_id = c.id;
```

The lookup join operator will maintain a RocksDB cache locally and pull the latest updates of the table in real time. Lookup join operator will only pull the necessary data, so your filter conditions are very important for performance.

This feature is only suitable for tables containing at most tens of millions of records to avoid excessive use of local disks.

## RocksDB Cache Options

The following options allow users to finely adjust RocksDB for better performance. You can either specify them in table properties or in dynamic table hints.

```sql
-- dynamic table hints example
SELECT o.order_id, o.total, c.country, c.zip
FROM Orders AS o JOIN customers /*+ OPTIONS('lookup.cache-rows'='20000') */
FOR SYSTEM_TIME AS OF o.proc_time AS c
ON o.customer_id = c.id;
```

{{< generated/rocksdb_configuration >}}
