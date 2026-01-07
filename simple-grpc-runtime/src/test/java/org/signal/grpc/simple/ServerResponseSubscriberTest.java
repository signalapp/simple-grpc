/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.grpc.simple;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.ServerCallStreamObserver;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ServerResponseSubscriberTest {

  private ServerCallStreamObserver<Integer> responseObserver;

  private ServerResponseSubscriber<Integer> serverResponseSubscriber;

  private AtomicReference<Runnable> onReadyHandler;
  private AtomicReference<Runnable> onCloseHandler;
  private AtomicReference<Runnable> onCancelHandler;

  private static final Status MAPPED_EXCEPTION_STATUS = Status.OUT_OF_RANGE;

  @BeforeEach
  void setUp() {
    //noinspection unchecked
    responseObserver = mock(ServerCallStreamObserver.class);

    onReadyHandler = new AtomicReference<>();
    onCloseHandler = new AtomicReference<>();
    onCancelHandler = new AtomicReference<>();

    doAnswer(invocation -> {
      onReadyHandler.set(invocation.getArgument(0));
      return null;
    }).when(responseObserver).setOnReadyHandler(any());

    doAnswer(invocation -> {
      onCloseHandler.set(invocation.getArgument(0));
      return null;
    }).when(responseObserver).setOnCloseHandler(any());

    doAnswer(invocation -> {
      onCancelHandler.set(invocation.getArgument(0));
      return null;
    }).when(responseObserver).setOnCancelHandler(any());

    serverResponseSubscriber =
        new ServerResponseSubscriber<>(responseObserver, ServerResponseSubscriberTest::mapException);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void onSubscribe(final boolean initiallyReady) {
    when(responseObserver.isReady()).thenReturn(initiallyReady);

    final Flow.Subscription subscription = mock(Flow.Subscription.class);
    serverResponseSubscriber.onSubscribe(subscription);

    if (initiallyReady) {
      verify(subscription).request(1);
    } else {
      verify(subscription, never()).request(anyInt());
    }
  }

  @ParameterizedTest
  @CsvSource({
      "true, true",
      "true, false",
      "false, true",
      "false, false",
  })
  void onNext(final boolean requestCancelled, final boolean ready) {
    final Flow.Subscription subscription = mock(Flow.Subscription.class);
    serverResponseSubscriber.onSubscribe(subscription);

    when(responseObserver.isCancelled()).thenReturn(requestCancelled);
    when(responseObserver.isReady()).thenReturn(ready);

    final int next = ThreadLocalRandom.current().nextInt();

    serverResponseSubscriber.onNext(next);

    if (requestCancelled) {
      verify(responseObserver, never()).onNext(anyInt());
      verify(subscription, never()).request(anyLong());
    } else {
      verify(responseObserver).onNext(next);

      if (ready) {
        verify(subscription).request(1);
      } else {
        verify(subscription, never()).request(anyLong());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void onError(final boolean requestCancelled) {
    when(responseObserver.isCancelled()).thenReturn(requestCancelled);

    serverResponseSubscriber.onError(new RuntimeException());

    if (requestCancelled) {
      verify(responseObserver, never()).onError(any());
    } else {
      verify(responseObserver).onError(argThat(throwable -> throwable instanceof StatusException &&
          MAPPED_EXCEPTION_STATUS.equals(((StatusException) throwable).getStatus())));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void onComplete(final boolean requestCancelled) {
    when(responseObserver.isCancelled()).thenReturn(requestCancelled);

    serverResponseSubscriber.onComplete();

    if (requestCancelled) {
      verify(responseObserver, never()).onCompleted();
    } else {
      verify(responseObserver).onCompleted();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void onResponseObserverReady(final boolean ready) {
    final Flow.Subscription subscription = mock(Flow.Subscription.class);
    serverResponseSubscriber.onSubscribe(subscription);

    when(responseObserver.isReady()).thenReturn(ready);

    onReadyHandler.get().run();

    if (ready) {
      verify(subscription).request(1);
    } else {
      verify(subscription, never()).request(anyLong());
    }
  }

  @Test
  void onResponseObserverClosed() {
    assertDoesNotThrow(() -> onCloseHandler.get().run());

    final Flow.Subscription subscription = mock(Flow.Subscription.class);
    serverResponseSubscriber.onSubscribe(subscription);

    onCloseHandler.get().run();

    verify(subscription).cancel();
  }

  @Test
  void onResponseObserverCancelled() {
    assertDoesNotThrow(() -> onCancelHandler.get().run());

    final Flow.Subscription subscription = mock(Flow.Subscription.class);
    serverResponseSubscriber.onSubscribe(subscription);

    onCancelHandler.get().run();

    verify(subscription).cancel();
  }

  private static Throwable mapException(final Throwable ignored) {
    return MAPPED_EXCEPTION_STATUS.asException();
  }
}
