/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.grpc.simple;

import io.grpc.stub.ServerCallStreamObserver;
import java.util.concurrent.Flow;
import java.util.function.Function;

/**
 * A server response subscriber subscribes to a {@link java.util.concurrent.Flow.Publisher} of response elements and
 * publishes them to a {@link ServerCallStreamObserver} while respecting backpressure and flow-control signals.
 * <p>
 * Note that the gRPC Java framework does not allow servers to learn how much outstanding demand any client has (other
 * than that a client may receive at least one more response), so the {@link Flow.Publisher} will receive frequent
 * requests for a single element. Implementors may wish to compensate by buffering response objects.
 *
 * @param <T> the type of response element
 */
class ServerResponseSubscriber<T> implements Flow.Subscriber<T> {

  private final ServerCallStreamObserver<T> responseObserver;
  private final Function<Throwable, Throwable> exceptionMapper;

  private Flow.Subscription subscription;

  /**
   * Constructs a new server response subscriber. Server response subscribers bind to the given response observer's
   * lifecycle and <em>must</em> be constructed before starting a gRPC call (i.e. they may not be constructed for a
   * stream observer associated with an already-started call). In practice, this means that a
   * {@code ServerResponseSubscriber} should only be constructed by {@link ServerCalls}.
   *
   * @param responseObserver the response observer to which to send results
   * @param exceptionMapper a function to map exceptions to gRPC status codes
   */
  public ServerResponseSubscriber(final ServerCallStreamObserver<T> responseObserver,
      final Function<Throwable, Throwable> exceptionMapper) {

    this.responseObserver = responseObserver;
    this.exceptionMapper = exceptionMapper;

    // Regardless of whether the client cancelled the request or the request completed normally, we want to make sure
    // to cancel the subscription to avoid leaks. Note that, because we're establishing these handlers before starting
    // the gRPC call, none of these signals should have fired yet.
    responseObserver.setOnCancelHandler(this::onResponseObserverCancelled);
    responseObserver.setOnCloseHandler(this::onResponseObserverCancelled);
    responseObserver.setOnReadyHandler(this::onResponseObserverReady);
  }

  @Override
  public void onSubscribe(final Flow.Subscription subscription) {
    this.subscription = subscription;

    // Start the flow of responses if we can; if the response observer is NOT ready to receive requests immediately,
    // we'll start requesting elements from the publisher when the "on ready" handler fires later.
    if (responseObserver.isReady()) {
      subscription.request(1);
    }
  }

  @Override
  public void onNext(final T item) {
    // Don't send more items if the call is cancelled
    if (!responseObserver.isCancelled()) {
      responseObserver.onNext(item);

      // If we're NOT ready for more responses right now, we'll request more demand when the "on ready" handler fires
      // later.
      if (responseObserver.isReady()) {
        subscription.request(1);
      }
    }
  }

  @Override
  public void onError(final Throwable throwable) {
    final Throwable mappedThrowable = exceptionMapper.apply(throwable);

    // Don't send errors if the call is cancelled
    if (!responseObserver.isCancelled()) {
      responseObserver.onError(mappedThrowable);
    }
  }

  @Override
  public void onComplete() {
    // Don't complete the request if it's already cancelled
    if (!responseObserver.isCancelled()) {
      responseObserver.onCompleted();
    }
  }

  private void onResponseObserverReady() {
    // This may seem a little redundant, but the `ServerCallStreamObserver#setOnReadyHandler` docs point out that there
    // are cases where we might wind up here when `isReady` is `false`, and we should check on each call.
    if (responseObserver.isReady()) {
      subscription.request(1);
    }
  }

  private void onResponseObserverCancelled() {
    if (subscription != null) {
      subscription.cancel();
    }
  }
}
