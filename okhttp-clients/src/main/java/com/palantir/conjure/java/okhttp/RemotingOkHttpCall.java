/*
 * (c) Copyright 2017 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.conjure.java.okhttp;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.netflix.concurrency.limits.Limiter;
import com.palantir.conjure.java.api.errors.QosException;
import com.palantir.conjure.java.api.errors.RemoteException;
import com.palantir.conjure.java.api.errors.UnknownRemoteException;
import com.palantir.conjure.java.client.config.ClientConfiguration;
import com.palantir.logsafe.Preconditions;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.UnsafeArg;
import com.palantir.logsafe.exceptions.SafeIllegalStateException;
import com.palantir.logsafe.exceptions.SafeIoException;
import com.palantir.logsafe.logger.SafeLogger;
import com.palantir.logsafe.logger.SafeLoggerFactory;
import com.palantir.tracing.DetachedSpan;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * An OkHttp {@link Call} implementation that handles standard retryable error status such as 308, 429, 503, and
 * connection errors. Calls are rescheduled on a given scheduler and executed on a given executor.
 */
final class RemotingOkHttpCall extends ForwardingCall {

    private static final SafeLogger log = SafeLoggerFactory.get(RemotingOkHttpCall.class);

    private static final ResponseHandler<RemoteException> remoteExceptionHandler =
            RemoteExceptionResponseHandler.INSTANCE;
    private static final ResponseHandler<UnknownRemoteException> unknownRemoteExceptionHandler =
            UnknownRemoteExceptionResponseHandler.INSTANCE;
    private static final ResponseHandler<QosException> qosHandler = QosExceptionResponseHandler.INSTANCE;

    private final BackoffStrategy backoffStrategy;
    private final UrlSelector urls;
    private final RemotingOkHttpClient client;
    private final ScheduledExecutorService schedulingExecutor;
    private final ExecutorService executionExecutor;
    private final ConcurrencyLimiters.ConcurrencyLimiter limiter;
    private final ClientConfiguration.ServerQoS serverQoS;
    private final ClientConfiguration.RetryOnTimeout retryOnTimeout;
    private final ClientConfiguration.RetryOnSocketException retryOnSocketException;
    // Previous call in the chain if this is a retry request
    private final Optional<Call> previous;

    private final int maxNumRelocations;

    RemotingOkHttpCall(
            Call delegate,
            Optional<Call> previous,
            BackoffStrategy backoffStrategy,
            UrlSelector urls,
            RemotingOkHttpClient client,
            ScheduledExecutorService schedulingExecutor,
            ExecutorService executionExecutor,
            ConcurrencyLimiters.ConcurrencyLimiter limiter,
            int maxNumRelocations,
            ClientConfiguration.ServerQoS serverQoS,
            ClientConfiguration.RetryOnTimeout retryOnTimeout,
            ClientConfiguration.RetryOnSocketException retryOnSocketException) {
        super(delegate);
        this.previous = previous;
        this.backoffStrategy = backoffStrategy;
        this.urls = urls;
        this.client = client;
        this.schedulingExecutor = schedulingExecutor;
        this.executionExecutor = executionExecutor;
        this.limiter = limiter;
        this.maxNumRelocations = maxNumRelocations;
        this.serverQoS = serverQoS;
        this.retryOnTimeout = retryOnTimeout;
        this.retryOnSocketException = retryOnSocketException;
    }

    /**
     * Process the call. If an IOException is encountered, mark the URL as failed, which indicates that it should be
     * avoided for subsequent calls (if {@link UrlSelector} was initialized with a positive
     * {@link ClientConfiguration#failedUrlCooldown()}.
     */
    @Override
    public Response execute() throws IOException {
        SettableFuture<Response> future = SettableFuture.create();
        enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException exception) {
                if (!future.setException(exception)) {
                    log.warn(
                            "Future has already completed",
                            UnsafeArg.of("requestUrl", call.request().url().toString()),
                            exception);
                }
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (!future.set(response)) {
                    close(response);
                    log.warn(
                            "Future has already completed, closing the response",
                            UnsafeArg.of("requestUrl", call.request().url().toString()));
                }
            }
        });

        try {
            // We don't enforce a timeout here because it's not possible to know how long this operation might take.
            // First, it might get queued indefinitely in the Dispatcher, and then it might get retried a (potentially)
            // unknown amount of times by the BackoffStrategy. The {@code get} call times out when the underlying
            // OkHttp call times out (, possibly after a number of retries).
            return future.get();
        } catch (InterruptedException e) {
            getDelegate().cancel();
            // Regardless of the cancel above, the call may have succeeded or is going to succeed, and we need to make
            // sure the response body is closed correctly in those cases.
            Futures.addCallback(future, ResponseClosingCallback.INSTANCE, MoreExecutors.directExecutor());
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Call cancelled via interruption");
        } catch (ExecutionException e) {
            getDelegate().cancel();
            if (e.getCause() instanceof IoRemoteException) {
                // TODO(rfink): Consider unwrapping the RemoteException at the Retrofit/Feign layer for symmetry, #626
                RemoteException wrappedException = ((IoRemoteException) e.getCause()).getWrappedException();
                RemoteException correctStackTrace =
                        new RemoteException(wrappedException.getError(), wrappedException.getStatus());
                correctStackTrace.initCause(e);
                throw correctStackTrace;
            } else if (e.getCause() instanceof IoUnknownRemoteException) {
                UnknownRemoteException wrappedException =
                        ((IoUnknownRemoteException) e.getCause()).getUnknownRemoteException();
                UnknownRemoteException correctStackTrace =
                        new UnknownRemoteException(wrappedException.getStatus(), wrappedException.getBody());
                correctStackTrace.initCause(e);
                throw correctStackTrace;
            } else if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            } else {
                throw new SafeIoException("Failed to execute call", e);
            }
        }
    }

    private static Response buildFrom(Response unbufferedResponse, byte[] bodyBytes) {
        return unbufferedResponse
                .newBuilder()
                .body(
                        bodyBytes == null
                                ? null
                                : buffer(
                                        Preconditions.checkNotNull(
                                                        unbufferedResponse.body(), "Expected a response body")
                                                .contentType(),
                                        bodyBytes))
                .build();
    }

    private static ResponseBody buffer(MediaType mediaType, byte[] bodyBytes) {
        return ResponseBody.create(mediaType, bodyBytes);
    }

    @Override
    public void enqueue(Callback callback) {
        DetachedSpan attemptSpan = request().tag(Tags.AttemptSpan.class).attemptSpan();
        DetachedSpan concurrencyLimiterSpan = attemptSpan.childDetachedSpan(limiter.spanName());
        ListenableFuture<Limiter.Listener> limiterListener = limiter.acquire();
        request().tag(ConcurrencyLimiterListener.class).setLimiterListener(limiterListener);
        Futures.addCallback(
                limiterListener,
                new FutureCallback<Limiter.Listener>() {
                    @Override
                    public void onSuccess(Limiter.Listener _listener) {
                        concurrencyLimiterSpan.complete();
                        DetachedSpan dispatcherSpan = attemptSpan.childDetachedSpan("OkHttp: dispatcher");
                        request().tag(Tags.SettableDispatcherSpan.class).setDispatcherSpan(dispatcherSpan);
                        enqueueClosingEntireSpan(callback);
                    }

                    @Override
                    public void onFailure(Throwable throwable) {
                        callback.onFailure(
                                RemotingOkHttpCall.this,
                                new SafeIoException(
                                        "This should never happen, since it implies "
                                                + "we failed when using the concurrency limiter",
                                        throwable));
                    }
                },
                MoreExecutors.directExecutor());
    }

    @Override
    public boolean isCanceled() {
        return getDelegate().isCanceled() || previous.map(Call::isCanceled).orElse(Boolean.FALSE);
    }

    private void enqueueClosingEntireSpan(Callback callback) {
        enqueueInternal(new Callback() {
            @Override
            public void onFailure(Call call, IOException exception) {
                try {
                    call.request().tag(Tags.EntireSpan.class).get().complete();
                } finally {
                    callback.onFailure(call, exception);
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    call.request().tag(Tags.EntireSpan.class).get().complete();
                } finally {
                    callback.onResponse(call, response);
                }
            }
        });
    }

    private void enqueueInternal(Callback callback) {
        super.enqueue(new LeakedResponseClosingCallback(new Callback() {
            @Override
            public void onFailure(Call call, IOException exception) {
                if (isCanceled()) {
                    callback.onFailure(call, exception);
                    return;
                }

                urls.markAsFailed(request().url());

                // Fail call if backoffs are exhausted or if no retry URL can be determined.
                Optional<Duration> backoff = backoffStrategy.nextBackoff();
                if (!shouldRetry(exception, backoff)) {
                    callback.onFailure(
                            call,
                            new SafeIoException(
                                    "Failed to complete the request due to an IOException",
                                    exception,
                                    UnsafeArg.of(
                                            "requestUrl", call.request().url().toString())));
                    return;
                }

                Optional<HttpUrl> redirectTo = urls.redirectToNext(request().url());
                if (!redirectTo.isPresent()) {
                    callback.onFailure(
                            call,
                            new SafeIoException(
                                    "Failed to determine valid failover URL",
                                    exception,
                                    UnsafeArg.of(
                                            "requestUrl", call.request().url().toString()),
                                    UnsafeArg.of("baseUrls", urls.getBaseUrls())));
                    return;
                }

                retryIfAllowed(callback, call, exception, () -> {
                    if (log.isInfoEnabled()) {
                        log.info(
                                "Retrying call after failure",
                                SafeArg.of("backoffMillis", backoff.get().toMillis()),
                                // toString required because this object is mutable, and the logger may
                                // format this value asynchronously.
                                SafeArg.of("backoffState", backoffStrategy.toString()),
                                UnsafeArg.of("requestUrl", call.request().url().toString()),
                                UnsafeArg.of("redirectToUrl", redirectTo.get().toString()),
                                exception);
                    }
                    Tags.AttemptSpan nextAttempt = createNextAttempt();
                    Request redirectedRequest = request()
                            .newBuilder()
                            .url(redirectTo.get())
                            .tag(Tags.AttemptSpan.class, nextAttempt)
                            .build();
                    RemotingOkHttpCall retryCall = client.newCallWithMutableState(
                            redirectedRequest, backoffStrategy, maxNumRelocations - 1, Optional.of(call));
                    scheduleExecution(backoff.get(), nextAttempt, retryCall, callback);
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                urls.markAsSucceeded(request().url());

                // Relay successful responses
                if (response.code() / 100 <= 2) {
                    callback.onResponse(call, response);

                    return;
                }

                ResponseBody maybeResponseBody = response.body();
                // Buffer the response into a byte[] so that multiple handler can safely consume the body.
                // This consumes and closes the original response body.
                Supplier<Response> errorResponseSupplier;
                try {
                    byte[] body = maybeResponseBody == null ? null : maybeResponseBody.bytes();
                    // so error handlers can read the body without breaking subsequent handlers
                    errorResponseSupplier = () -> buildFrom(response, body);
                } catch (IOException e) {
                    onFailure(call, e);
                    return;
                } finally {
                    close(response);
                }

                // Handle to handle QoS situations: retry, failover, etc.
                Optional<QosException> qosError = qosHandler.handle(errorResponseSupplier.get());
                if (qosError.isPresent()) {
                    qosError.get().accept(createQosVisitor(callback, call, errorResponseSupplier.get()));
                    return;
                }

                // Handle responses that correspond to RemoteExceptions / SerializableErrors
                Optional<RemoteException> httpError = remoteExceptionHandler.handle(errorResponseSupplier.get());
                if (httpError.isPresent()) {
                    callback.onFailure(call, new IoRemoteException(httpError.get()));
                    return;
                }

                // Catch-all: handle all other responses
                Optional<UnknownRemoteException> unknownHttpError =
                        unknownRemoteExceptionHandler.handle(errorResponseSupplier.get());
                if (unknownHttpError.isPresent()) {
                    callback.onFailure(call, new IoUnknownRemoteException(unknownHttpError.get()));
                    return;
                }

                callback.onFailure(
                        call, new SafeIoException("Failed to handle request, this is an conjure-java-runtime bug."));
            }
        }));
    }

    private static final class LeakedResponseClosingCallback implements Callback {

        private final Callback delegate;

        LeakedResponseClosingCallback(Callback delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onFailure(Call call, IOException exception) {
            ResponseCapturingInterceptor.getResponse().ifPresent(RemotingOkHttpCall::close);
            ResponseCapturingInterceptor.clearThreadState();
            delegate.onFailure(call, exception);
        }

        @Override
        public void onResponse(Call call, Response response) throws IOException {
            ResponseCapturingInterceptor.clearThreadState();
            delegate.onResponse(call, response);
        }
    }

    private boolean shouldRetry(IOException exception, Optional<Duration> backoff) {
        if (retryOnSocketException == ClientConfiguration.RetryOnSocketException.DANGEROUS_DISABLED) {
            return false;
        }
        switch (retryOnTimeout) {
            case DISABLED:
                if (exception instanceof SocketTimeoutException) {
                    // non-connect timeouts should not be retried
                    SocketTimeoutException socketTimeout = (SocketTimeoutException) exception;
                    // in java 14 "connect timed out" renamed to "Connect timed out"
                    if (socketTimeout.getMessage() == null
                            || !socketTimeout.getMessage().contains("connect timed out")
                                    && !socketTimeout.getMessage().contains("Connect timed out")) {
                        return false;
                    }
                }
                return backoff.isPresent();
            case DANGEROUS_ENABLE_AT_RISK_OF_RETRY_STORMS:
                return backoff.isPresent();
        }

        throw new SafeIllegalStateException(
                "Encountered unknown retry on timeout configuration", SafeArg.of("retryOnTimeout", retryOnTimeout));
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void scheduleExecution(Duration backoff, Tags.AttemptSpan attemptSpan, Call nextCall, Callback callback) {
        DetachedSpan backoffSpan = attemptSpan.attemptSpan().childDetachedSpan("OkHttp: backoff-with-jitter");

        // TODO(rfink): Investigate whether ignoring the ScheduledFuture is safe, #629.
        schedulingExecutor.schedule(
                () -> executionExecutor.execute(() -> {
                    backoffSpan.complete();
                    if (isCanceled()) {
                        callback.onFailure(this, new SafeIoException("Request is cancelled"));
                    } else {
                        nextCall.enqueue(callback);
                    }
                }),
                backoff.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private QosException.Visitor<Void> createQosVisitor(Callback callback, Call call, Response response) {
        return new QosException.Visitor<Void>() {
            @Override
            public Void visit(QosException.Throttle exception) {
                if (shouldPropagateQos(serverQoS)) {
                    propagateResponse(callback, call, response);
                    return null;
                }

                Optional<Duration> nonAdvertizedBackoff = backoffStrategy.nextBackoff();
                if (!nonAdvertizedBackoff.isPresent()) {
                    callback.onFailure(
                            call,
                            new SafeIoException(
                                    "Failed to complete the request due to QosException.Throttle",
                                    exception,
                                    UnsafeArg.of(
                                            "requestUrl", call.request().url().toString())));
                    return null;
                }

                retryIfAllowed(callback, call, exception, () -> {
                    Duration backoff = exception.getRetryAfter().orElseGet(nonAdvertizedBackoff::get);
                    log.debug(
                            "Rescheduling call after receiving QosException.Throttle",
                            SafeArg.of("backoffMillis", backoff.toMillis()),
                            exception);
                    // Must create this before scheduling, so the attempt starts right now.
                    Tags.AttemptSpan nextAttempt = createNextAttempt();
                    Request nextAttemptRequest = request()
                            .newBuilder()
                            .tag(Tags.AttemptSpan.class, nextAttempt)
                            .build();
                    RemotingOkHttpCall nextCall = client.newCallWithMutableState(
                            nextAttemptRequest, backoffStrategy, maxNumRelocations, Optional.of(call));
                    scheduleExecution(backoff, nextAttempt, nextCall, callback);
                });
                return null;
            }

            @Override
            public Void visit(QosException.RetryOther exception) {
                if (maxNumRelocations <= 0) {
                    callback.onFailure(
                            call,
                            new SafeIoException(
                                    "Exceeded the maximum number of allowed redirects",
                                    exception,
                                    UnsafeArg.of(
                                            "requestUrl", call.request().url().toString())));
                    return null;
                }

                // Redirect to the URL specified by the exception.
                Optional<HttpUrl> redirectTo = urls.redirectTo(
                        request().url(), exception.getRedirectTo().toString());
                if (!redirectTo.isPresent()) {
                    callback.onFailure(
                            call,
                            new SafeIoException(
                                    "Failed to determine valid redirect URL after receiving QosException.RetryOther",
                                    exception,
                                    UnsafeArg.of(
                                            "requestUrl", call.request().url().toString()),
                                    UnsafeArg.of(
                                            "redirectToUrl",
                                            exception.getRedirectTo().toString()),
                                    UnsafeArg.of("baseUrls", urls.getBaseUrls())));
                    return null;
                }

                retryIfAllowed(callback, call, exception, () -> {
                    log.debug(
                            "Retrying call after receiving QosException.RetryOther",
                            UnsafeArg.of("requestUrl", call.request().url()),
                            UnsafeArg.of("redirectToUrl", redirectTo.get()),
                            exception);
                    Tags.AttemptSpan nextAttempt = createNextAttempt();
                    Request redirectedRequest = request()
                            .newBuilder()
                            .tag(Tags.AttemptSpan.class, nextAttempt)
                            .url(redirectTo.get())
                            .build();
                    client.newCallWithMutableState(
                                    redirectedRequest, backoffStrategy, maxNumRelocations - 1, Optional.of(call))
                            .enqueue(callback);
                });

                return null;
            }

            @Override
            public Void visit(QosException.Unavailable exception) {
                if (shouldPropagateQos(serverQoS)) {
                    propagateResponse(callback, call, response);
                    return null;
                }

                Optional<Duration> backoff = backoffStrategy.nextBackoff();
                if (!backoff.isPresent()) {
                    callback.onFailure(
                            call,
                            new SafeIoException(
                                    "Failed to complete the request due to QosException.Unavailable",
                                    exception,
                                    UnsafeArg.of(
                                            "requestUrl", call.request().url().toString())));
                    return null;
                }

                // Redirect to the "next" URL, whichever that may be, after backing off.
                Optional<HttpUrl> redirectTo = urls.redirectToNext(request().url());
                if (!redirectTo.isPresent()) {
                    callback.onFailure(
                            call,
                            new SafeIoException(
                                    "Failed to determine valid redirect URL after receiving QosException.Unavailable",
                                    UnsafeArg.of(
                                            "requestUrl", call.request().url().toString()),
                                    UnsafeArg.of("baseUrls", urls.getBaseUrls())));
                    return null;
                }

                retryIfAllowed(callback, call, exception, () -> {
                    log.debug(
                            "Retrying call after receiving QosException.Unavailable",
                            SafeArg.of("backoffMillis", backoff.get().toMillis()),
                            UnsafeArg.of("redirectToUrl", redirectTo.get()),
                            exception);
                    Tags.AttemptSpan nextAttempt = createNextAttempt();
                    Request redirectedRequest = request()
                            .newBuilder()
                            .tag(Tags.AttemptSpan.class, nextAttempt)
                            .url(redirectTo.get())
                            .build();
                    scheduleExecution(
                            backoff.get(),
                            nextAttempt,
                            client.newCallWithMutableState(
                                    redirectedRequest, backoffStrategy, maxNumRelocations, Optional.of(call)),
                            callback);
                });
                return null;
            }
        };
    }

    private static void retryIfAllowed(Callback callback, Call call, Exception exception, Runnable retryScheduler) {
        if (isStreamingBody(call)) {
            callback.onFailure(call, new SafeIoException("Cannot retry streamed HTTP body", exception));
        } else {
            retryScheduler.run();
        }
    }

    private static boolean isStreamingBody(Call call) {
        return call.request().body() != null && call.request().body().isOneShot();
    }

    private static boolean shouldPropagateQos(ClientConfiguration.ServerQoS serverQoS) {
        switch (serverQoS) {
            case PROPAGATE_429_and_503_TO_CALLER:
                return true;
            case AUTOMATIC_RETRY:
                return false;
        }

        throw new SafeIllegalStateException(
                "Encountered unknown propagate QoS configuration", SafeArg.of("serverQoS", serverQoS));
    }

    private static void propagateResponse(Callback callback, Call call, Response response) {
        try {
            callback.onResponse(call, response);
        } catch (IOException e) {
            callback.onFailure(call, e);
        }
    }

    // TODO(rfink): Consider removing RemotingOkHttpCall#doClone method, #627
    @Override
    public RemotingOkHttpCall doClone() {
        return new RemotingOkHttpCall(
                getDelegate().clone(),
                Optional.empty(),
                backoffStrategy,
                urls,
                client,
                schedulingExecutor,
                executionExecutor,
                limiter,
                maxNumRelocations,
                serverQoS,
                retryOnTimeout,
                retryOnSocketException);
    }

    private Tags.AttemptSpan createNextAttempt() {
        Tags.AttemptSpan previousAttempt = request().tag(Tags.AttemptSpan.class);
        DetachedSpan entireSpan = request().tag(Tags.EntireSpan.class).get();
        return previousAttempt.nextAttempt(entireSpan);
    }

    private enum ResponseClosingCallback implements FutureCallback<Response> {
        INSTANCE;

        @Override
        public void onSuccess(Response response) {
            close(response);
        }

        @Override
        public void onFailure(Throwable _throwable) {
            // do nothing
        }
    }

    private static void close(Response response) {
        if (response != null && response.body() != null) {
            response.close();
        }
    }

    private static final class IoUnknownRemoteException extends IOException {

        private final UnknownRemoteException unknownRemoteException;

        private IoUnknownRemoteException(UnknownRemoteException unknownRemoteException) {
            super(unknownRemoteException);
            this.unknownRemoteException = unknownRemoteException;
        }

        private UnknownRemoteException getUnknownRemoteException() {
            return unknownRemoteException;
        }
    }
}
