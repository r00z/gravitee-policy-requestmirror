/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.policy.requestmirror;

import io.gravitee.gateway.api.Request;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.handler.Handler;
import io.gravitee.gateway.api.proxy.ProxyConnection;
import io.gravitee.gateway.api.proxy.ProxyResponse;
import io.gravitee.gateway.api.stream.WriteStream;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

public class RequestMirrorConnection implements ProxyConnection {
    private static final Logger logger = LoggerFactory.getLogger(RequestMirrorConnection.class);

    private final HttpClient httpClient;
    private final Request originalRequest;

    private Handler<ProxyResponse> responseHandler;
    private Buffer content;
    private String url = "";

    public RequestMirrorConnection(ExecutionContext executionContext, RequestMirrorPolicyConfiguration configuration) {
        url = configuration.getUrl();
        originalRequest = executionContext.request();
        Vertx vertx = executionContext.getComponent(Vertx.class);
        this.httpClient = vertx.createHttpClient();
    }

    @Override
    public WriteStream<Buffer> write(Buffer chunk) {
        if (content == null) {
            content = Buffer.buffer();
        }
        content.appendBuffer(chunk);
        return this;
    }

    @Override
    public void end() {
        String messageBody = (content != null) ? content.toString() : "";
        HttpMethod httpMethod = HttpMethod.valueOf(originalRequest.method().name());
        logger.info("Message body: " + messageBody + ", url: " + url + ", method: " + httpMethod);
        try {
            URL urlObject = new URL(url);

            HttpClientRequest clientRequest = httpClient.requestAbs(httpMethod, url, done -> {
                logger.info("Response code: " + done.statusCode() + ", statusMessage: " + done.statusMessage() );
                done.bodyHandler(buffer -> {
                    logger.debug("Body: " + buffer.getString(0, buffer.length()));
                });
            });

            MultiMap headers = clientRequest.headers();
            if (originalRequest.headers() != null && !originalRequest.headers().isEmpty()) {
                Map<String, String> originalHeaders = originalRequest.headers().toSingleValueMap();
                headers.setAll(originalHeaders);
            }
            clientRequest.connectionHandler(connection -> {
                connection.exceptionHandler(ex -> {
                    logger.error("Connection exception ", ex);
                });
            });

            clientRequest.exceptionHandler(event -> {
                logger.error("Server exception", event);
            });

            if (!messageBody.isEmpty() && (originalRequest.method().equals(io.gravitee.common.http.HttpMethod.POST)
                                            || originalRequest.method().equals(io.gravitee.common.http.HttpMethod.PUT)
                                            || originalRequest.method().equals(io.gravitee.common.http.HttpMethod.PATCH))) {
                clientRequest.write(messageBody);
            }
            clientRequest.end();
        } catch (MalformedURLException e) {
            logger.error("Invalid URL: " + url);
        } catch (Exception e) {
            logger.error("General exception: ", e);
        }

        responseHandler.handle(new RequestMirrorResponse());
    }

    @Override
    public ProxyConnection responseHandler(Handler<ProxyResponse> responseHandler) {
        this.responseHandler = responseHandler;
        return this;
    }
}
