/*
 * Copyright 2015-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.rsocket.kotlin.internal.lease

import io.netty.buffer.Unpooled
import io.reactivex.Completable
import io.reactivex.Flowable
import io.rsocket.kotlin.*
import io.rsocket.kotlin.util.DuplexConnectionProxy
import org.reactivestreams.Publisher
import java.nio.channels.ClosedChannelException
import java.util.concurrent.atomic.AtomicReference

internal class LeaseConnection(
        private val leaseContext: LeaseContext,
        source: DuplexConnection,
        private val sendManager: LeaseManager,
        private val receiveManager: LeaseManager)
    : DuplexConnectionProxy(source), LeaseGranter {

    @Volatile
    private var closed = false

    override fun grant(numberOfRequests: Int,
                       ttlSeconds: Int): Completable =
            sendLease(numberOfRequests, ttlSeconds)

    override fun send(frame: Publisher<Frame>): Completable {
        return super.send(Flowable.fromPublisher(frame)
                .doOnNext { f -> leaseGrantedTo(f, receiveManager) })
    }

    override fun receive(): Flowable<Frame> {
        return super.receive()
                .doOnNext { f -> leaseGrantedTo(f, sendManager) }
    }

    override fun onClose(): Completable {
        return super.onClose()
                .doOnComplete { closed = true }
    }

    private fun leaseGrantedTo(f: Frame,
                               leaseManager: LeaseManager) {
        if (isEnabled() && isLease(f)) {
            val requests = Frame.Lease.numberOfRequests(f)
            val ttl = Frame.Lease.ttl(f)
            leaseManager.grant(requests, ttl)
        }
    }

    private fun sendLease(requests: Int,
                          ttl: Int): Completable = when {
        ttl <= 0 -> Completable
                .error(IllegalArgumentException(
                        "Time-to-live should be positive"))
        requests <= 0 -> Completable
                .error(IllegalArgumentException(
                        "Allowed requests should be positive"))
        else ->
            if (closed) {
                Completable.error(ClosedChannelException())
            } else {
                send(Flowable.just(Frame.Lease.from(ttl, requests, Unpooled.EMPTY_BUFFER)))
            }
    }

    private fun isLease(f: Frame) = f.type === FrameType.LEASE

    private fun isEnabled() = leaseContext.leaseEnabled
}
