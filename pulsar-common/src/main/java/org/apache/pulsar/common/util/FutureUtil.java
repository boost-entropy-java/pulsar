/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.common.util;

import com.google.common.util.concurrent.MoreExecutors;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.concurrent.ThreadSafe;
import org.jspecify.annotations.NonNull;

/**
 * This class is aimed at simplifying work with {@code CompletableFuture}.
 */
public class FutureUtil {

    /**
     * Return a future that represents the completion of the futures in the provided Collection.
     *
     * @param futures futures to wait for
     * @return a new CompletableFuture that is completed when all of the given CompletableFutures complete
     */
    public static CompletableFuture<Void> waitForAll(Collection<? extends CompletableFuture<?>> futures) {
        if (futures == null || futures.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    public static CompletableFuture<Void> runWithCurrentThread(Runnable runnable) {
        return CompletableFuture.runAsync(
                () -> runnable.run(), MoreExecutors.directExecutor());
    }

    public static <T> CompletableFuture<List<T>> waitForAll(Stream<CompletableFuture<List<T>>> futures) {
        return futures.reduce(CompletableFuture.completedFuture(new ArrayList<>()),
                (pre, curr) -> pre.thenCompose(preV -> curr.thenApply(currV -> {
                    preV.addAll(currV);
                    return preV;
                })));
    }

    /**
     * Make the dest future complete after another one. {@param dest} is will be completed with the same value as
     * {@param src}, or be completed with the same error as {@param src}.
     */
    public static <T> void completeAfter(final CompletableFuture<T> dest, CompletableFuture<T> src) {
        src.whenComplete((v, ex) -> {
            if (ex != null) {
                dest.completeExceptionally(ex);
            } else {
                dest.complete(v);
            }
        });
    }

    /**
     * Make the dest future complete after others. {@param dest} is will be completed with a {@link Void} value
     * if all the futures of {@param src} is completed, or be completed exceptionally with the same error as the first
     * one completed exceptionally future of {@param src}.
     */
    public static void completeAfterAll(final CompletableFuture<Void> dest,
                                        CompletableFuture<? extends Object>... src) {
        FutureUtil.waitForAll(Arrays.asList(src)).whenComplete((ignore, ex) -> {
            if (ex != null) {
                dest.completeExceptionally(ex);
            } else {
                dest.complete(null);
            }
        });
    }

    /**
     * Return a future that represents the completion of any future in the provided Collection.
     *
     * @param futures futures to wait any
     * @return a new CompletableFuture that is completed when any of the given CompletableFutures complete
     */
    public static CompletableFuture<Object> waitForAny(Collection<? extends CompletableFuture<?>> futures) {
        return CompletableFuture.anyOf(futures.toArray(new CompletableFuture[0]));
    }

    /**
     * Return a future that represents the completion of any future that match the predicate in the provided Collection.
     *
     * @param futures futures to wait any
     * @param tester if any future match the predicate
     * @return a new CompletableFuture that is completed when any of the given CompletableFutures match the tester
     */
    public static CompletableFuture<Optional<Object>> waitForAny(Collection<? extends CompletableFuture<?>> futures,
                                                       Predicate<Object> tester) {
        return waitForAny(futures).thenCompose(v -> {
            if (tester.test(v)) {
                futures.forEach(f -> {
                    if (!f.isDone()) {
                        f.cancel(true);
                    }
                });
                return CompletableFuture.completedFuture(Optional.of(v));
            }
            Collection<CompletableFuture<?>> doneFutures = futures.stream()
                    .filter(f -> f.isDone())
                    .collect(Collectors.toList());
            futures.removeAll(doneFutures);
            Optional<?> value = doneFutures.stream()
                    .filter(f -> !f.isCompletedExceptionally())
                    .map(CompletableFuture::join)
                    .filter(tester)
                    .findFirst();
            if (!value.isPresent()) {
                if (futures.size() == 0) {
                    return CompletableFuture.completedFuture(Optional.empty());
                }
                return waitForAny(futures, tester);
            }
            futures.forEach(f -> {
                if (!f.isDone()) {
                    f.cancel(true);
                }
            });
            return CompletableFuture.completedFuture(Optional.of(value.get()));
        });
    }


    /**
     * Return a future that represents the completion of the futures in the provided Collection.
     * The future will support {@link CompletableFuture#cancel(boolean)}. It will cancel
     * all unfinished futures when the future gets cancelled.
     *
     * @param futures futures to wait for
     * @return a new CompletableFuture that is completed when all of the given CompletableFutures complete
     */
    public static CompletableFuture<Void> waitForAllAndSupportCancel(
            Collection<? extends CompletableFuture<?>> futures) {
        CompletableFuture[] futuresArray = futures.toArray(new CompletableFuture[0]);
        CompletableFuture<Void> combinedFuture = CompletableFuture.allOf(futuresArray);
        whenCancelledOrTimedOut(combinedFuture, () -> {
            for (CompletableFuture completableFuture : futuresArray) {
                if (!completableFuture.isDone()) {
                    completableFuture.cancel(false);
                }
            }
        });
        return combinedFuture;
    }

    /**
     * If the future is cancelled or times out, the cancel action will be invoked.
     *
     * The action is executed once if the future completes with
     * {@link java.util.concurrent.CancellationException} or {@link TimeoutException}
     *
     * @param future future to attach the action to
     * @param cancelAction action to invoke if the future is cancelled or times out
     */
    public static void whenCancelledOrTimedOut(CompletableFuture<?> future, Runnable cancelAction) {
        CompletableFutureCancellationHandler cancellationHandler =
                new CompletableFutureCancellationHandler();
        cancellationHandler.setCancelAction(cancelAction);
        cancellationHandler.attachToFuture(future);
    }

    public static <T> CompletableFuture<T> failedFuture(Throwable t) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(t);
        return future;
    }

    public static Throwable unwrapCompletionException(Throwable ex) {
        if (ex instanceof CompletionException) {
            return unwrapCompletionException(ex.getCause());
        } else if (ex instanceof ExecutionException) {
            return unwrapCompletionException(ex.getCause());
        } else {
            return ex;
        }
    }

    @ThreadSafe
    public static class Sequencer<T> {
        private CompletableFuture<T> sequencerFuture = CompletableFuture.completedFuture(null);
        private final boolean allowExceptionBreakChain;

        public Sequencer(boolean allowExceptionBreakChain) {
            this.allowExceptionBreakChain = allowExceptionBreakChain;
        }

        public static <T> Sequencer<T> create(boolean allowExceptionBreakChain) {
            return new Sequencer<>(allowExceptionBreakChain);
        }
        public static <T> Sequencer<T> create() {
            return new Sequencer<>(false);
        }

        /**
         * @throws NullPointerException NPE when param is null
         */
        public synchronized CompletableFuture<T> sequential(Supplier<CompletableFuture<T>> newTask) {
            Objects.requireNonNull(newTask);
            if (sequencerFuture.isDone()) {
                if (sequencerFuture.isCompletedExceptionally() && allowExceptionBreakChain) {
                    return sequencerFuture;
                }
                return sequencerFuture = newTask.get();
            }
            return sequencerFuture = allowExceptionBreakChain
                    ? sequencerFuture.thenCompose(__ -> newTask.get())
                    : sequencerFuture.exceptionally(ex -> null).thenCompose(__ -> newTask.get());
        }
    }

    /**
     * Creates a new {@link CompletableFuture} instance with timeout handling.
     *
     * @param timeout the duration of the timeout
     * @param executor the executor to use for scheduling the timeout
     * @param exceptionSupplier the supplier for creating the exception
     * @param <T> type parameter for the future
     * @return the new {@link CompletableFuture} instance
     */
    public static <T> CompletableFuture<T> createFutureWithTimeout(Duration timeout,
                                                                   ScheduledExecutorService executor,
                                                                   Supplier<Throwable> exceptionSupplier) {
        return addTimeoutHandling(new CompletableFuture<>(), timeout, executor, exceptionSupplier);
    }

    /**
     * Adds timeout handling to an existing {@link CompletableFuture}.
     *
     * @param future the target future
     * @param timeout the duration of the timeout
     * @param executor the executor to use for scheduling the timeout
     * @param exceptionSupplier the supplier for creating the exception
     * @param <T> type parameter for the future
     * @return returns the original target future
     */
    public static <T> CompletableFuture<T> addTimeoutHandling(CompletableFuture<T> future, Duration timeout,
                                               ScheduledExecutorService executor,
                                               Supplier<Throwable> exceptionSupplier) {
        ScheduledFuture<?> scheduledFuture = executor.schedule(() -> {
            if (!future.isDone()) {
                future.completeExceptionally(exceptionSupplier.get());
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        future.whenComplete((res, exception) -> scheduledFuture.cancel(false));
        return future;
    }

    /**
     * @throws RejectedExecutionException if this task cannot be accepted for execution
     * @throws NullPointerException if one of params is null
     */
    public static <T> @NonNull CompletableFuture<T> composeAsync(Supplier<CompletableFuture<T>> futureSupplier,
                                                                 Executor executor) {
        Objects.requireNonNull(futureSupplier);
        Objects.requireNonNull(executor);
        final CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> futureSupplier.get().whenComplete((result, error) -> {
                if (error != null) {
                    future.completeExceptionally(error);
                    return;
                }
                future.complete(result);
            }));
        } catch (RejectedExecutionException ex) {
            future.completeExceptionally(ex);
        }
        return future;
    }


    /**
     * Creates a low-overhead timeout exception which is performance optimized to minimize allocations
     * and cpu consumption. It sets the stacktrace of the exception to the given source class and
     * source method name. The instances of this class can be cached or stored as constants and reused
     * multiple times.
     *
     * @param message exception message
     * @param sourceClass source class for manually filled in stacktrace
     * @param sourceMethod source method name for manually filled in stacktrace
     * @return new TimeoutException instance
     */
    public static TimeoutException createTimeoutException(String message, Class<?> sourceClass, String sourceMethod) {
        return new LowOverheadTimeoutException(message, sourceClass, sourceMethod);
    }

    private static class LowOverheadTimeoutException extends TimeoutException {
        private static final long serialVersionUID = 1L;

        LowOverheadTimeoutException(String message, Class<?> sourceClass, String sourceMethod) {
            super(message);
            setStackTrace(new StackTraceElement[]{new StackTraceElement(sourceClass.getName(), sourceMethod,
                    null, -1)});
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    public static <T> Optional<Throwable> getException(CompletableFuture<T> future) {
        if (future != null && future.isCompletedExceptionally()) {
            try {
                future.get();
            } catch (InterruptedException e) {
                return Optional.ofNullable(e);
            } catch (ExecutionException e) {
                return Optional.ofNullable(e.getCause());
            }
        }
        return Optional.empty();
    }

    /**
     * Wrap throwable exception to CompletionException if that exception is not an instance of CompletionException.
     *
     * @param throwable Exception
     * @return CompletionException
     */
    public static CompletionException wrapToCompletionException(Throwable throwable) {
        if (throwable instanceof CompletionException) {
            return (CompletionException) throwable;
        } else {
            return new CompletionException(throwable);
        }
    }

    /**
     * Executes an operation using the supplied {@link Executor}
     * and notify failures on the supplied {@link CompletableFuture}.
     *
     * @param runnable the runnable to execute
     * @param executor  the executor to use for executing the runnable
     * @param completableFuture  the future to complete in case of exceptions
     * @return
     */

    public static void safeRunAsync(Runnable runnable,
                                    Executor executor,
                                    CompletableFuture completableFuture) {
        CompletableFuture
                .runAsync(runnable, executor)
                .exceptionally((throwable) -> {
                    completableFuture.completeExceptionally(throwable);
                    return null;
                });
    }

    /**
     * Blocks to get the result of a CompletableFuture, while ensuring resources are cleaned up
     * if the wait is interrupted.
     * <p>
     * If the current thread is interrupted while waiting, this method registers a cleanup action
     * to be executed when the future eventually completes. This prevents resource leaks that
     * could otherwise occur when an interruption happens but the underlying asynchronous task
     * finishes successfully later. After registering the action, it re-throws the
     * {@link InterruptedException}.
     *
     * @param future         The CompletableFuture to wait for.
     * @param cleanupAction  A consumer that performs a cleanup action (e.g., closing a resource)
     * on the result if the wait is interrupted.
     * @param <T>            The type of the future's result.
     * @return The computed result from the future.
     * @throws InterruptedException if the current thread was interrupted while waiting.
     * @throws ExecutionException   if the future completed exceptionally.
     */
    public static <T> T getAndCleanupOnInterrupt(CompletableFuture<T> future, Consumer<T> cleanupAction)
            throws InterruptedException, ExecutionException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            future.thenAccept(cleanupAction);
            throw e;
        }
    }
}
