/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.http.nio.pool;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.http.annotation.ThreadSafe;
import org.apache.http.concurrent.BasicFuture;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.nio.reactor.BasicConnectingIOReactor;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.nio.reactor.SessionRequest;
import org.apache.http.nio.reactor.SessionRequestCallback;
import org.apache.http.pool.ConnPool;
import org.apache.http.pool.ConnPoolControl;
import org.apache.http.pool.PoolEntry;
import org.apache.http.pool.PoolEntryCallback;
import org.apache.http.pool.PoolStats;
import org.apache.http.util.Args;
import org.apache.http.util.Asserts;

/**
 * Abstract non-blocking connection pool.
 *
 * @param <T> route
 * @param <C> connection object
 * @param <E> pool entry
 *
 * @since 4.2
 */
@ThreadSafe
public abstract class LocklessConnPoolBase<T, C, E extends PoolEntry<T, C>>
implements ConnPool<T, E>, ConnPoolControl<T> {

    private final ConnectingIOReactor ioreactor;
    private final NIOConnFactory<T, C> connFactory;
    private final SocketAddressResolver<T> addressResolver;
//    private final SessionRequestCallback sessionRequestCallback;
    protected final ConcurrentHashMap<T, RouteSpecificPoolBase<T, C, E>> routeToPool;
    private final Deque<LeaseRequest<T, C, E>> leasingRequests;
    private final Set<SessionRequest> pending;
    private final Set<E> leased;
    private final Deque<E> available;
    private final Queue<LeaseRequest<T, C, E>> completedRequests;
    private final Map<T, Integer> maxPerRoute;
//    private final Lock lock;
    private final AtomicBoolean isShutDown;

    private volatile int defaultMaxPerRoute;
    private volatile int maxTotal;

    /**
     * @deprecated use {@link LocklessConnPoolBase#LocklessConnPoolBase(ConnectingIOReactor,
     *   NIOConnFactory, SocketAddressResolver, int, int)}
     */
    @Deprecated
    public LocklessConnPoolBase(
        final ConnectingIOReactor ioreactor, final NIOConnFactory<T, C> connFactory,
        final int defaultMaxPerRoute, final int maxTotal
    ) {
        super();
        Args.notNull(ioreactor, "I/O reactor");
        Args.notNull(connFactory, "Connection factory");
        Args.positive(defaultMaxPerRoute, "Max per route value");
        Args.positive(maxTotal, "Max total value");
        this.ioreactor = ioreactor;
        this.connFactory = connFactory;
        this.addressResolver = new SocketAddressResolver<T>() {

            public SocketAddress resolveLocalAddress(final T route) throws IOException {
                return LocklessConnPoolBase.this.resolveLocalAddress(route);
            }

            public SocketAddress resolveRemoteAddress(final T route) throws IOException {
                return LocklessConnPoolBase.this.resolveRemoteAddress(route);
            }

        };
        //this.sessionRequestCallback = new InternalSessionRequestCallback();
//        this.routeToPool = new HashMap<T, RouteSpecificPool<T, C, E>>();
//        this.leasingRequests = new ConcurrentLinkedQueue<LeaseRequest<T, C, E>>();
//        this.pending = new HashSet<SessionRequest>();
//        this.leased = new HashSet<E>();
//        this.available = new ConcurrentLinkedQueue<E>();
//        this.maxPerRoute = new HashMap<T, Integer>();
        this.routeToPool = new ConcurrentHashMap<T, RouteSpecificPoolBase<T, C, E>>();
        this.leasingRequests = new ConcurrentLinkedDeque<LeaseRequest<T, C, E>>();
        this.pending = Collections.newSetFromMap(new ConcurrentHashMap<SessionRequest,Boolean>()) ; //new HashSet<SessionRequest>();
        this.leased =  Collections.newSetFromMap(new ConcurrentHashMap<E,Boolean>()) ; // new HashSet<E>();
        this.available = new ConcurrentLinkedDeque<E>();
        this.maxPerRoute = new ConcurrentHashMap<T, Integer>();
        this.completedRequests = new ConcurrentLinkedQueue<LeaseRequest<T, C, E>>();
//        this.lock = new ReentrantLock();
        this.isShutDown = new AtomicBoolean(false);
        this.defaultMaxPerRoute = defaultMaxPerRoute;
        this.maxTotal = maxTotal;
    }

    /**
     * @since 4.3
     */
    public LocklessConnPoolBase(
        final ConnectingIOReactor ioreactor, final NIOConnFactory<T, C> connFactory,
        final SocketAddressResolver<T> addressResolver, final int defaultMaxPerRoute,
        final int maxTotal
    ) {
        super();
        Args.notNull(ioreactor, "I/O reactor");
        Args.notNull(connFactory, "Connection factory");
        Args.notNull(addressResolver, "Address resolver");
        Args.positive(defaultMaxPerRoute, "Max per route value");
        Args.positive(maxTotal, "Max total value");
        this.ioreactor = ioreactor;
        this.connFactory = connFactory;
        this.addressResolver = addressResolver;
        //this.sessionRequestCallback = new InternalSessionRequestCallback();
        this.routeToPool = new ConcurrentHashMap<T, RouteSpecificPoolBase<T, C, E>>();
        this.leasingRequests = new ConcurrentLinkedDeque<LeaseRequest<T, C, E>>();
        this.pending = Collections.newSetFromMap(new ConcurrentHashMap<SessionRequest,Boolean>()) ; //new HashSet<SessionRequest>();
        this.leased =  Collections.newSetFromMap(new ConcurrentHashMap<E,Boolean>()) ; // new HashSet<E>();
        this.available = new ConcurrentLinkedDeque<E>();
        this.maxPerRoute = new ConcurrentHashMap<T, Integer>();

//        this.routeToPool = new HashMap<T, RouteSpecificPool<T, C, E>>();
//        this.leasingRequests = new LinkedList<LeaseRequest<T, C, E>>();
//        this.pending = new HashSet<SessionRequest>();
//        this.leased = new HashSet<E>();
//        this.available = new LinkedList<E>();
//        this.maxPerRoute = new HashMap<T, Integer>();
        this.completedRequests = new ConcurrentLinkedQueue<LeaseRequest<T, C, E>>();
//        this.lock = new ReentrantLock();
        this.isShutDown = new AtomicBoolean(false);
        this.defaultMaxPerRoute = defaultMaxPerRoute;
        this.maxTotal = maxTotal;
    }

    /**
     * @deprecated (4.3) use {@link SocketAddressResolver}
     */
    @Deprecated
    protected SocketAddress resolveRemoteAddress(final T route) {
        return null;
    }

    /**
     * @deprecated (4.3) use {@link SocketAddressResolver}
     */
    @Deprecated
    protected SocketAddress resolveLocalAddress(final T route) {
        return null;
    }

    protected abstract E createEntry(T route, C conn);

    /**
     * @since 4.3
     */
    protected void onLease(final E entry) {
    }

    /**
     * @since 4.3
     */
    protected void onRelease(final E entry) {
    }

    public boolean isShutdown() {
        return this.isShutDown.get();
    }

    public void shutdown(final long waitMs) throws IOException {
        if (this.isShutDown.compareAndSet(false, true)) {
            fireCallbacks();
//            this.lock.lock();
//            try {
                for (final SessionRequest sessionRequest: this.pending) {
                    sessionRequest.cancel();
                }
                for (final E entry: this.available) {
                    entry.close();
                }
                for (final E entry: this.leased) {
                    entry.close();
                }
                for (final RouteSpecificPoolBase<T, C, E> pool: this.routeToPool.values()) {
                    if (pool != null) {
                        pool.shutdown();
                    }
                }
                this.routeToPool.clear();
                this.leased.clear();
                this.pending.clear();
                this.available.clear();
                this.leasingRequests.clear();
                this.ioreactor.shutdown(waitMs);
//            } finally {
//                this.lock.unlock();
//            }
        }
    }

    private RouteSpecificPoolBase<T, C, E> getPool(final T route) {
        RouteSpecificPoolBase<T, C, E> pool = this.routeToPool.get(route);
        if (pool == null) {
            pool = new RouteSpecificPoolBase<T, C, E>(route) {

                @Override
                protected E createEntry(final T route, final C conn) {
                    return LocklessConnPoolBase.this.createEntry(route, conn);
                }

            };
            final RouteSpecificPoolBase<T, C, E> existing = this.routeToPool.putIfAbsent(route, pool);
            if (existing != null) {
                pool = existing ;
            }
        }
        return pool;
    }

    public Future<E> lease(
            final T route, final Object state,
            final long connectTimeout, final TimeUnit tunit,
            final FutureCallback<E> callback) {
        return this.lease(route, state, connectTimeout, connectTimeout, tunit, callback);
    }

    /**
     * @since 4.3
     */
    public Future<E> lease(
            final T route, final Object state,
            final long connectTimeout, final long leaseTimeout, final TimeUnit tunit,
            final FutureCallback<E> callback) {
        Args.notNull(route, "Route");
        Args.notNull(tunit, "Time unit");
        Asserts.check(!this.isShutDown.get(), "Connection pool shut down");
        final BasicFuture<E> future = new BasicFuture<E>(callback);
//        this.lock.lock();
//        try {
            final long timeout = connectTimeout > 0 ? tunit.toMillis(connectTimeout) : 0;
            final LeaseRequest<T, C, E> request = new LeaseRequest<T, C, E>(route, state, timeout, leaseTimeout, future);
            final boolean completed = processPendingRequest(request);
            if (!request.isDone() && !completed) {
                this.leasingRequests.add(request);
            }
            if (request.isDone()) {
                this.completedRequests.add(request);
            }
//        } finally {
//            this.lock.unlock();
//        }
        fireCallbacks();
        return future;
    }

    public Future<E> lease(final T route, final Object state, final FutureCallback<E> callback) {
        return lease(route, state, -1, TimeUnit.MICROSECONDS, callback);
    }

    public Future<E> lease(final T route, final Object state) {
        return lease(route, state, -1, TimeUnit.MICROSECONDS, null);
    }

    public void release(final E entry, final boolean reusable) {
        if (entry == null) {
            return;
        }
        if (this.isShutDown.get()) {
            return;
        }
//        this.lock.lock();
//        try {
            if (this.leased.remove(entry)) {
                final RouteSpecificPoolBase<T, C, E> pool = getPool(entry.getRoute());
                pool.free(entry, reusable);
                if (reusable) {
                    this.available.offerFirst(entry);
                    onRelease(entry);
                } else {
                    entry.close();
                }
                processNextPendingRequest();
            }
//        } finally {
//            this.lock.unlock();
//        }
        fireCallbacks();
    }

    private void processPendingRequests() {
        final Iterator<LeaseRequest<T, C, E>> it = this.leasingRequests.iterator();
        while (it.hasNext()) {
            final LeaseRequest<T, C, E> request = it.next();
            final boolean completed = processPendingRequest(request);
            if (request.isDone() || completed) {
                it.remove();
            }
            if (request.isDone()) {
                this.completedRequests.add(request);
            }
        }
    }

    private void processNextPendingRequest() {
        final Iterator<LeaseRequest<T, C, E>> it = this.leasingRequests.iterator();
        while (it.hasNext()) {
            final LeaseRequest<T, C, E> request = it.next();
            final boolean completed = processPendingRequest(request);
            if (request.isDone() || completed) {
                it.remove();
            }
            if (request.isDone()) {
                this.completedRequests.add(request);
            }
            if (completed) {
                return;
            }
        }
    }

    private boolean processPendingRequest(final LeaseRequest<T, C, E> request) {
        final T route = request.getRoute();
        final Object state = request.getState();
        final long deadline = request.getDeadline();

        final long now = System.currentTimeMillis();
        if (now > deadline) {
            request.failed(new TimeoutException());
            return false;
        }

        final RouteSpecificPoolBase<T, C, E> pool = getPool(route);
        E entry;
        for (;;) {
            entry = pool.getFree(state);
            if (entry == null) {
                break;
            }
            if (entry.isClosed() || entry.isExpired(System.currentTimeMillis())) {
                entry.close();
                this.available.remove(entry);
                pool.free(entry, false);
            } else {
                break;
            }
        }
        if (entry != null) {
            this.leased.add(entry);
            this.available.remove(entry);
            request.completed(entry);
            onLease(entry);
            return true;
        }

        // New connection is needed
        final int maxPerRoute = getMax(route);
        // Shrink the pool prior to allocating a new connection
        final int excess = Math.max(0, pool.getAllocatedCount() + 1 - maxPerRoute);
        if (excess > 0) {
            for (int i = 0; i < excess; i++) {
                final E lastUsed = pool.getLastUsed();
                if (lastUsed == null) {
                    break;
                }
                lastUsed.close();
                this.available.remove(lastUsed);
                pool.remove(lastUsed);
            }
        }

        if (pool.getAllocatedCount() < maxPerRoute) {
            final int totalUsed = this.pending.size() + this.leased.size();
            final int freeCapacity = Math.max(this.maxTotal - totalUsed, 0);
            if (freeCapacity == 0) {
                return false;
            }
            final int totalAvailable = this.available.size();
            if (totalAvailable > freeCapacity - 1) {
                final E lastUsed = this.available.pollLast();
                if (lastUsed != null) {
                    lastUsed.close();
                    final RouteSpecificPoolBase<T, C, E> otherpool = getPool(lastUsed.getRoute());
                    otherpool.remove(lastUsed);
                }
            }

            final SocketAddress localAddress;
            final SocketAddress remoteAddress;
            try {
                remoteAddress = this.addressResolver.resolveRemoteAddress(route);
                localAddress = this.addressResolver.resolveLocalAddress(route);
            } catch (final IOException ex) {
                request.failed(ex);
                return false;
            }
            final BasicFuture<E> future = request.getFuture();
            final SessionRequest sessionRequest = this.ioreactor.connect(
                    remoteAddress, localAddress, route, new InternalSessionRequestCallback(pool, future));
            // actually useless, but kept for compatibility with unit tests (SRU)
            if (!(this.ioreactor instanceof BasicConnectingIOReactor)) {
                pool.addPending(sessionRequest, future);
                this.pending.add(sessionRequest);
                final int timout = request.getConnectTimeout() < Integer.MAX_VALUE ?
                        (int) request.getConnectTimeout() : Integer.MAX_VALUE;
                sessionRequest.setConnectTimeout(timout);
            }
            // End of comment (SRU)
            return true;
        } else {
            return false;
        }
    }

    private void fireCallbacks() {
        LeaseRequest<T, C, E> request;
        while ((request = this.completedRequests.poll()) != null) {
            final BasicFuture<E> future = request.getFuture();
            final Exception ex = request.getException();
            final E result = request.getResult();
            if (ex != null) {
                future.failed(ex);
            } else if (result != null) {
                future.completed(result);
            } else {
                future.cancel();
            }
        }
    }

    public void validatePendingRequests() {
//        this.lock.lock();
//        try {
            final long now = System.currentTimeMillis();
            final Iterator<LeaseRequest<T, C, E>> it = this.leasingRequests.iterator();
            while (it.hasNext()) {
                final LeaseRequest<T, C, E> request = it.next();
                final long deadline = request.getDeadline();
                if (now > deadline) {
                    it.remove();
                    request.failed(new TimeoutException());
                    this.completedRequests.add(request);
                }
            }
//        } finally {
//            this.lock.unlock();
//        }
        fireCallbacks();
    }

    protected void requestCompleted(final SessionRequest request) {
        if (this.isShutDown.get()) {
            return;
        }
        @SuppressWarnings("unchecked")
        final
        T route = (T) request.getAttachment();
        final RouteSpecificPoolBase<T, C, E> pool = getPool(route);
        final IOSession session = request.getSession();
        try {
            final C conn = this.connFactory.create(route, session);
            final E entry = pool.createEntry(request, conn);
            pool.completed(request, entry);
            this.pending.remove(request);
            this.leased.add(entry);
            onLease(entry);
        } catch (final IOException ex) {
            pool.failed(request, ex);
        }
        fireCallbacks();
    }

    protected void requestCancelled(final SessionRequest request) {
        if (this.isShutDown.get()) {
            return;
        }
        @SuppressWarnings("unchecked")
        final
        T route = (T) request.getAttachment();
//        this.lock.lock();
//        try {
            this.pending.remove(request);
            final RouteSpecificPoolBase<T, C, E> pool = getPool(route);
            pool.cancelled(request);
            processNextPendingRequest();
//        } finally {
//            this.lock.unlock();
//        }
        fireCallbacks();
    }

    protected void requestFailed(final SessionRequest request) {
        if (this.isShutDown.get()) {
            return;
        }
        @SuppressWarnings("unchecked")
        final
        T route = (T) request.getAttachment();
//        this.lock.lock();
//        try {
            this.pending.remove(request);
            final RouteSpecificPoolBase<T, C, E> pool = getPool(route);
            pool.failed(request, request.getException());
            processNextPendingRequest();
//        } finally {
//            this.lock.unlock();
//        }
        fireCallbacks();
    }

    protected void requestTimeout(final SessionRequest request) {
        if (this.isShutDown.get()) {
            return;
        }
        @SuppressWarnings("unchecked")
        final
        T route = (T) request.getAttachment();
//        this.lock.lock();
//        try {
            this.pending.remove(request);
            final RouteSpecificPoolBase<T, C, E> pool = getPool(route);
            pool.timeout(request);
            processNextPendingRequest();
//        } finally {
//            this.lock.unlock();
//        }
        fireCallbacks();
    }

    private int getMax(final T route) {
        final Integer v = this.maxPerRoute.get(route);
        if (v != null) {
            return v.intValue();
        } else {
            return this.defaultMaxPerRoute;
        }
    }

    public void setMaxTotal(final int max) {
        Args.positive(max, "Max value");
//        this.lock.lock();
//        try {
            this.maxTotal = max;
//        } finally {
//            this.lock.unlock();
//        }
    }

    public int getMaxTotal() {
//        this.lock.lock();
//        try {
            return this.maxTotal;
//        } finally {
//            this.lock.unlock();
//        }
    }

    public void setDefaultMaxPerRoute(final int max) {
        Args.positive(max, "Max value");
//        this.lock.lock();
//        try {
            this.defaultMaxPerRoute = max;
//        } finally {
//            this.lock.unlock();
//        }
    }

    public int getDefaultMaxPerRoute() {
//        this.lock.lock();
//        try {
            return this.defaultMaxPerRoute;
//        } finally {
//            this.lock.unlock();
//        }
    }

    public void setMaxPerRoute(final T route, final int max) {
        Args.notNull(route, "Route");
        Args.positive(max, "Max value");
//        this.lock.lock();
//        try {
            this.maxPerRoute.put(route, Integer.valueOf(max));
//        } finally {
//            this.lock.unlock();
//        }
    }

    public int getMaxPerRoute(final T route) {
        Args.notNull(route, "Route");
//        this.lock.lock();
//        try {
            return getMax(route);
//        } finally {
//            this.lock.unlock();
//        }
    }

    public PoolStats getTotalStats() {
//        this.lock.lock();
//        try {
            return new PoolStats(
                    this.leased.size(),
                    this.pending.size(),
                    this.available.size(),
                    this.maxTotal);
//        } finally {
//            this.lock.unlock();
//        }
    }

    public PoolStats getStats(final T route) {
        Args.notNull(route, "Route");
//        this.lock.lock();
//        try {
            final RouteSpecificPoolBase<T, C, E> pool = getPool(route);
            return new PoolStats(
                    pool.getLeasedCount(),
                    pool.getPendingCount(),
                    pool.getAvailableCount(),
                    getMax(route));
//        } finally {
//            this.lock.unlock();
//        }
    }

    /**
     * Enumerates all available connections.
     *
     * @since 4.3
     */
    protected void enumAvailable(final PoolEntryCallback<T, C> callback) {
//        this.lock.lock();
//        try {
            final Iterator<E> it = this.available.iterator();
            while (it.hasNext()) {
                final E entry = it.next();
                callback.process(entry);
                if (entry.isClosed()) {
                    final RouteSpecificPoolBase<T, C, E> pool = getPool(entry.getRoute());
                    pool.remove(entry);
                    it.remove();
                }
            }
            processPendingRequests();
            purgePoolMap();
//        } finally {
//            this.lock.unlock();
//        }
    }

    /**
     * Enumerates all leased connections.
     *
     * @since 4.3
     */
    protected void enumLeased(final PoolEntryCallback<T, C> callback) {
//        this.lock.lock();
//        try {
            final Iterator<E> it = this.leased.iterator();
            while (it.hasNext()) {
                final E entry = it.next();
                callback.process(entry);
            }
            processPendingRequests();
//        } finally {
//            this.lock.unlock();
//        }
    }

    /**
     * Use {@link #enumLeased(org.apache.http.pool.PoolEntryCallback)}
     *  or {@link #enumAvailable(org.apache.http.pool.PoolEntryCallback)} instead.
     *
     * @deprecated (4.3.2)
     */
    @Deprecated
    protected void enumEntries(final Iterator<E> it, final PoolEntryCallback<T, C> callback) {
        while (it.hasNext()) {
            final E entry = it.next();
            callback.process(entry);
        }
        processPendingRequests();
    }

    private void purgePoolMap() {
        final Iterator<Map.Entry<T, RouteSpecificPoolBase<T, C, E>>> it = this.routeToPool.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<T, RouteSpecificPoolBase<T, C, E>> entry = it.next();
            final RouteSpecificPoolBase<T, C, E> pool = entry.getValue();
            if (pool.getAllocatedCount() == 0) {
                it.remove();
            }
        }
    }

    public void closeIdle(final long idletime, final TimeUnit tunit) {
        Args.notNull(tunit, "Time unit");
        long time = tunit.toMillis(idletime);
        if (time < 0) {
            time = 0;
        }
        final long deadline = System.currentTimeMillis() - time;
        enumAvailable(new PoolEntryCallback<T, C>() {

            public void process(final PoolEntry<T, C> entry) {
                if (entry.getUpdated() <= deadline) {
                    entry.close();
                }
            }

        });
    }

    public void closeExpired() {
        final long now = System.currentTimeMillis();
        enumAvailable(new PoolEntryCallback<T, C>() {

            public void process(final PoolEntry<T, C> entry) {
                if (entry.isExpired(now)) {
                    entry.close();
                }
            }

        });
    }

    @Override
    public String toString() {
        final StringBuilder buffer = new StringBuilder();
        buffer.append("[leased: ");
        buffer.append(this.leased);
        buffer.append("][available: ");
        buffer.append(this.available);
        buffer.append("][pending: ");
        buffer.append(this.pending);
        buffer.append("]");
        return buffer.toString();
    }

    public class InternalSessionRequestCallback implements SessionRequestCallback {

        private final BasicFuture<E> future;
        private final RouteSpecificPoolBase<T, C, E> pool;

        public InternalSessionRequestCallback(final RouteSpecificPoolBase<T, C, E> pool, final BasicFuture<E> future) {
            this.pool = pool;
            this.future = future;
        }

        public void completed(final SessionRequest request) {
            requestCompleted(request);
        }

        public void cancelled(final SessionRequest request) {
            requestCancelled(request);
        }

        public void failed(final SessionRequest request) {
            requestFailed(request);
        }

        public void timeout(final SessionRequest request) {
            requestTimeout(request);
        }

        public void initiated(final SessionRequest request) {
            pool.addPending(request, future);
            pending.add(request);
            final int timout = request.getConnectTimeout() < Integer.MAX_VALUE ?
                    (int) request.getConnectTimeout() : Integer.MAX_VALUE;
            request.setConnectTimeout(timout);
        }
    }
}
