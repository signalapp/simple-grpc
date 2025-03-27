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
import java.util.Optional;
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

    ServerCalls.unaryCall("Test request", responseObserver, ignored -> response, ignored -> Optional.empty());

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

    final Function<Throwable, Optional<Status>> mapException = ignored -> Optional.of(Status.RESOURCE_EXHAUSTED);

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
        ignored -> Optional.empty());

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
        ignored -> Optional.of(Status.RESOURCE_EXHAUSTED));

    verifyError(responseObserver, Status.Code.RESOURCE_EXHAUSTED, cancelled);
  }

  @Test
  void serverStreamingCallPublishedError() {
    @SuppressWarnings("unchecked") final ServerCallStreamObserver<Integer> responseObserver =
        mock(ServerCallStreamObserver.class);

    ServerCalls.serverStreamingCall("Test request",
        responseObserver,
        ignored -> JdkFlowAdapter.publisherToFlowPublisher(Mono.error(RuntimeException::new)),
        ignored -> Optional.of(Status.RESOURCE_EXHAUSTED));

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

  @Test
  void clientStreamingCall() {
    @SuppressWarnings("unchecked") final ServerCallStreamObserver<List<Integer>> responseObserver =
        mock(ServerCallStreamObserver.class);

    final ClientRequestPublisher<Integer> clientRequestPublisher =
        (ClientRequestPublisher<Integer>) ServerCalls.clientStreamingCall(responseObserver,
            (CheckedFunction<Flow.Publisher<Integer>, CompletionStage<List<Integer>>>) (requestPublisher ->
                JdkFlowAdapter.flowPublisherToFlux(requestPublisher)
                    .collectList()
                    .toFuture()),
            ignored -> Optional.empty());

    clientRequestPublisher.onNext(0);
    clientRequestPublisher.onNext(1);
    clientRequestPublisher.onNext(2);
    clientRequestPublisher.onCompleted();

    verify(responseObserver).onNext(List.of(0, 1, 2));
    verify(responseObserver).onCompleted();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void clientStreamingCallException(final boolean cancelled) {
    @SuppressWarnings("unchecked") final ServerCallStreamObserver<List<Integer>> responseObserver =
        mock(ServerCallStreamObserver.class);

    when(responseObserver.isCancelled()).thenReturn(cancelled);

    ServerCalls.clientStreamingCall(responseObserver,
        ignored -> {
          throw new RuntimeException();
        },
        ignored -> Optional.of(Status.RESOURCE_EXHAUSTED));

    verifyError(responseObserver, Status.Code.RESOURCE_EXHAUSTED, cancelled);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void clientStreamingCallPublishedError(final boolean cancelled) {
    @SuppressWarnings("unchecked") final ServerCallStreamObserver<List<Integer>> responseObserver =
        mock(ServerCallStreamObserver.class);

    when(responseObserver.isCancelled()).thenReturn(cancelled);

    ServerCalls.clientStreamingCall(responseObserver,
        (CheckedFunction<Flow.Publisher<Integer>, CompletionStage<List<Integer>>>) (ignored ->
            CompletableFuture.failedFuture(new RuntimeException())),
        ignored -> Optional.of(Status.RESOURCE_EXHAUSTED));

    verifyError(responseObserver, Status.Code.RESOURCE_EXHAUSTED, cancelled);
  }

  @Test
  void bidirectionalStreamingCall() {
    @SuppressWarnings("unchecked") final ServerCallStreamObserver<Integer> responseObserver =
        mock(ServerCallStreamObserver.class);

    final ClientRequestPublisher<Integer> clientRequestPublisher =
        (ClientRequestPublisher<Integer>) ServerCalls.bidirectionalStreamingCall(responseObserver,
            (CheckedFunction<Flow.Publisher<Integer>, Flow.Publisher<Integer>>) (requestPublisher ->
                JdkFlowAdapter.publisherToFlowPublisher(JdkFlowAdapter.flowPublisherToFlux(requestPublisher)
                    .map(n -> -n))),
            ignored -> Optional.empty());

    clientRequestPublisher.onNext(0);
    clientRequestPublisher.onNext(1);
    clientRequestPublisher.onNext(2);
    clientRequestPublisher.onCompleted();

    final ArgumentCaptor<Integer> responseCaptor = ArgumentCaptor.forClass(Integer.class);

    verify(responseObserver, times(3)).onNext(responseCaptor.capture());
    verify(responseObserver).onCompleted();

    assertEquals(List.of(0, -1, -2), responseCaptor.getAllValues());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void bidirectionalStreamingCallException(final boolean cancelled) {
    @SuppressWarnings("unchecked") final ServerCallStreamObserver<Integer> responseObserver =
        mock(ServerCallStreamObserver.class);

    when(responseObserver.isCancelled()).thenReturn(cancelled);

    ServerCalls.bidirectionalStreamingCall(responseObserver,
        ignored -> {
          throw new RuntimeException();
        },
        ignored -> Optional.of(Status.RESOURCE_EXHAUSTED));

    verifyError(responseObserver, Status.Code.RESOURCE_EXHAUSTED, cancelled);
  }

  @Test
  void bidirectionalStreamingCallPublishedError() {
    @SuppressWarnings("unchecked") final ServerCallStreamObserver<Integer> responseObserver =
        mock(ServerCallStreamObserver.class);

    ServerCalls.bidirectionalStreamingCall(responseObserver,
        ignored -> JdkFlowAdapter.publisherToFlowPublisher(Mono.error(RuntimeException::new)),
        ignored -> Optional.of(Status.RESOURCE_EXHAUSTED));

    verifyError(responseObserver, Status.Code.RESOURCE_EXHAUSTED, false);
  }
}
