/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.grpc.simple;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Channel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

public class SimpleGrpcIntegrationTest {

  private CalculatorService calculatorService;

  private Server server;
  private Channel channel;

  private static final String SERVER_NAME = "calculator-test";

  private static class CalculatorService extends SimpleCalculatorGrpc.CalculatorImplBase {

    private final AtomicInteger countElementsGenerated = new AtomicInteger();

    @Override
    public AdditionResponse add(final AdditionRequest request) {
      if (request.getAddendList().isEmpty()) {
        throw new IllegalArgumentException("Must specify at least one addend");
      }

      return AdditionResponse.newBuilder().setSum(request.getAddendList().stream()
              .mapToInt(i -> i)
              .sum())
          .build();
    }

    @Override
    public Flow.Publisher<CountResponse> count(final CountRequest request) {
      if (request.getLimit() < 1) {
        throw new IllegalArgumentException("Limit must be positive");
      }

      return JdkFlowAdapter.publisherToFlowPublisher(Flux.range(0, request.getLimit())
          .doOnNext(ignored -> countElementsGenerated.incrementAndGet())
          .map(i -> CountResponse.newBuilder().setNumber(i).build()));
    }

    @Override
    public CompletionStage<AdditionResponse> addStream(final Flow.Publisher<AdditionRequest> request) {
      return JdkFlowAdapter.flowPublisherToFlux(request)
          .flatMapIterable(AdditionRequest::getAddendList)
          .reduce(0, Integer::sum)
          .map(sum -> AdditionResponse.newBuilder().setSum(sum).build())
          .toFuture();
    }

    public int getCountElementsGenerated() {
      return countElementsGenerated.get();
    }

    @Override
    protected Throwable mapException(final Throwable e) {
      if (e instanceof IllegalArgumentException) {
        return Status.INVALID_ARGUMENT.withCause(e).asException();
      }

      return super.mapException(e);
    }
  }

  @BeforeEach
  void setUp() throws IOException {
    calculatorService = new CalculatorService();

    server = InProcessServerBuilder.forName(SERVER_NAME)
        .directExecutor()
        .addService(calculatorService)
        .build()
        .start();

    channel = InProcessChannelBuilder.forName(SERVER_NAME)
        .directExecutor()
        .build();
  }

  @AfterEach
  void tearDown() {
    server.shutdownNow();
  }

  @Test
  void testUnary() {
    final CalculatorGrpc.CalculatorBlockingStub stub = CalculatorGrpc.newBlockingStub(channel);

    final AdditionRequest additionRequest = AdditionRequest.newBuilder()
        .addAddend(8)
        .addAddend(6)
        .build();

    assertEquals(14, stub.add(additionRequest).getSum());
  }

  @Test
  void testUnaryException() {
    final CalculatorGrpc.CalculatorBlockingStub stub = CalculatorGrpc.newBlockingStub(channel);

    final StatusRuntimeException statusRuntimeException =
        assertThrows(StatusRuntimeException.class, () -> stub.add(AdditionRequest.newBuilder().build()));

    assertEquals(Status.INVALID_ARGUMENT, statusRuntimeException.getStatus());
  }

  @Test
  void testServerStreaming() {
    final int limit = 10;

    final Iterator<CountResponse> responseIterator = CalculatorGrpc.newBlockingStub(channel)
        .count(CountRequest.newBuilder().setLimit(limit).build());

    int elementsCounted = 0;

    while (responseIterator.hasNext()) {
      assertEquals(elementsCounted++, responseIterator.next().getNumber());
    }

    assertEquals(limit, elementsCounted);
  }

  @Test
  void testServerStreamingFlowControl() throws IOException, InterruptedException {
    final CalculatorGrpc.CalculatorStub stub = CalculatorGrpc.newStub(channel);
    final CountDownLatch terminationLatch = new CountDownLatch(1);

    final AtomicBoolean completed = new AtomicBoolean();
    final AtomicReference<Throwable> error = new AtomicReference<>();
    final AtomicInteger elementsReceived = new AtomicInteger();

    final int cancelAfter = 3;

    final ClientResponseObserver<CountRequest, CountResponse> clientResponseObserver = new ClientResponseObserver<>() {

      private ClientCallStreamObserver<CountRequest> requestStream;

      @Override
      public void beforeStart(final ClientCallStreamObserver<CountRequest> requestStream) {
        this.requestStream = requestStream;
        this.requestStream.disableAutoRequestWithInitial(1);
      }

      @Override
      public void onNext(final CountResponse value) {
        if (elementsReceived.incrementAndGet() == cancelAfter) {
          requestStream.cancel("Cancel after set number of elements", null);
        } else {
          requestStream.request(1);
        }
      }

      @Override
      public void onError(final Throwable t) {
        error.set(t);
        terminationLatch.countDown();
      }

      @Override
      public void onCompleted() {
        completed.set(true);
        terminationLatch.countDown();
      }
    };

    stub.count(CountRequest.newBuilder().setLimit(cancelAfter * 10).build(), clientResponseObserver);
    terminationLatch.await();

    assertFalse(completed.get());
    assertInstanceOf(StatusRuntimeException.class, error.get());
    assertEquals(Status.CANCELLED.getCode(), ((StatusRuntimeException) error.get()).getStatus().getCode());
    assertEquals(cancelAfter, calculatorService.getCountElementsGenerated());
  }

  @Test
  void testClientStreaming() throws IOException, InterruptedException {
    final CountDownLatch terminationLatch = new CountDownLatch(1);

    final AtomicBoolean completed = new AtomicBoolean();
    final AtomicReference<Throwable> error = new AtomicReference<>();
    final AtomicLong sum = new AtomicLong();

    final StreamObserver<AdditionRequest> requestStreamObserver =
        CalculatorGrpc.newStub(channel).addStream(new StreamObserver<>() {
          @Override
          public void onNext(final AdditionResponse value) {
            sum.set(value.getSum());
          }

          @Override
          public void onError(final Throwable t) {
            error.set(t);
            terminationLatch.countDown();
          }

          @Override
          public void onCompleted() {
            completed.set(true);
            terminationLatch.countDown();
          }
        });

    requestStreamObserver.onNext(AdditionRequest.newBuilder().addAddend(6).build());
    requestStreamObserver.onNext(AdditionRequest.newBuilder().addAddend(7).build());
    requestStreamObserver.onNext(AdditionRequest.newBuilder().addAddend(8).build());
    requestStreamObserver.onCompleted();

    terminationLatch.await();

    assertTrue(completed.get());
    assertNull(error.get());
    assertEquals(21, sum.get());
  }
}
