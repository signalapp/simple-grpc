/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.grpc.simple;

import io.grpc.Channel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.Test;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SimpleGrpcIntegrationTest {

  private static class CalculatorService extends SimpleCalculatorGrpc.CalculatorImplBase {

    @Override
    public AdditionResponse add(final AdditionRequest request) {
      return AdditionResponse.newBuilder().setSum(request.getAddendList().stream()
              .mapToInt(i -> i)
              .sum())
          .build();
    }
  }

  private static class ExceptionCalculatorService extends SimpleCalculatorGrpc.CalculatorImplBase {

    @Override
    public AdditionResponse add(final AdditionRequest request) {
      throw new IllegalArgumentException();
    }

    @Override
    protected Throwable mapException(final Exception e) {
      if (e instanceof IllegalArgumentException) {
        return Status.INVALID_ARGUMENT.asException();
      }

      return super.mapException(e);
    }
  }

  @Test
  void testUnary() throws IOException {
    final Server server = InProcessServerBuilder.forName("test")
        .directExecutor()
        .addService(new CalculatorService())
        .build()
        .start();

    try {
      final Channel channel = InProcessChannelBuilder.forName("test")
          .directExecutor()
          .build();

      final CalculatorGrpc.CalculatorBlockingStub stub = CalculatorGrpc.newBlockingStub(channel);

      final AdditionRequest additionRequest = AdditionRequest.newBuilder()
          .addAddend(8)
          .addAddend(6)
          .build();

      assertEquals(14, stub.add(additionRequest).getSum());
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void testUnaryException() throws IOException {
    final Server server = InProcessServerBuilder.forName("test")
        .directExecutor()
        .addService(new ExceptionCalculatorService())
        .build()
        .start();

    try {
      final Channel channel = InProcessChannelBuilder.forName("test")
          .directExecutor()
          .build();

      final CalculatorGrpc.CalculatorBlockingStub stub = CalculatorGrpc.newBlockingStub(channel);

      final StatusRuntimeException statusRuntimeException =
          assertThrows(StatusRuntimeException.class, () -> stub.add(AdditionRequest.newBuilder().build()));

      assertEquals(Status.INVALID_ARGUMENT, statusRuntimeException.getStatus());
    } finally {
      server.shutdownNow();
    }
  }
}
