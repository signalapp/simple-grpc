/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.grpc.simple;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.html.HtmlEscapers;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.compiler.PluginProtos;
import com.salesforce.jprotoc.Generator;
import com.salesforce.jprotoc.GeneratorException;
import com.salesforce.jprotoc.ProtoTypeMap;
import com.salesforce.jprotoc.ProtocPlugin;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Generates "simple" gRPC server stubs from gRPC service definitions. "Simple" server stubs:
 * <ul>
 *   <li>Generate blocking, just-return-the-response methods for unary gRPC methods</li>
 *   <li>Generate methods that accept a {@link java.util.concurrent.Flow.Publisher} and return a
 *   {@link java.util.concurrent.CompletionStage} for client-streaming gRPC methods</li>
 *   <li>Generate methods that return a {@code Flow.Publisher} for server-streaming gRPC methods</li>
 *   <li>Generate methods that accept and return a {@code Flow.Publisher} for bidirectional-streaming gRPC methods</li>
 * </ul>
 * Callers should take care to provide an appropriate executor to the gRPC server for blocking methods; although many
 * different kinds of executors could work, this generator was designed with
 * <a href="https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html">virtual threads</a> in mind.
 * <p>
 * This generator borrows heavily and with gratitude from the
 * <a href="https://github.com/salesforce/reactive-grpc">reactive-grpc</a> generator.
 */
public class SimpleGrpcGenerator extends Generator {

  private static final String CLASS_PREFIX = "Simple";
  private static final String SERVICE_JAVADOC_PREFIX = "    ";
  private static final String METHOD_JAVADOC_PREFIX = "        ";

  private static final int SERVICE_NUMBER_OF_PATHS = 2;
  private static final int METHOD_NUMBER_OF_PATHS = 4;

  public static void main(final String... args) {
    if (args.length == 0) {
      // Generate from protoc via stdin
      ProtocPlugin.generate(new SimpleGrpcGenerator());
    } else if (args.length == 1) {
      // Process from a descriptor_dump file via command line arg
      ProtocPlugin.debug(new SimpleGrpcGenerator(), args[0]);
    } else {
      System.err.println("Usage: SimpleGrpcGenerator [DESCRIPTOR_DUMP_FILE]");
    }
  }

  @Override
  public List<PluginProtos.CodeGeneratorResponse.File> generateFiles(final PluginProtos.CodeGeneratorRequest request) throws GeneratorException {
    final List<DescriptorProtos.FileDescriptorProto> protosToGenerate = request.getProtoFileList().stream()
        .filter(protoFile -> request.getFileToGenerateList().contains(protoFile.getName()))
        .toList();

    return generateFiles(findServices(protosToGenerate, ProtoTypeMap.of(request.getProtoFileList())));
  }

  @Override
  protected List<PluginProtos.CodeGeneratorResponse.Feature> supportedFeatures() {
    return List.of(PluginProtos.CodeGeneratorResponse.Feature.FEATURE_PROTO3_OPTIONAL);
  }

  private List<ServiceContext> findServices(final List<DescriptorProtos.FileDescriptorProto> fileDescriptorProtos,
      final ProtoTypeMap typeMap) {

    return fileDescriptorProtos.stream()
        .flatMap(fileDescriptorProto -> IntStream.range(0, fileDescriptorProto.getServiceCount())
            .mapToObj(serviceNumber -> buildServiceContext(fileDescriptorProto, typeMap, serviceNumber)))
        .toList();
  }

  private static ServiceContext buildServiceContext(final DescriptorProtos.FileDescriptorProto fileDescriptorProto,
      final ProtoTypeMap typeMap,
      final int serviceNumber) {

    final DescriptorProtos.ServiceDescriptorProto serviceProto = fileDescriptorProto.getService(serviceNumber);
    final List<DescriptorProtos.SourceCodeInfo.Location> locations =
        fileDescriptorProto.getSourceCodeInfo().getLocationList();

    final DescriptorProtos.SourceCodeInfo.Location serviceLocation = locations.stream()
        .filter(location ->
            location.getPathCount() >= 2 &&
                location.getPath(0) == DescriptorProtos.FileDescriptorProto.SERVICE_FIELD_NUMBER &&
                location.getPath(1) == serviceNumber)
        .filter(location -> location.getPathCount() == SERVICE_NUMBER_OF_PATHS)
        .findFirst()
        .orElseGet(DescriptorProtos.SourceCodeInfo.Location::getDefaultInstance);

    final ServiceContext serviceContext = new ServiceContext();
    serviceContext.fileName = CLASS_PREFIX + serviceProto.getName() + "Grpc.java";
    serviceContext.className = CLASS_PREFIX + serviceProto.getName() + "Grpc";
    serviceContext.serviceName = serviceProto.getName();
    serviceContext.deprecated = serviceProto.getOptions() != null && serviceProto.getOptions().getDeprecated();
    serviceContext.protoName = fileDescriptorProto.getName();
    serviceContext.packageName = extractPackageName(fileDescriptorProto);
    serviceContext.javaDoc = getJavaDoc(getComments(serviceLocation), SERVICE_JAVADOC_PREFIX);

    for (int methodNumber = 0; methodNumber < serviceProto.getMethodCount(); methodNumber++) {
      serviceContext.methods.add(buildMethodContext(serviceProto.getMethod(methodNumber), typeMap, locations, methodNumber));
    }

    return serviceContext;
  }

  private static String extractPackageName(final DescriptorProtos.FileDescriptorProto proto) {
    final DescriptorProtos.FileOptions options = proto.getOptions();

    if (options != null) {
      final String javaPackage = options.getJavaPackage();
      if (!Strings.isNullOrEmpty(javaPackage)) {
        return javaPackage;
      }
    }

    return Strings.nullToEmpty(proto.getPackage());
  }

  private static MethodContext buildMethodContext(final DescriptorProtos.MethodDescriptorProto methodProto,
      final ProtoTypeMap typeMap,
      final List<DescriptorProtos.SourceCodeInfo.Location> locations,
      final int methodNumber) {

    final DescriptorProtos.SourceCodeInfo.Location methodLocation = locations.stream()
        .filter(location ->
            location.getPathCount() == METHOD_NUMBER_OF_PATHS &&
                location.getPath(METHOD_NUMBER_OF_PATHS - 1) == methodNumber)
        .findFirst()
        .orElseGet(DescriptorProtos.SourceCodeInfo.Location::getDefaultInstance);

    final MethodContext methodContext = new MethodContext();
    methodContext.methodName = lowerCaseFirst(methodProto.getName());
    methodContext.inputType = typeMap.toJavaTypeName(methodProto.getInputType());
    methodContext.outputType = typeMap.toJavaTypeName(methodProto.getOutputType());
    methodContext.deprecated = methodProto.getOptions() != null && methodProto.getOptions().getDeprecated();
    methodContext.isManyInput = methodProto.getClientStreaming();
    methodContext.isManyOutput = methodProto.getServerStreaming();
    methodContext.methodNumber = methodNumber;
    methodContext.javaDoc = getJavaDoc(getComments(methodLocation), METHOD_JAVADOC_PREFIX);

    if (!methodProto.getClientStreaming() && !methodProto.getServerStreaming()) {
      methodContext.simpleCallsMethodName = "unaryCall";
      methodContext.grpcCallsMethodName = "asyncUnaryCall";
    }

    if (!methodProto.getClientStreaming() && methodProto.getServerStreaming()) {
      methodContext.simpleCallsMethodName = "serverStreamingCall";
      methodContext.grpcCallsMethodName = "asyncServerStreamingCall";
    }

    if (methodProto.getClientStreaming() && !methodProto.getServerStreaming()) {
      methodContext.simpleCallsMethodName = "clientStreamingCall";
      methodContext.grpcCallsMethodName = "asyncClientStreamingCall";
    }

    if (methodProto.getClientStreaming() && methodProto.getServerStreaming()) {
      methodContext.simpleCallsMethodName = "bidirectionalStreamingCall";
      methodContext.grpcCallsMethodName = "asyncBidiStreamingCall";
    }

    return methodContext;
  }

  private static String lowerCaseFirst(final String s) {
    return Character.toLowerCase(s.charAt(0)) + s.substring(1);
  }

  private List<PluginProtos.CodeGeneratorResponse.File> generateFiles(final List<ServiceContext> services) {
    return services.stream()
        .map(this::buildFile)
        .collect(Collectors.toList());
  }

  private PluginProtos.CodeGeneratorResponse.File buildFile(final ServiceContext serviceContext) {
    return PluginProtos.CodeGeneratorResponse.File
        .newBuilder()
        .setName(getGeneratedFilename(serviceContext))
        .setContent(applyTemplate("SimpleStub.mustache", serviceContext))
        .build();
  }

  /**
   * Gets the relative path for a generated file. As the schema notes, this is:
   * <p>
   * <blockquote>The file name, relative to the output directory. The name must not contain "." or ".." components and
   * must be relative, not be absolute (so, the file cannot lie outside the output directory). "/" must be used as the
   * path separator, not "\".</blockquote>
   *
   * @param serviceContext the service context for which to get a generated filename
   *
   * @return the relative path to the generated file
   *
   * @see <a href="https://github.com/protocolbuffers/protobuf/blob/e8edc5d5e72fa091b0086b4a6d12af0bb66d664b/src/google/protobuf/compiler/plugin.proto#L119-L130">CodeGeneratorResponse.File.name</a>
   */
  @VisibleForTesting
  static String getGeneratedFilename(final ServiceContext serviceContext) {
    final String dir = serviceContext.packageName != null
        ? serviceContext.packageName.replace('.', '/')
        : null;

    if (Strings.isNullOrEmpty(dir)) {
      return serviceContext.fileName;
    } else {
      return dir + "/" + serviceContext.fileName;
    }
  }

  private static String getComments(final DescriptorProtos.SourceCodeInfo.Location location) {
    return location.getLeadingComments().isEmpty() ? location.getTrailingComments() : location.getLeadingComments();
  }

  private static String getJavaDoc(final String comments, final String prefix) {
    if (comments.isEmpty()) {
      return null;
    }

    final StringBuilder builder = new StringBuilder("/**\n")
        .append(prefix).append(" * <pre>\n");
    Arrays.stream(HtmlEscapers.htmlEscaper().escape(comments).split("\n"))
        .map(line -> line.replace("*/", "&#42;&#47;").replace("*", "&#42;"))
        .forEach(line -> builder.append(prefix).append(" * ").append(line).append("\n"));

    builder
        .append(prefix).append(" * </pre>\n")
        .append(prefix).append(" */");

    return builder.toString();
  }

  /**
   * Template class for proto Service objects.
   */
  @VisibleForTesting
  static class ServiceContext {
    public String fileName;
    public String protoName;
    public String packageName;
    public String className;
    public String serviceName;
    public boolean deprecated;
    public String javaDoc;
    public List<MethodContext> methods = new ArrayList<>();

    // Although not used directly by the generator, templates may invoke this method
    @SuppressWarnings("unused")
    public List<MethodContext> unaryRequestMethods() {
      return methods.stream().filter(m -> !m.isManyInput).collect(Collectors.toList());
    }
  }

  /**
   * Template class for proto RPC objects.
   */
  @VisibleForTesting
  static class MethodContext {
    public String methodName;
    public String inputType;
    public String outputType;
    public boolean deprecated;
    public boolean isManyInput;
    public boolean isManyOutput;
    public String simpleCallsMethodName;
    public String grpcCallsMethodName;
    public int methodNumber;
    public String javaDoc;

    // This method mimics the upper-casing method of gRPC to ensure compatibility
    // See https://github.com/grpc/grpc-java/blob/v1.8.0/compiler/src/java_plugin/cpp/java_generator.cpp#L58
    // Although not used directly by the generator, templates may invoke this method
    @SuppressWarnings("unused")
    public String methodNameUpperUnderscore() {
      StringBuilder s = new StringBuilder();
      for (int i = 0; i < methodName.length(); i++) {
        char c = methodName.charAt(i);
        s.append(Character.toUpperCase(c));
        if ((i < methodName.length() - 1) && Character.isLowerCase(c) && Character.isUpperCase(methodName.charAt(i + 1))) {
          s.append('_');
        }
      }
      return s.toString();
    }

    // Although not used directly by the generator, templates may invoke this method
    @SuppressWarnings("unused")
    public String methodNamePascalCase() {
      String mn = methodName.replace("_", "");
      return Character.toUpperCase(mn.charAt(0)) + mn.substring(1);
    }

    // Although not used directly by the generator, templates may invoke this method
    @SuppressWarnings("unused")
    public String methodNameCamelCase() {
      String mn = methodName.replace("_", "");
      return Character.toLowerCase(mn.charAt(0)) + mn.substring(1);
    }
  }
}
