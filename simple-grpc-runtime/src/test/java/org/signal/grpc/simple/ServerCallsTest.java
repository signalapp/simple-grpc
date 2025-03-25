/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.grpc.simple;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ServerCallsTest {

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void unaryCall(final boolean cancelled) {
    final String response = "Test response";

    @SuppressWarnings("unchecked") final ServerCallStreamObserver<String> responseObserver =
        mock(ServerCallStreamObserver.class);

    when(responseObserver.isCancelled()).thenReturn(cancelled);

    ServerCalls.unaryCall("Test request", responseObserver, ignored -> response, ServerCalls::mapException);

    verify(responseObserver, atLeastOnce()).isCancelled();

    if (!cancelled) {
      verify(responseObserver).onNext(response);
      verify(responseObserver).onCompleted();
    }

    verifyNoMoreInteractions(responseObserver);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void unaryCallException(final boolean cancelled) {
    @SuppressWarnings("unchecked") final ServerCallStreamObserver<String> responseObserver =
        mock(ServerCallStreamObserver.class);

    when(responseObserver.isCancelled()).thenReturn(cancelled);

    final Function<Throwable, Throwable> mapException = ignored -> Status.RESOURCE_EXHAUSTED.asException();

    ServerCalls.unaryCall("Test request", responseObserver, ignored -> {
      throw new RuntimeException();
    }, mapException);

    verifyError(responseObserver, Status.Code.RESOURCE_EXHAUSTED, cancelled);
  }

  @Test
  void serverStreamingCall() {
    @SuppressWarnings("unchecked") final ServerCallStreamObserver<Integer> responseObserver =
        mock(ServerCallStreamObserver.class);

    when(responseObserver.isReady()).thenReturn(true);

    ServerCalls.serverStreamingCall("Test request",
        responseObserver,
        ignored -> JdkFlowAdapter.publisherToFlowPublisher(Flux.range(0, 3)),
        ServerCalls::mapException);

    final ArgumentCaptor<Integer> argumentCaptor = ArgumentCaptor.forClass(Integer.class);

    verify(responseObserver, times(3)).onNext(argumentCaptor.capture());
    verify(responseObserver).onCompleted();

    assertEquals(List.of(0, 1, 2), argumentCaptor.getAllValues());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void serverStreamingCallException(final boolean cancelled) {
    @SuppressWarnings("unchecked") final ServerCallStreamObserver<Integer> responseObserver =
        mock(ServerCallStreamObserver.class);

    when(responseObserver.isCancelled()).thenReturn(cancelled);

    ServerCalls.serverStreamingCall("Test request",
        responseObserver,
        ignored -> {
          throw new RuntimeException();
        },
        ignored -> Status.RESOURCE_EXHAUSTED.asException());

    verifyError(responseObserver, Status.Code.RESOURCE_EXHAUSTED, cancelled);
  }

  @Test
  void serverStreamingCallPublishedError() {
    @SuppressWarnings("unchecked") final ServerCallStreamObserver<Integer> responseObserver =
        mock(ServerCallStreamObserver.class);

    ServerCalls.serverStreamingCall("Test request",
        responseObserver,
        ignored -> JdkFlowAdapter.publisherToFlowPublisher(Mono.error(RuntimeException::new)),
        ignored -> Status.RESOURCE_EXHAUSTED.asException());

    verifyError(responseObserver, Status.Code.RESOURCE_EXHAUSTED, false);
  }

  private static void verifyError(final StreamObserver<?> responseObserver,
      @SuppressWarnings("SameParameterValue") final Status.Code expectedStatusCode,
      final boolean requestCancelled) {

    if (requestCancelled) {
      verify(responseObserver, never()).onError(any());
    } else {
      verify(responseObserver).onError(argThat(throwable ->
          throwable instanceof StatusException &&
              ((StatusException) throwable).getStatus().getCode() == expectedStatusCode));
    }

    verify(responseObserver, never()).onNext(any());
    verify(responseObserver, never()).onCompleted();
  }
}
