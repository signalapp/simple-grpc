/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.grpc.simple;

import com.google.protobuf.compiler.PluginProtos;
import com.salesforce.jprotoc.ProtocPluginTesting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SimpleGrpcGeneratorTest {

  @Test
  void verifyGenerator() throws IOException {
    final PluginProtos.CodeGeneratorResponse response =
        ProtocPluginTesting.test(new SimpleGrpcGenerator(), "target/generated-test-sources/protobuf/java/descriptor_dump");

    assertTrue(response.getError().isBlank());
    assertFalse(response.getFileList().isEmpty());
  }

  @ParameterizedTest
  @MethodSource
  void getGeneratedFilename(final SimpleGrpcGenerator.ServiceContext serviceContext, final String expectedFilename) {
    assertEquals(expectedFilename, SimpleGrpcGenerator.getGeneratedFilename(serviceContext));
  }

  private static List<Arguments> getGeneratedFilename() {
    final SimpleGrpcGenerator.ServiceContext serviceContextWithPackage = new SimpleGrpcGenerator.ServiceContext();
    serviceContextWithPackage.packageName = "com.example.test";
    serviceContextWithPackage.fileName = "Test.java";

    final SimpleGrpcGenerator.ServiceContext serviceContextWithBlankPackage = new SimpleGrpcGenerator.ServiceContext();
    serviceContextWithBlankPackage.packageName = "";
    serviceContextWithBlankPackage.fileName = "Test.java";

    final SimpleGrpcGenerator.ServiceContext serviceContextWithNullPackage = new SimpleGrpcGenerator.ServiceContext();
    serviceContextWithNullPackage.packageName = null;
    serviceContextWithNullPackage.fileName = "Test.java";

    return List.of(
        Arguments.argumentSet("Service context with package",
            serviceContextWithPackage, "com/example/test/Test.java"),

        Arguments.argumentSet("Service context with blank package",
            serviceContextWithBlankPackage, "Test.java"),

        Arguments.argumentSet("Service context with null package",
            serviceContextWithNullPackage, "Test.java")
    );
  }
}
