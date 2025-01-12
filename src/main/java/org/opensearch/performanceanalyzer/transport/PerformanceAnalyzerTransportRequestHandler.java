/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

/*
 * Copyright 2019-2021 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.opensearch.performanceanalyzer.transport;


import com.google.common.annotations.VisibleForTesting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.bulk.BulkShardRequest;
import org.opensearch.action.support.replication.TransportReplicationAction.ConcreteShardRequest;
import org.opensearch.performanceanalyzer.PerformanceAnalyzerApp;
import org.opensearch.performanceanalyzer.config.PerformanceAnalyzerController;
import org.opensearch.performanceanalyzer.rca.framework.metrics.WriterMetrics;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportChannel;
import org.opensearch.transport.TransportRequest;
import org.opensearch.transport.TransportRequestHandler;

public class PerformanceAnalyzerTransportRequestHandler<T extends TransportRequest>
        implements TransportRequestHandler<T> {
    private static final Logger LOG =
            LogManager.getLogger(PerformanceAnalyzerTransportRequestHandler.class);
    private final PerformanceAnalyzerController controller;
    private TransportRequestHandler<T> actualHandler;
    boolean logOnce = false;

    PerformanceAnalyzerTransportRequestHandler(
            TransportRequestHandler<T> actualHandler, PerformanceAnalyzerController controller) {
        this.actualHandler = actualHandler;
        this.controller = controller;
    }

    PerformanceAnalyzerTransportRequestHandler<T> set(TransportRequestHandler<T> actualHandler) {
        this.actualHandler = actualHandler;
        return this;
    }

    @Override
    public void messageReceived(T request, TransportChannel channel, Task task) throws Exception {
        actualHandler.messageReceived(request, getChannel(request, channel, task), task);
    }

    @VisibleForTesting
    TransportChannel getChannel(T request, TransportChannel channel, Task task) {
        if (!controller.isPerformanceAnalyzerEnabled()) {
            return channel;
        }

        if (request instanceof ConcreteShardRequest) {
            return getShardBulkChannel(request, channel, task);
        } else {
            return channel;
        }
    }

    private TransportChannel getShardBulkChannel(T request, TransportChannel channel, Task task) {
        String className = request.getClass().getName();
        boolean bPrimary = false;

        if (className.equals(
                "org.opensearch.action.support.replication.TransportReplicationAction$ConcreteShardRequest")) {
            bPrimary = true;
        } else if (className.equals(
                "org.opensearch.action.support.replication.TransportReplicationAction$ConcreteReplicaRequest")) {
            bPrimary = false;
        } else {
            return channel;
        }

        TransportRequest transportRequest = ((ConcreteShardRequest<?>) request).getRequest();

        if (!(transportRequest instanceof BulkShardRequest)) {
            return channel;
        }

        BulkShardRequest bsr = (BulkShardRequest) transportRequest;
        PerformanceAnalyzerTransportChannel performanceanalyzerChannel =
                new PerformanceAnalyzerTransportChannel();

        try {
            performanceanalyzerChannel.set(
                    channel,
                    System.currentTimeMillis(),
                    bsr.index(),
                    bsr.shardId().id(),
                    bsr.items().length,
                    bPrimary);
        } catch (Exception ex) {
            if (!logOnce) {
                LOG.error(ex);
                logOnce = true;
            }
            PerformanceAnalyzerApp.WRITER_METRICS_AGGREGATOR.updateStat(
                    WriterMetrics.OPENSEARCH_REQUEST_INTERCEPTOR_ERROR, "", 1);
        }

        return performanceanalyzerChannel;
    }
}
