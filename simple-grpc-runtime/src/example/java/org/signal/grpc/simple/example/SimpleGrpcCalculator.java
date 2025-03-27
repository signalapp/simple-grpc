/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.grpc.simple.example;

import io.grpc.Status;
import reactor.adapter.JdkFlowAdapter;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;

public class SimpleGrpcCalculator extends SimpleCalculatorGrpc.CalculatorImplBase {

  @Override
  public DivisionResponse divide(final DivisionRequest request) throws Exception {
    // This could throw an exception if `divisor` is 0!
    return DivisionResponse.newBuilder()
        .setQuotient(request.getDividend() / request.getDivisor())
        .build();
  }

  @Override
  protected Optional<Status> mapExceptionToStatus(final Throwable throwable) {
    if (throwable instanceof ArithmeticException) {
      return Optional.of(Status.INVALID_ARGUMENT.withCause(throwable));
    }

    return super.mapExceptionToStatus(throwable);
  }

  @Override
  public Flow.Publisher<AdditionResponse> runningAddition(final Flow.Publisher<AdditionRequest> requestPublisher) {
    final AtomicLong runningSum = new AtomicLong();

    return JdkFlowAdapter.publisherToFlowPublisher(
        JdkFlowAdapter.flowPublisherToFlux(requestPublisher)
            .map(AdditionRequest::getAddend)
            .map(runningSum::addAndGet)
            .map(sum -> AdditionResponse.newBuilder().setSum(sum).build()));
  }
}
