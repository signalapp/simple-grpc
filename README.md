# simple-grpc

simple-grpc generates [gRPC](https://grpc.io/) service stubs that are intended to be simple and straightforward to use. It provides a [protocol buffers](https://protobuf.dev/) compiler plugin and a small runtime library. Service stubs generated by simple-grpc target Java 9 and newer and require no additional dependencies (aside from the simple-grpc runtime). simple-grpc does not generate client stubs.

The default gRPC server stub generator aims to make as few assumptions as possible, remain highly generic and adaptable, and targets the widest range of Java versions possible. While these are laudable and understandable goals, the tradeoffs that come with those decisions aren't right for everybody. The conceptual model imposed by the default code generator can be confusing for engineers approaching it for the first time, and engineers must take care when implementing service methods to avoid some significant pitfalls. simple-grpc aims to simplify the conceptual model and eliminate the most common pitfalls. To illustrate how simple-grpc works, let's consider a pair of examples. We'll start by considering a service with a single [unary RPC](https://grpc.io/docs/what-is-grpc/core-concepts/#unary-rpc):

```protobuf
service Calculator {
  rpc Divide (DivisionRequest) returns (DivisionResponse) {}
}

message DivisionRequest {
  int32 dividend = 1;
  int32 divisor = 2;
}

message DivisionResponse {
  int32 quotient = 1;
}
```

If we were to implement the service with the default gRPC server generator, we might wind up with an implementation that looks something like this:

```java
@Override
public void divide(final DivisionRequest request, final StreamObserver<DivisionResponse> responseObserver) {
  // This could throw an exception if `divisor` is 0!
  responseObserver.onNext(DivisionResponse.newBuilder()
      .setQuotient(request.getDividend() / request.getDivisor())
      .build());

  responseObserver.onCompleted();
}
```

Note that we must call both `StreamObserver#onNext` and `StreamObserver#onCompleted`. There are two issues here:

1. This is a unary method, and so implementations must call `onNext` at most once, but there's no API- or compiler-level enforcement of that requirement. Implementors could forget to call `onNext` entirely, call it more than once, or get caught by some runtime exception that prevents an expected `onNext` call from happening.
2. Similarly, implementations must call `onCompleted` or `onError` exactly once, but (as with `onNext`) there's no API- or compiler-level enforcement. Implementors could forget, miss a call in a complex branching structure, or get caught by an unexpected runtime exception.

In the example above, if a caller makes a request with a divisor of 0, then the division will fail with an `ArithmeticException` and the implementation will not call `onNext` or `onCompleted` (or `onError`). From the caller's perspective, the call will simply appear to hang indefinitely with no error message of any kind.

Let's compare an implementation based a stub from the default code generator to one based on a server stub generated by simple-grpc:

```java
@Override
public DivisionResponse divide(final DivisionRequest request) throws Exception {
  // This could throw an exception if `divisor` is 0!
  return DivisionResponse.newBuilder()
      .setQuotient(request.getDividend() / request.getDivisor())
      .build();
}
```

With simple-grpc, unary implementations simply return a result or throw an exception. They do not need to interact with a `StreamObserver` at all, and the compiler enforces that the method either returns a result or throws an exception (note that simple-grpc methods are allowed to throw checked exceptions!). It's impossible to write an implementation that doesn't exit somehow.

If a caller were to invoke this method as written with a divisor of zero, the division operation would throw an `ArithmeticException` (as in the previous example), but the simple-grpc runtime would catch the exception and transmit an error to the caller. By default, all exceptions are communicated to callers with a gRPC status of `UNKNOWN`.

simple-grpc allows implementations to provide their own exception-to-`Status`-mapping methods. In this case, we might provide an exception mapper like:

```java
@Override
protected Optional<Status> mapExceptionToStatus(final Throwable throwable) {
  if (throwable instanceof ArithmeticException) {
    return Optional.of(Status.INVALID_ARGUMENT.withCause(throwable));
  }

  return super.mapExceptionToStatus(throwable);
}
```

With this exception mapper in place, a division-by-zero error would produce an `ArithmeticException` which simple-grpc would pass to `mapExceptionToStatus`, which would in turn translate it to a more appropriate status. Callers may also choose to catch and handle exceptions in implementing methods, but service-level exception mapping functions can be helpful for common exception types.

Let's consider a second example to illustrate how simple-grpc simplifies conceptual models. This time, let's consider a bidirectional streaming service. We'll add a new method to our example `Calculator` service:

```protobuf
service Calculator {
  // ...

  // Add a stream of integers, returning the new sum after each addition
  rpc RunningAddition (stream AdditionRequest) returns (stream AdditionResponse) {}
}

message AdditionRequest {
  int32 addend = 1;
}

message AdditionResponse {
  int64 sum = 1;
}
```

Here's an implementation using the default generator:

```java
@Override
public StreamObserver<AdditionRequest> runningAddition(final StreamObserver<AdditionResponse> responseObserver) {
  final AtomicLong runningSum = new AtomicLong();

  return new StreamObserver<>() {
    @Override
    public void onNext(final AdditionRequest additionRequest) {
      final long updatedSum = runningSum.addAndGet(additionRequest.getAddend());

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
```

Note that the _response_ observer is passed to the implementation as an argument, and the implementation is expected to return its own `StreamObserver` implementation to accept _request_ instances. That's likely the inverse of what most Java engineers would expect (though it is certainly understandable given the goals and constraints that apply to the default generator). By contrast, here's an implementation based on the stub generated by simple-grpc:

```java
@Override
public Flow.Publisher<AdditionResponse> runningAddition(final Flow.Publisher<AdditionRequest> requestPublisher) {
  final AtomicLong runningSum = new AtomicLong();

  return JdkFlowAdapter.publisherToFlowPublisher(
      JdkFlowAdapter.flowPublisherToFlux(requestPublisher)
          .map(AdditionRequest::getAddend)
          .map(runningSum::addAndGet)
          .map(sum -> AdditionResponse.newBuilder().setSum(sum).build()));
}
```

The method generated by simple-grpc accepts a [`java.util.concurrent.Flow.Publisher`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Flow.Publisher.html) that produces a stream of requests and returns a `Flow.Publisher` that produces a stream of response objects. Callers are free to use the reactive flow system of their choice; here, we're using [Reactor](https://projectreactor.io/). This has the nice property of placing the inputs in the method arguments and the outputs in the returned value. `Publishers` generated by gRPC also inherently support [gRPC flow control](https://grpc.io/docs/guides/flow-control/); simple-grpc transparently bridges the flow control system in a [`java.util.concurrent.Flow`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Flow.html) to the gRPC flow control system.

While not shown in this example, the `mapException` method in simple-grpc-generated server classes will also handle exceptions passed to a response `Publisher`'s `onError` method.

## Generating simple gRPC service stubs

When added as a protocol buffer compiler plugin for gRPC, simple-grpc will generate service stubs for services discovered in your project's `src/main/proto` (and `src/test/proto`) directory. To use simple-grpc as a gRPC server generator (assuming Maven as a build system), add the following to the `<build>` section of your `pom.xml`:

```xml
<build>
    <extensions>
        <extension>
            <groupId>kr.motd.maven</groupId>
            <artifactId>os-maven-plugin</artifactId>
            <version>${os.maven.plugin.version}</version>
        </extension>
    </extensions>

    <plugins>
        <plugin>
            <groupId>org.xolstice.maven.plugins</groupId>
            <artifactId>protobuf-maven-plugin</artifactId>

            <configuration>
                <protocArtifact>com.google.protobuf:protoc:${protoc.version}:exe:${os.detected.classifier}</protocArtifact>
                <pluginId>grpc-java</pluginId>
                <pluginArtifact>io.grpc:protoc-gen-grpc-java:${grpc.version}:exe:${os.detected.classifier}</pluginArtifact>
            </configuration>

            <executions>
                <execution>
                    <goals>
                        <goal>compile</goal>
                        <goal>compile-custom</goal>
                        <goal>test-compile</goal>
                        <goal>test-compile-custom</goal>
                    </goals>
                    <configuration>
                        <protocPlugins>
                            <protocPlugin>
                                <id>simple-grpc</id>
                                <groupId>org.signal</groupId>
                                <artifactId>simple-grpc-generator</artifactId>
                                <version>${simple-grpc.version}</version>
                                <mainClass>org.signal.grpc.simple.SimpleGrpcGenerator</mainClass>
                            </protocPlugin>
                        </protocPlugins>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

Note that you'll need to define or replace:

- `${os.maven.plugin.version}`
- `${protoc.version}`
- `${grpc.version}`
- `${simple-grpc.version}`

`${os.detected.classifier}` is provided by `os-maven-plugin` and does not need to be manually-specified.

## Implementing simple gRPC service stubs

To implement services based on stubs generated by simple-grpc, you'll first need to add the simple-grpc runtime as a dependency:

```xml
<dependency>
  <groupId>org.signal</groupId>
  <artifactId>simple-grpc-runtime</artifactId>
  <version>${simple-grpc.version}</version>
</dependency>
```

From there, create a class that extends one of the base classes generated by simple-grpc, then override and implement the individual service methods. Note that stub names generated by simple-grpc always begin with a prefix of `Simple`, though the package and containing class may change depending on your specific protocol buffer options.

From our calculator example above, an implementation might look something like:

```java
public class SimpleGrpcCalculator extends SimpleCalculatorGrpc.CalculatorImplBase {

  @Override
  public DivisionResponse divide(final DivisionRequest request) throws Exception {
    // Your implementation here!
  }

  @Override
  public Flow.Publisher<AdditionResponse> runningAddition(final Flow.Publisher<AdditionRequest> requestPublisher) throws Exception {
    // Your implementation here!
  }

  @Override
  protected Throwable mapException(final Throwable throwable) {
    // Your implementation here!
  }
}
```

In general, gRPC services have four distinct types of remote procedure calls (RPCs): [unary](https://grpc.io/docs/what-is-grpc/core-concepts/#unary-rpc), [server streaming](https://grpc.io/docs/what-is-grpc/core-concepts/#server-streaming-rpc), [client streaming](https://grpc.io/docs/what-is-grpc/core-concepts/#server-streaming-rpc), and [bidirectional streaming](https://grpc.io/docs/what-is-grpc/core-concepts/#server-streaming-rpc). simple-grpc will generate different method signatures for each type:

1. For unary RPCs, simple-grpc will generate a blocking method that accepts a single request object and returns a single response object. Unary request methods may throw exceptions—even checked exceptions. Callers should take care to provide the gRPC server with an appropriate executor for running blocking methods; we recommend a [virtual-thread-per-task](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Executors.html#newVirtualThreadPerTaskExecutor()) executor for implementors using Java 21 or newer.
2. For server streaming RPCs, simple-grpc will generate a method that accepts a single request object and returns a `java.util.concurrent.Flow.Publisher` that produces a stream of response objects. The subscriber's `onNext`, `onComplete`, and `onError` methods are automatically and transparently connected to the analogous gRPC signals, and flow control signals from the client are propagated to the publisher. Note that response publishers should expect a series of small requests rather than smaller numbers of larger requests (i.e. 100 calls to `request(1)` rather than a single call to `request(100)`). Callers may wish to prefetch and buffer responses to compensate. In addition to publishing errors, implementations may throw exceptions—even checked exceptions—outside the scope of the response publisher (e.g. an `IllegalArgumentException` if the request is invalid).
3. For client streaming RPCs, simple-grpc will generate a method that accepts a `Publisher` of request objects and returns a `CompletionStage` that yields a single response object. Flow control signals sent to the request publisher will be propagated to the client. In addition to returning a failed completion stage, implementations may throw exceptions—even checked exceptiosn—outside the scope of the returned completion stage (e.g. an `IllegalStateException` if the application is not ready to begin processing requests).
4. For bidirectional streaming requests, simple-grpc will generate a method that accepts a `Publisher` of request objects and returns a `Publisher` of response objects. The publishers behave as they do in server streaming requests and client streaming requests. As with server streaming requests, implementations may publish errors via the response publisher or throw exceptions directly.

## Handling exceptions

All error reporting pathways in all RPC types ultimately pass through an implementation's exception-mapping method. This applies to thrown exceptions, errors published via a `Flow.Subscription`, and errors yielded by failed `CompletionStage`s.

The default exception-mapping method has the following behavior:

- For a [`StatusException`](https://grpc.github.io/grpc-java/javadoc/io/grpc/StatusException.html) or [`StatusRuntimeException`](https://grpc.github.io/grpc-java/javadoc/io/grpc/StatusRuntimeException.html), simple-grpc will transmit the gRPC [`Status`](https://grpc.github.io/grpc-java/javadoc/io/grpc/Status.html) from the exception to the calling client.
- For other types of exceptions, it will invoke [`Status#fromThrowable`](https://grpc.github.io/grpc-java/javadoc/io/grpc/Status.html#fromThrowable(java.lang.Throwable)) to find a `StatusException` or `StatusRuntimeException` in the exception's causal chain. If one is found, then simple-grpc will transmit the `Status` from the exception. Otherwise, it will transmit an error with a [status code](https://grpc.io/docs/guides/status-codes/) of `UNKNOWN`.

Callers may override the default exception mapper for each generated service to return a specific gRPC `Status` for specific exceptions. To recall a prior example, a custom exception mapper might look something like this:

```java
@Override
protected Optional<Status> mapExceptionToStatus(final Throwable throwable) {
  if (throwable instanceof ArithmeticException) {
    return Optional.of(Status.INVALID_ARGUMENT.withCause(throwable));
  }

  return super.mapExceptionToStatus(throwable);
}
```

## Building and testing

simple-grpc uses [Maven](https://maven.apache.org/) as its build system. To build simple-grpc from source:

```shell
./mvnw clean package
```

…or to run tests:

```shell
./mvnw clean test
```

### For IntelliJ IDEA users

Note that IntelliJ IDEA struggles with multi-module projects that set their versions on the fly (like simple-grpc). Please see [IDEA-187928](https://youtrack.jetbrains.com/issue/IDEA-187928/Jgitver-not-working-at-all-for-a-multimodule-project) for background and discussion, but in short, IntelliJ users are likely to encounter an error something like:

> Could not find artifact org.signal:simple-grpc:jar:tests:0.0.1-SNAPSHOT

To work around the issue, IntelliJ users can navigate to Settings → Build, Execution, Deployment → Build Tools → Maven → Importing and add `-Djgitver.skip=true` to "VM options for importer."

## License

Copyright 2025 Signal Messenger, LLC

Licensed under the [GNU AGPLv3](https://www.gnu.org/licenses/agpl-3.0.html).