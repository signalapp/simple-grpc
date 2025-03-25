/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.grpc.simple;

import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.Flow;

/**
 * A client request publisher receives inbound client requests as a {@link io.grpc.stub.StreamObserver}, then passes the
 * requests to a subscriber (i.e. the handler method) as a {@link java.util.concurrent.Flow.Publisher}.
 *
 * @param <T> the type of request object
 */
class ClientRequestPublisher<T> implements StreamObserver<T>, Flow.Publisher<T> {

  // Perhaps confusingly, the RESPONSE observer is the channel by which we signal changes in demand to the client
  private final ServerCallStreamObserver<?> responseObserver;

  private Flow.Subscriber<? super T> subscriber;

  /**
   * Constructs a new client request publisher. Client request publishers bind to the given response observer's
   * lifecycle and <em>must</em> be constructed before starting a gRPC call (i.e. they may not be constructed for a
   * stream observer associated with an already-started call). In practice, this means that a
   * {@code ClientRequestPublisher} should only be constructed by {@link ServerCalls}.
   *
   * @param responseObserver the response observer to use to send flow control signals to the caller
   */
  public ClientRequestPublisher(final ServerCallStreamObserver<?> responseObserver) {
    this.responseObserver = responseObserver;
    this.responseObserver.disableAutoRequest();
  }

  @Override
  public void subscribe(final Flow.Subscriber<? super T> subscriber) {
    if (this.subscriber != null) {
      subscriber.onError(new IllegalStateException("Client request publishers only allow a single subscriber"));
      return;
    }

    this.subscriber = subscriber;

    subscriber.onSubscribe(new Flow.Subscription() {
      @Override
      public void request(final long n) {
        responseObserver.request((int) Math.min(n, Integer.MAX_VALUE));
      }

      @Override
      public void cancel() {
        // gRPC's Java framework doesn't expose a mechanism for server-side cancellation of individual requests from
        // service implementations
      }
    });
  }

  @Override
  public void onNext(final T value) {
    if (subscriber == null) {
      throw new IllegalStateException("No subscriber present");
    }

    subscriber.onNext(value);
  }

  @Override
  public void onError(final Throwable t) {
    if (subscriber == null) {
      throw new IllegalStateException("No subscriber present");
    }

    subscriber.onError(t);
  }

  @Override
  public void onCompleted() {
    if (subscriber == null) {
      throw new IllegalStateException("No subscriber present");
    }

    subscriber.onComplete();
  }
}
