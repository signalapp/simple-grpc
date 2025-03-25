/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.grpc.simple;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.ServerCallStreamObserver;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    final Function<Exception, Throwable> mapException = ignored -> Status.RESOURCE_EXHAUSTED.asException();

    ServerCalls.unaryCall("Test request", responseObserver, ignored -> {
      throw new RuntimeException();
    }, mapException);

    verify(responseObserver, atLeastOnce()).isCancelled();

    if (!cancelled) {
      verify(responseObserver).onError(argThat(throwable ->
          throwable instanceof StatusException &&
              ((StatusException) throwable).getStatus().getCode() == Status.Code.RESOURCE_EXHAUSTED));
    }

    verifyNoMoreInteractions(responseObserver);
  }
}
