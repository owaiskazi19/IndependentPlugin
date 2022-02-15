package transportservice;/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */


import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.Future;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.concurrent.AbstractRefCounted;
import transportservice.netty4.Netty;


import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.opensearch.common.util.concurrent.OpenSearchExecutors.daemonThreadFactory;

public final class SharedGroupFactory {

    private static final Logger logger = LogManager.getLogger(SharedGroupFactory.class);

    private final Settings settings;
    private final int workerCount;

    private RefCountedGroup genericGroup;
    private SharedGroup dedicatedHttpGroup;

    public SharedGroupFactory(Settings settings) {
        this.settings = settings;
        this.workerCount = Netty.WORKER_COUNT.get(settings);
    }

    public Settings getSettings() {
        return settings;
    }

    public int getTransportWorkerCount() {
        return workerCount;
    }

    public synchronized SharedGroup getTransportGroup() {
        return getGenericGroup();
    }

//    public synchronized SharedGroup getHttpGroup() {
//        if (httpWorkerCount == 0) {
//            return getGenericGroup();
//        } else {
//            if (dedicatedHttpGroup == null) {
//                NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup(
//                        httpWorkerCount,
//                        daemonThreadFactory(settings, HttpServerTransport.HTTP_SERVER_WORKER_THREAD_NAME_PREFIX)
//                );
//                dedicatedHttpGroup = new SharedGroup(new RefCountedGroup(eventLoopGroup));
//            }
//            return dedicatedHttpGroup;
//        }
//    }

    private SharedGroup getGenericGroup() {
        if (genericGroup == null) {
            EventLoopGroup eventLoopGroup = new NioEventLoopGroup(
                    workerCount,
                    daemonThreadFactory(settings, "transport_worker")
            );
            this.genericGroup = new RefCountedGroup(eventLoopGroup);
        } else {
            genericGroup.incRef();
        }
        return new SharedGroup(genericGroup);
    }

    private static class RefCountedGroup extends AbstractRefCounted {

        public static final String NAME = "ref-counted-event-loop-group";
        private final EventLoopGroup eventLoopGroup;

        private RefCountedGroup(EventLoopGroup eventLoopGroup) {
            super(NAME);
            this.eventLoopGroup = eventLoopGroup;
        }

        @Override
        protected void closeInternal() {
            Future<?> shutdownFuture = eventLoopGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            shutdownFuture.awaitUninterruptibly();
            if (shutdownFuture.isSuccess() == false) {
                logger.warn("Error closing netty event loop group", shutdownFuture.cause());
            }
        }
    }

    /**
     * Wraps the {@link RefCountedGroup}. Calls {@link RefCountedGroup#decRef()} on close. After close,
     * this wrapped instance can no longer be used.
     */
    public static class SharedGroup {

        private final RefCountedGroup refCountedGroup;

        private final AtomicBoolean isOpen = new AtomicBoolean(true);

        private SharedGroup(RefCountedGroup refCountedGroup) {
            this.refCountedGroup = refCountedGroup;
        }

        public EventLoopGroup getLowLevelGroup() {
            return refCountedGroup.eventLoopGroup;
        }

        public void shutdown() {
            if (isOpen.compareAndSet(true, false)) {
                refCountedGroup.decRef();
            }
        }
    }
}