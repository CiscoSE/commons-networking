package com.cisco.commons.networking;


import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.ssl.TrustStrategy;

import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * SSE (Server-sent events) client.
 * Server-Sent Events (SSE) is a <a href="https://en.wikipedia.org/wiki/Push_technology">server push</a> technology 
 * enabling a client to receive automatic updates from a server via HTTP connection. <br/>
 * The Server-Sent Events EventSource API is standardized as part of 
 * <a href="https://www.w3.org/TR/eventsource/">HTML5</a> by the W3C. <br/>
 * It is used for unidirectional server to client events, as opposed to the full-duplex bidirectional WebSockets. <br/>
 * <br/>
 * The SSE client implementation is based on Java 11 HttpClient.
 * The SSE client has reconnect sampling mechanism with a default time of one minute. <br/>
 * The SSE client has connectivity refresh mechanism with default time of 24 hours. <br/>
 * 
 * Copyright 2021 Cisco Systems
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </pre>
 * 
 * @author Liran Mendelovich
 */
@Slf4j
public class SSEClient {

    private static final long DEFAULT_RECONNECT_SAMPLING_TIME_MILLIS = 60L * 1000L;
    private static final long DEFAULT_CONNECTIVITY_REFRESH_DELAY = 24;
    private static final TimeUnit DEFAULT_CONNECTIVITY_REFRESH_DELAY_TIME_UNIT = TimeUnit.HOURS;
    
	private String url;
	private Map<String, String> headerParams;
	private EventHandler eventHandler;
	private long reconnectSamplingTimeMillis = DEFAULT_RECONNECT_SAMPLING_TIME_MILLIS;
	private long connectivityRefreshDelay = DEFAULT_CONNECTIVITY_REFRESH_DELAY;
	private TimeUnit connectivityRefreshDelayTimeUnit = DEFAULT_CONNECTIVITY_REFRESH_DELAY_TIME_UNIT;
	
    private InputStream inputStream;
    private SSLContext sslContext;

    private AtomicBoolean shouldRun = new AtomicBoolean(true);
    private AtomicBoolean shouldSkipSleep = new AtomicBoolean(false);
    private String threadName;
    private SubscribeStatus status = SubscribeStatus.NOT_STARTED;
    
    private ExecutorService pool;
    private ScheduledExecutorService connectivityRefreshPoolScheduler;
    
    public enum SubscribeStatus {
        SUCCESS, FAILING, RECONNECTING, NOT_STARTED, STOPPED
    }

    @Builder
    public SSEClient(String url, Map<String, String> headerParams, EventHandler eventHandler,
    		Long reconnectSamplingTimeMillis, Long connectivityRefreshDelay,
    		TimeUnit connectivityRefreshDelayTimeUnit) {
		super();
		if (url == null) {
			throw new IllegalArgumentException("url cannot be null");
		}
		if (eventHandler == null) {
			throw new IllegalArgumentException("eventHandler cannot be null");
		}
		if (reconnectSamplingTimeMillis != null) {
			if (reconnectSamplingTimeMillis < 500) {
				throw new IllegalArgumentException("reconnectSamplingTimeMillis must be at least 500");
			}
			this.reconnectSamplingTimeMillis = reconnectSamplingTimeMillis;
		}
		if (connectivityRefreshDelay != null) {
			if (connectivityRefreshDelay <= 0) {
				throw new IllegalArgumentException("connectivityRefreshDelay must be larger than zero.");
			}
			if (connectivityRefreshDelayTimeUnit == null) {
				throw new IllegalArgumentException("connectivityRefreshDelayTimeUnit cannot be null when connectivityRefreshDelay is set");
			}
			Duration connectivityRefreshDuration = Duration.of(connectivityRefreshDelay, connectivityRefreshDelayTimeUnit.toChronoUnit());
			if (connectivityRefreshDuration.toMillis() < reconnectSamplingTimeMillis || connectivityRefreshDuration.toSeconds() < 10) {
				throw new IllegalArgumentException("connectivityRefresh duration must be larger than reconnectSamplingTime and at least 10 seconds.");
			}
			this.connectivityRefreshDelay = connectivityRefreshDelay;
			this.connectivityRefreshDelayTimeUnit = connectivityRefreshDelayTimeUnit;
		}
		this.url = url;
		this.headerParams = headerParams;
		if (this.headerParams == null) {
			this.headerParams = new HashMap<>();
		}
		this.eventHandler = eventHandler;
		this.pool = Executors.newSingleThreadExecutor();
		this.connectivityRefreshPoolScheduler = Executors.newScheduledThreadPool(1);
		try {
			this.sslContext = buildSSLContext();
		} catch (Exception e) {
			throw new IllegalStateException("Failed getting SSL context", e);
		}
	}
    
    private SSLContext buildSSLContext() throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException {
        TrustStrategy acceptingTrustStrategy = (cert, authType) -> true;
        return SSLContexts.custom().loadTrustMaterial(null, acceptingTrustStrategy).build();
    }
    
    private void getChanges() {
        log.info("getChanges begin");
        threadName = Thread.currentThread().getName();
        while (shouldRun.get() && !Thread.currentThread().isInterrupted()) {
            try {
            	getChangesHelper();
            } catch (Exception e) {
                String errorMessage = "Got error: " + e.getMessage();
                log.error(errorMessage, e);
            }
            if (shouldRun.get() && !Thread.currentThread().isInterrupted()) {
                status = SubscribeStatus.RECONNECTING;
                if (shouldSkipSleep.compareAndSet(true, false)) {
                    log.debug("Not delaying");
                } else {
                    log.debug("Delaying before reconnecting.");
                    sleepQuitely(reconnectSamplingTimeMillis);
                    log.debug("Delaying before reconnecting done.");
                }
            }
        }
        log.info("getChanges end");
    }

    private void sleepQuitely(long millis) {
        try {
            Thread.sleep(millis);
        } catch (Exception e) {
            log.error("Error sleeping: " + e.getMessage());
        }
    }
    
    public void getChangesHelper() throws IOException {
        try {
            log.info("getChangesHelper begin for url: {}", url);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(new URI(url))
                .version(HttpClient.Version.HTTP_2)
                .GET()
                .header("Accept", "text/event-stream");

            Set<Entry<String, String>> customHeaderParams = headerParams.entrySet();
			for (Entry<String, String> headerParam : customHeaderParams) {
				requestBuilder.header(headerParam.getKey(), headerParam.getValue());
			}
			HttpRequest request = requestBuilder.build();
            
            HttpResponse<InputStream> response = HttpClient.newBuilder().sslContext(sslContext)
                .build()
                .send(request, BodyHandlers.ofInputStream());
            
            if (response.statusCode() == HttpStatus.SC_OK) {
                status = SubscribeStatus.SUCCESS;
                inputStream = response.body();
                handleResponse(inputStream);
            } else {
                log.info("Getting error body response.");
                String body = getNonStreamBodyResponse(response);
                log.error("Got error response: {} {}", response.statusCode(), body);
            }
            
            log.info("getChangesHelper end");
        } catch (Exception e) {
            String errorMessage = "Got exception: " + e.getMessage() + ", cause: " + e.getCause() + ", class: " + e.getClass();
            if (e.getCause() instanceof InterruptedException) {
                log.debug(errorMessage);
            } else {
                log.error(errorMessage, e);
                throw new IOException(errorMessage, e);
            }
        }
    }

    public String getNonStreamBodyResponse(HttpResponse<InputStream> response) throws IOException {
        try (InputStream currentInputStream = response.body()) {
            return IOUtils.toString(response.body(), StandardCharsets.UTF_8);
        }
    }

    private void handleResponse(InputStream inputStream) throws IOException {
        log.info("Handling response.");
        try (BufferedInputStream in = IOUtils.buffer(inputStream)) {
            int charInt = -1;
            StringBuilder stringBuilder = new StringBuilder();
            char prevChar = 0;
            
            /*
             * Separate notifications following SSE (server-sent-events) protocol:
             * - A message text goes after 'data: ', the space after the colon is optional.
             * - Messages are delimited with double line breaks \n\n.
             */
            while ((charInt = in.read()) != -1) {
                char c = (char) charInt;
                stringBuilder.append(c);
                if (c == '\n' && prevChar == '\n') {
                    String notification = stringBuilder.toString().replace("data:", "");
                    handleData(notification);
                    stringBuilder = new StringBuilder();
                }
                prevChar = c;
            }
        } catch (Exception e) {
            String errorMessage = "Could not handle response: " + e.getMessage();
            if ("closed".equals(e.getMessage())) {
                log.info(errorMessage);
            } else {
                log.error(errorMessage);
                throw new IOException(errorMessage, e);
            }
        }
        log.info("done handling response.");
    }

    private void handleData(String eventText) {
    	try {
			eventHandler.handle(eventText.trim());
		} catch (Exception e) {
			log.error("Error handleData: " + e.getMessage(), e);
		}
    }

    public boolean isSubscribedSuccessfully() {
        return shouldRun.get() && SubscribeStatus.SUCCESS.equals(status);
    }

    public void stopCurrentRequest() {
        log.info("Stopping current request for Restconf NSO subscriber {}", threadName);
        shouldSkipSleep.set(true);
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (Exception e) {
                log.error("Got error stopCurrentRequest: " + e.getMessage(), e);
            }
        }
        log.info("Stopped current request for current request for Restconf NSO subscriber {}", threadName);
    }
    
    public void start() {
    	log.info("Subscribing changes from " + url);
        Runnable connectivityRefreshTask = () -> {
            try {
                log.info("Stopping current request, for refreshing connectivity with new request.");
                stopCurrentRequest();
            } catch (Exception e) {
                log.info("Got error at connectivityRefreshTask: " + e.getMessage());
            }
        };
        log.info("Scheduling connectity refresh task.");
        scheduleConnectivityRefreshTask(connectivityRefreshTask);
        
        pool.execute(() -> {
        	try {
				getChanges();
			} catch (Exception e) {
				log.error("Got error getChanges: " + e.getMessage(), e);
			}
        });
        
        log.info("done.");
    }

    protected void scheduleConnectivityRefreshTask(Runnable connectivityRefreshTask) {
        connectivityRefreshPoolScheduler.scheduleWithFixedDelay(connectivityRefreshTask , connectivityRefreshDelay, connectivityRefreshDelay, connectivityRefreshDelayTimeUnit);
    }

    protected SubscribeStatus getStatus() {
        return status;
    }
    
    public void shutdown() {
        try {
            log.info("shutdown");
            shouldRun.set(false);
            log.info("Stopping SSE Client {}", threadName);
            stopCurrentRequest();
            pool.shutdownNow();
            connectivityRefreshPoolScheduler.shutdownNow();
            status = SubscribeStatus.STOPPED;
            log.info("Stopped SSE Client {}", threadName);
        } catch (Exception e) {
            log.error("Error in preDestroy", e);
        }
    }

}
