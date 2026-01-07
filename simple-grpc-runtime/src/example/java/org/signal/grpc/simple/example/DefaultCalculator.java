/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.grpc.simple.example;

import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicLong;

public class DefaultCalculator extends CalculatorGrpc.CalculatorImplBase {

  @Override
  public void divide(final DivisionRequest request, final StreamObserver<DivisionResponse> responseObserver) {
    // This could throw an exception if `divisor` is 0!
    responseObserver.onNext(DivisionResponse.newBuilder()
        .setQuotient(request.getDividend() / request.getDivisor())
        .build());

    responseObserver.onCompleted();
  }

  @Override
  public StreamObserver<AdditionRequest> runningAddition(final StreamObserver<AdditionResponse> responseObserver) {
    final AtomicLong sum = new AtomicLong();

    return new StreamObserver<>() {
      @Override
      public void onNext(final AdditionRequest additionRequest) {
        final long updatedSum = sum.addAndGet(additionRequest.getAddend());

        responseObserver.onNext(AdditionResponse.newBuilder()
            .setSum(updatedSum)
            .build());
      }

      @Override
      public void onError(final Throwable throwable) {
        // Terminate the response stream if the client sends an error
        responseObserver.onError(throwable);
      }

      @Override
      public void onCompleted() {
        // When the client has finished sending requests, we're done sending responses
        responseObserver.onCompleted();
      }
    };
  }
}
