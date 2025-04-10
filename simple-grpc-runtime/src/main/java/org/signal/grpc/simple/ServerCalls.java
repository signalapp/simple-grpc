/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.grpc.simple;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Function;

/**
 * Bridges calls from the gRPC framework to application-space service implementations.
 */
public class ServerCalls {

  private ServerCalls() {}

  /**
   * Handles unary (single-request, single-response) calls from a service stub generated by {@code SimpleGrpcGenerator}.
   *
   * @param request the request object from the client
   * @param responseObserver the response observer used by the server to send signals to the client
   * @param delegate the service method that will handle the business logic of this request
   * @param exceptionMapper a function that maps exceptions thrown by the {@code delegate} function to an appropriate
   *                        gRPC {@link Status}
   *
   * @param <Req> the type of request object handled by the {@code delegate} function
   * @param <Resp> the type of response object returned by the {@code delegate} function
   */
  public static <Req, Resp> void unaryCall(
      final Req request,
      final ServerCallStreamObserver<Resp> responseObserver,
      final CheckedFunction<Req, Resp> delegate,
      final Function<Throwable, Optional<Status>> exceptionMapper) {

    try {
      final Resp response = delegate.apply(request);

      // Don't try to respond if the server has already canceled the request
      if (!responseObserver.isCancelled()) {
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      }
    } catch (final Exception e) {
      handleDelegateException(e, exceptionMapper, responseObserver);
    }
  }

  /**
   * Handles server-streaming calls from a service stub generated by {@code SimpleGrpcGenerator}.
   *
   * @param request the request object from the client
   * @param responseObserver the response observer used by the server to send signals to the client
   * @param delegate the service method that will handle the business logic of this request; must not block
   * @param exceptionMapper a function that maps exceptions thrown by the {@code delegate} function to an appropriate
   *                        gRPC {@link Status}
   *
   * @param <Req> the type of request object handled by the {@code delegate} function
   * @param <Resp> the type of response object returned by the {@code delegate} function
   */
  public static <Req, Resp> void serverStreamingCall(
      final Req request,
      final ServerCallStreamObserver<Resp> responseObserver,
      final CheckedFunction<Req, Flow.Publisher<Resp>> delegate,
      final Function<Throwable, Optional<Status>> exceptionMapper) {

    try {
      delegate.apply(request)
          .subscribe(new ServerResponseSubscriber<>(responseObserver, exceptionMapper));
    } catch (final Exception e) {
      handleDelegateException(e, exceptionMapper, responseObserver);
    }
  }

  /**
   * Handles client-streaming calls from a service stub generated by {@code SimpleGrpcGenerator}.
   *
   * @param responseObserver the response observer used by the server to send signals to the client
   * @param delegate the service method that will handle the business logic of this request; must not block
   * @param exceptionMapper a function that maps exceptions thrown by the {@code delegate} function to an appropriate
   *                        gRPC {@link Status}
   *
   * @return a {@link StreamObserver} that receives requests from clients
   *
   * @param <Req> the type of request object handled by the {@code delegate} function
   * @param <Resp> the type of response object returned by the {@code delegate} function
   */
  public static <Req, Resp> StreamObserver<Req> clientStreamingCall(
      final ServerCallStreamObserver<Resp> responseObserver,
      final CheckedFunction<Flow.Publisher<Req>, CompletionStage<Resp>> delegate,
      final Function<Throwable, Optional<Status>> exceptionMapper) {

    final ClientRequestPublisher<Req> clientRequestPublisher = new ClientRequestPublisher<>(responseObserver);

    try {
      // We won't actually start getting requests until we return the `clientRequestPublisher`, which is what the gRPC
      // runtime uses to pass requests in. That means that we also won't get a response until after this method returns.
      // To deal with that, implementing methods return a `CompletionStage` that can complete asynchronously.
      delegate.apply(clientRequestPublisher)
          .whenComplete((response, throwable) -> {
            if (throwable != null) {
              handleDelegateException(throwable, exceptionMapper, responseObserver);
            } else {
              // Don't try to respond if the server has already canceled the request
              if (!responseObserver.isCancelled()) {
                responseObserver.onNext(response);
                responseObserver.onCompleted();
              }
            }
          });
    } catch (final Exception e) {
      handleDelegateException(e, exceptionMapper, responseObserver);
    }

    return clientRequestPublisher;
  }

  /**
   * Handles bidirectional streaming calls from a service stub generated by {@code SimpleGrpcGenerator}.
   *
   * @param responseObserver the response observer used by the server to send signals to the client
   * @param delegate the service method that will handle the business logic of this request; must not block
   * @param exceptionMapper a function that maps exceptions thrown by the {@code delegate} function to an appropriate
   *                        gRPC {@link Status}
   *
   * @return a {@link StreamObserver} that receives requests from clients
   *
   * @param <Req> the type of request object handled by the {@code delegate} function
   * @param <Resp> the type of response object returned by the {@code delegate} function
   */
  public static <Req, Resp> StreamObserver<Req> bidirectionalStreamingCall(
      final ServerCallStreamObserver<Resp> responseObserver,
      final CheckedFunction<Flow.Publisher<Req>, Flow.Publisher<Resp>> delegate,
      final Function<Throwable, Optional<Status>> exceptionMapper) {

    final ClientRequestPublisher<Req> clientRequestPublisher = new ClientRequestPublisher<>(responseObserver);

    try {
      delegate.apply(clientRequestPublisher)
          .subscribe(new ServerResponseSubscriber<>(responseObserver, exceptionMapper));
    } catch (final Exception e) {
      handleDelegateException(e, exceptionMapper, responseObserver);
    }

    return clientRequestPublisher;
  }

  private static void handleDelegateException(final Throwable throwable,
      final Function<Throwable, Optional<Status>> exceptionMapper,
      final ServerCallStreamObserver<?> responseObserver) {

    final StatusException statusException = exceptionMapper.apply(throwable)
        .orElseGet(() -> Status.fromThrowable(throwable))
        .asException();

    // Don't try to respond if the server has already canceled the request
    if (!responseObserver.isCancelled()) {
      responseObserver.onError(statusException);
    }
  }
}
