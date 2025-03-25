/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.grpc.simple;

import io.grpc.stub.ServerCallStreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.Flow;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ClientRequestPublisherTest {

  private ServerCallStreamObserver<Integer> responseObserver;

  private ClientRequestPublisher<Integer> clientRequestPublisher;

  @BeforeEach
  void setUp() {
    //noinspection unchecked
    responseObserver = mock(ServerCallStreamObserver.class);

    clientRequestPublisher = new ClientRequestPublisher<>(responseObserver);
    verify(responseObserver).disableAutoRequest();
  }

  @Test
  void subscribe() {
    @SuppressWarnings("unchecked") final Flow.Subscriber<Integer> firstSubscriber = mock(Flow.Subscriber.class);
    @SuppressWarnings("unchecked") final Flow.Subscriber<Integer> secondSubscriber = mock(Flow.Subscriber.class);

    clientRequestPublisher.subscribe(firstSubscriber);
    clientRequestPublisher.subscribe(secondSubscriber);

    final ArgumentCaptor<Flow.Subscription> subscriptionCaptor = ArgumentCaptor.forClass(Flow.Subscription.class);

    verify(firstSubscriber).onSubscribe(subscriptionCaptor.capture());
    verify(firstSubscriber, never()).onError(any());

    verify(secondSubscriber, never()).onSubscribe(any());
    verify(secondSubscriber).onError(argThat(throwable -> throwable instanceof IllegalStateException));

    final int demand = 17;

    subscriptionCaptor.getValue().request(demand);
    verify(responseObserver).request(demand);
  }

  @Test
  void onNext() {
    assertThrows(IllegalStateException.class, () -> clientRequestPublisher.onNext(7));

    @SuppressWarnings("unchecked") final Flow.Subscriber<Integer> subscriber = mock(Flow.Subscriber.class);

    final int next = ThreadLocalRandom.current().nextInt();

    clientRequestPublisher.subscribe(subscriber);
    clientRequestPublisher.onNext(next);

    verify(subscriber).onNext(next);
  }

  @Test
  void onError() {
    assertThrows(IllegalStateException.class, () -> clientRequestPublisher.onError(new RuntimeException()));

    @SuppressWarnings("unchecked") final Flow.Subscriber<Integer> subscriber = mock(Flow.Subscriber.class);

    final Throwable throwable = new ArithmeticException();

    clientRequestPublisher.subscribe(subscriber);
    clientRequestPublisher.onError(throwable);

    verify(subscriber).onError(throwable);
  }

  @Test
  void onCompleted() {
    assertThrows(IllegalStateException.class, () -> clientRequestPublisher.onCompleted());

    @SuppressWarnings("unchecked") final Flow.Subscriber<Integer> subscriber = mock(Flow.Subscriber.class);

    clientRequestPublisher.subscribe(subscriber);
    clientRequestPublisher.onCompleted();

    verify(subscriber).onComplete();
  }
}
