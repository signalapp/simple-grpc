/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.grpc.simple;

/**
 * Represents a function that accepts one argument and produces a result and may throw a checked exception.
 *
 * @param <T> the type of the input to the function
 * @param <R> the type of the result of the function
 */
@FunctionalInterface
public interface CheckedFunction<T, R> {

  /**
   * Applies this function to the given argument.
   *
   * @param argument the function argument
   *
   * @return the function result
   *
   * @throws Exception in the event of an error of any kind when applying the function to the given argument
   */
  R apply(T argument) throws Exception;
}
