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
package org.apache.beam.sdk.io.astra.db;

/*-
 * #%L
 * Beam SDK for Astra
 * --
 * Copyright (C) 2023 DataStax
 * --
 * Licensed under the Apache License, Version 2.0
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import com.datastax.driver.core.ColumnMetadata;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.Metadata;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.TableMetadata;
import com.datastax.driver.core.Token;
import java.math.BigInteger;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Collectors;

import com.datastax.driver.core.exceptions.SyntaxError;
import org.apache.beam.sdk.io.astra.db.AstraDbIO.Read;
import org.apache.beam.sdk.io.astra.db.mapping.Mapper;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Joiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read Data coming from Cassandra.
 *
 * @param <T>
 *        type entity manipulated
 */
class ReadFn<T> extends DoFn<Read<T>, T> {

  /** Logger for the class. */
  private static final Logger LOG = LoggerFactory.getLogger(ReadFn.class);

  /** Reader function. */
  public ReadFn() {
    super();
  }

  @ProcessElement
  public void processElement(@Element Read<T> read, OutputReceiver<T> receiver) {
    try {
      Session session = AstraDbConnectionManager.getInstance().getSession(read);
      Mapper<T> mapper = read.mapperFactoryFn().apply(session);
      LOG.debug("ReadFn : Reading from {}.{}", read.keyspace().get(), read.table().get());
      Metadata          clusterMetadata = session.getCluster().getMetadata();
      KeyspaceMetadata  keyspaceMetadata = clusterMetadata.getKeyspace(read.keyspace().get());
      TableMetadata     tableMetadata = keyspaceMetadata .getTable(read.table().get());
      String partitionKey = tableMetadata.getPartitionKey().stream()
              .map(ColumnMetadata::getName)
              .collect(Collectors.joining(","));

      String query = generateRangeQuery(read, partitionKey, read.ringRanges() != null);
      PreparedStatement preparedStatement = session.prepare(query);
      Set<RingRange> ringRanges = read.ringRanges() == null ? Collections.emptySet() : read.ringRanges().get();

      for (RingRange rr : ringRanges) {
        Token startToken = session.getCluster().getMetadata().newToken(rr.getStart().toString());
        Token endToken = session.getCluster().getMetadata().newToken(rr.getEnd().toString());
        if (rr.isWrapping()) {
          // A wrapping range is one that overlaps from the end of the partitioner range and its
          // start (ie : when the start token of the split is greater than the end token)
          // We need to generate two queries here : one that goes from the start token to the end
          // of
          // the partitioner range, and the other from the start of the partitioner range to the
          // end token of the split.
          outputResults(
              session.execute(getLowestSplitQuery(read, partitionKey, rr.getEnd())),
              receiver,
              mapper);
          outputResults(
              session.execute(getHighestSplitQuery(read, partitionKey, rr.getStart())),
              receiver,
              mapper);
        } else {
          ResultSet rs =
              session.execute(
                  preparedStatement.bind().setToken(0, startToken).setToken(1, endToken));
          outputResults(rs, receiver, mapper);
        }
      }

      if (read.ringRanges() == null) {
        ResultSet rs = session.execute(preparedStatement.bind());
        outputResults(rs, receiver, mapper);
      }
    } catch(SyntaxError se) {
        // The last token is not a valid token, so we need to wrap around
        // mismatched input 'AND' expecting EOF (...(person_name) from beam.scientist [AND]...)
        LOG.debug("SyntaxError : {}", se.getMessage());
    } catch (Exception ex) {
        LOG.error("Cannot process read operation against Cassandra", ex);
        throw new IllegalStateException("Cannot process read operation against Cassandra", ex);
    }
  }

  private static <T> void outputResults(
      ResultSet rs, OutputReceiver<T> outputReceiver, Mapper<T> mapper) {
    Iterator<T> iter = mapper.map(rs);
    while (iter.hasNext()) {
      T n = iter.next();
      outputReceiver.output(n);
    }
  }

  private static String getHighestSplitQuery(
      Read<?> spec, String partitionKey, BigInteger highest) {
    String highestClause = String.format("(token(%s) >= %d)", partitionKey, highest);
    String finalHighQuery =
        (spec.query() == null)
            ? buildInitialQuery(spec, true) + highestClause
            : spec.query() + " AND " + highestClause;
    LOG.debug("CassandraIO generated a wrapAround query : {}", finalHighQuery);
    return finalHighQuery;
  }

  private static String getLowestSplitQuery(Read<?> spec, String partitionKey, BigInteger lowest) {
    String lowestClause = String.format("(token(%s) < %d)", partitionKey, lowest);
    String finalLowQuery =
        (spec.query() == null)
            ? buildInitialQuery(spec, true) + lowestClause
            : spec.query() + " AND " + lowestClause;
    LOG.debug("CassandraIO generated a wrapAround query : {}", finalLowQuery);
    return finalLowQuery;
  }

  private static String generateRangeQuery(
      Read<?> spec, String partitionKey, Boolean hasRingRange) {
    final String rangeFilter =
        hasRingRange
            ? Joiner.on(" AND ")
                .skipNulls()
                .join(
                    String.format("(token(%s) >= ?)", partitionKey),
                    String.format("(token(%s) < ?)", partitionKey))
            : "";
    final String combinedQuery = buildInitialQuery(spec, hasRingRange) + rangeFilter;
    LOG.debug("CassandraIO generated query : {}", combinedQuery);
    return combinedQuery;
  }

  private static String buildInitialQuery(Read<?> spec, Boolean hasRingRange) {
    String query = null;
    query = (spec.query() == null)
        ? String.format("SELECT * FROM %s.%s", spec.keyspace().get(), spec.table().get())
            + " WHERE "
        : spec.query().get()
            + (hasRingRange
                ? spec.query().get().toUpperCase().contains("WHERE") ? " AND " : " WHERE "
                : "");
    return query;
  }
}
