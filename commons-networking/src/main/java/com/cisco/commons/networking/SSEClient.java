package com.cisco.commons.networking;


import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLContext;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.ssl.TrustStrategy;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * SSE (Server-sent events) client.
 * Server-Sent Events (SSE) is a <a href="https://en.wikipedia.org/wiki/Push_technology">server push</a> technology 
 * enabling a client to receive automatic updates from a server via HTTP connection. <br/>
 * The Server-Sent Events EventSource API is standardized as part of 
 * <a href="https://html.spec.whatwg.org/multipage/server-sent-events.html#the-eventsource-interface">HTML5</a>. <br/>
 * It is used for unidirectional server to client events, as opposed to the full-duplex bidirectional WebSockets. <br/>
 * <br/>
 * The SSE client implementation is based on Java 11 HttpClient. <br/>
 * The SSE client has reconnect sampling mechanism with a default time of one minute. <br/>
 * The SSE client has connectivity refresh mechanism with default time of 24 hours. <br/>
 * 
 * For secure SSL, there is support for non-trusted hosts by DisableHostnameVerification system property by setting
 * setDisableHostnameVerificationSystemProperty parameter when building the object.
 * This is translating to: System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true"); 
 * This is not best practice as it is risky and sets a global system property, it is better to work with a trusted host.
 * <br/>
 * When useKeepAliveMechanismIfReceived is used, and keep-alive / comment messages are received, it is 
 * handled by logic which will disconnect / reconnect to the stream when no messages are received for some
 * time by a calculated interval. When it is enabled, any comment message will be treated as a keep-alive 
 * message per the SSE protocol specifications, as specific keep-alive messages are not well-defined.
 * <br/>
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
 * 
 * 
 * @author Liran Mendelovich
 */
@Slf4j
public class SSEClient {

	private static final long DEFAULT_RECONNECT_SAMPLING_TIME_MILLIS = 60L * 1000L;
    private static final long DEFAULT_CONNECTIVITY_REFRESH_DELAY = 24;
    private static final TimeUnit DEFAULT_CONNECTIVITY_REFRESH_DELAY_TIME_UNIT = TimeUnit.HOURS;
    private static final int DEFAULT_CONNECTIVITY_CHECK_INTERVAL_SECONDS = 2 * 60;
    private static final int DEFAULT_MIN_CONNECTIVITY_THRESHOLD_SECONDS = 2 * 60;
	private static final int KEEP_ALIVE_SAMPLING_MESSAGES_COUNT = 4;
	protected static final String CONNECTIVITY_CHECK = "connectivity_check";
    
	private String url;
	private Map<String, String> headerParams;
	private EventHandler eventHandler;
	private long reconnectSamplingTimeMillis = DEFAULT_RECONNECT_SAMPLING_TIME_MILLIS;
	private long connectivityRefreshDelay = DEFAULT_CONNECTIVITY_REFRESH_DELAY;
	private TimeUnit connectivityRefreshDelayTimeUnit = DEFAULT_CONNECTIVITY_REFRESH_DELAY_TIME_UNIT;
	private String connectivityCheckUrl;
	
    private InputStream inputStream;
    private SSLContext sslContext;

    private AtomicBoolean shouldRun = new AtomicBoolean(true);
    private AtomicBoolean shouldSkipSleep = new AtomicBoolean(false);
    private String threadName;
    
    @Getter
    private SubscribeStatus status = SubscribeStatus.NOT_STARTED;
    
    private AtomicBoolean isConnected = new AtomicBoolean(true);
    private boolean useConnectivityCheck;
    private boolean useKeepAliveMechanismIfReceived;
    private Integer connectivityCheckIntervalSeconds = DEFAULT_CONNECTIVITY_CHECK_INTERVAL_SECONDS;
    private Integer minConnectivityThresholdSeconds = DEFAULT_MIN_CONNECTIVITY_THRESHOLD_SECONDS;
	private long connectivityThresholdMillis;
	private AtomicBoolean keepAliveReceivedInCurrentRequest = new AtomicBoolean(false);
    private List<Long> firstKeepAliveMessageTimes = new ArrayList<>();    
    private long lastReceivedMessageTime = 0L;
    private AtomicLong reconnectionsCount = new AtomicLong(0L);
    
    private ExecutorService pool;
    private ScheduledExecutorService connectivityRefreshPoolScheduler;
    
    public enum SubscribeStatus {
        SUCCESS, FAILING, RECONNECTING, NOT_STARTED, STOPPED
    }

    @Builder
    public SSEClient(String url, Map<String, String> headerParams, EventHandler eventHandler,
    		Long reconnectSamplingTimeMillis, Long connectivityRefreshDelay,
    		TimeUnit connectivityRefreshDelayTimeUnit, boolean setDisableHostnameVerificationSystemProperty,
    		boolean useKeepAliveMechanismIfReceived, boolean useConnectivityCheck,
    		Integer connectivityCheckIntervalSeconds, Integer minConnectivityThresholdSeconds) {
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
		try {
			URL urlObject = new URL(new URL(url), "/");
			connectivityCheckUrl = urlObject.toString() + CONNECTIVITY_CHECK;
			log.info("connectivityCheckUrl: {}", connectivityCheckUrl);
		} catch (MalformedURLException e) {
			throw new IllegalArgumentException("invalid URL: " + url + ": " + e.getMessage());
		}
		
		this.headerParams = headerParams;
		if (this.headerParams == null) {
			this.headerParams = new HashMap<>();
		}
		this.eventHandler = eventHandler;
		this.useKeepAliveMechanismIfReceived = useKeepAliveMechanismIfReceived;
		this.useConnectivityCheck = useConnectivityCheck;
		if (useKeepAliveMechanismIfReceived) {
			log.info("useKeepAliveMechanismIfReceived is true, setting useConnectivityCheck to true.");
			this.useConnectivityCheck = true;
		}
		if (connectivityCheckIntervalSeconds != null) {
			this.connectivityCheckIntervalSeconds = connectivityCheckIntervalSeconds;
		}
		if (minConnectivityThresholdSeconds != null) {
			this.minConnectivityThresholdSeconds = minConnectivityThresholdSeconds;
		}
		this.pool = Executors.newSingleThreadExecutor();
		this.connectivityRefreshPoolScheduler = Executors.newScheduledThreadPool(1);
		try {
			if (setDisableHostnameVerificationSystemProperty) {
				System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
			}
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
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
				;

            Set<Entry<String, String>> customHeaderParams = headerParams.entrySet();
			for (Entry<String, String> headerParam : customHeaderParams) {
				requestBuilder.header(headerParam.getKey(), headerParam.getValue());
			}
			HttpRequest request = requestBuilder.build();
            
            HttpResponse<InputStream> response = HttpClient.newBuilder().sslContext(sslContext)
        		.connectTimeout(Duration.ofSeconds(30))
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
    	
    	/*
         * Separate notifications following <a href="https://datatracker.ietf.org/doc/html/rfc8895">SSE</a> (server-sent-events) protocol:
         * - A message text goes after 'data: ', the space after the colon is optional.
         * - A message is terminated by a blank line (two line terminators in a row).
         * 
         * A Sample SSE Stream:
         * event: start
	     * id: 1
	     * data: hello there
	     * :keepalive
		 * 
	     * event: middle
	     * id: 2
	     * data: let's chat some more ...
	     * data: and more and more and ...
	     * :keepalive
	     * 
	     * event: end
	     * id: 3
	     * data: goodbye
         */
    	
        log.info("Handling response.");
        keepAliveReceivedInCurrentRequest.set(false);
        connectivityThresholdMillis = 0;
        try (BufferedInputStream in = IOUtils.buffer(inputStream)) {
        	try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        		String line = null;
        		StringBuilder messageBuilder = new StringBuilder();
        		while((line = reader.readLine()) != null) {
        			lastReceivedMessageTime = System.currentTimeMillis();
        			String content = line;
        			if (content.startsWith("data:")) {
        				messageBuilder.append(content.substring(5));
    				} else if (useKeepAliveMechanismIfReceived && content.startsWith(":")) {
                    	log.debug("Got keeplive / comment message: {}", content);
                    	keepAliveReceivedInCurrentRequest.set(true);
                    	processKeepAliveMessage();
                    } else {
    					log.debug("Got non-data line: {}", content);
    				}
					if (line.trim().isEmpty() && messageBuilder.length() > 0) {
						String message = messageBuilder.toString();
        				handleData(message);
        				messageBuilder = new StringBuilder();
                    }
        	    }
        		if (messageBuilder.length() > 0) {
                	log.info("Non-processed data: {}", messageBuilder);
                }
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
    	scheduleConnectivityTasks();
        pool.execute(() -> {
        	try {
				getChanges();
			} catch (Exception e) {
				log.error("Got error getChanges: " + e.getMessage(), e);
			}
        });
        
        log.info("done.");
    }
    
    protected void scheduleConnectivityTasks() {
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
    	if (useConnectivityCheck) {
	    	Runnable connectivityCheckTask = () -> {
	            try {
	            	if (!isSubscribedSuccessfully()) {
	            		log.debug("Status is not subscribed successfully. Skipping connectivity check.");
	            		return;
	            	}
	            	boolean wasConnected = isConnected.get();
	                boolean isCurrentlyConnected = isCurrentlyConnected();
	                log.debug("connectivityCheckTask - isCurrentlyConnected: {}, wasConnected: {}, keepAliveReceived: {}, connectivityThresholdMillis: {}", 
	            		isCurrentlyConnected, wasConnected, keepAliveReceivedInCurrentRequest, connectivityThresholdMillis);
	                boolean shouldReconnect = false;
	                if (keepAliveReceivedInCurrentRequest.get() && connectivityThresholdMillis > 0) {
	                	if (!isCurrentlyConnected) {
	                		log.info("time from last received message not valid.");
	                		shouldReconnect = true;
	                	}
	                } else if (!wasConnected && isCurrentlyConnected) {
	                	log.info("Previous status was disconnected, and now it is connected.");
	                }
	                if (!isCurrentlyConnected) {
	                	shouldReconnect = true;
	                }
	                if (shouldReconnect) {
	                	log.info("Stopping current request, for refreshing connectivity with new request.");
	                	keepAliveReceivedInCurrentRequest.set(false);
	            		reconnectionsCount.incrementAndGet();
	                	stopCurrentRequest();
	                }
	                isConnected.set(isCurrentlyConnected);
	            } catch (Exception e) {
	                log.info("Got error at connectivityRefreshTask: " + e.getMessage());
	            }
	        };
	    	log.info("Scheduling connectity check task every {} seconds.", connectivityCheckIntervalSeconds);
	        connectivityRefreshPoolScheduler.scheduleWithFixedDelay(connectivityCheckTask ,
	        		connectivityCheckIntervalSeconds, connectivityCheckIntervalSeconds, TimeUnit.SECONDS);
    	}
    }
    
    private boolean isCurrentlyConnected() {
		try {
			if (!isLastReceivedMessageTimeValid()) {
				return false;
			}
			log.debug("Checking via connectivity check URL request: {}", connectivityCheckUrl);
		    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(new URI(connectivityCheckUrl))
                .GET()
                .timeout(Duration.ofSeconds(3));
		    Set<Entry<String, String>> customHeaderParams = headerParams.entrySet();
			for (Entry<String, String> headerParam : customHeaderParams) {
				requestBuilder.header(headerParam.getKey(), headerParam.getValue());
			}
			HttpRequest request = requestBuilder.build();
            HttpResponse<InputStream> response = HttpClient.newBuilder().sslContext(sslContext)
        		.connectTimeout(Duration.ofSeconds(3))
                .build()
                .send(request, BodyHandlers.ofInputStream());
			log.debug("connectivityCheck response status: {}", response.statusCode());
			return true;
		} catch (ConnectException e) {
			log.info("isCurrentlyConnected - ConnectException: " + e.getMessage());
			return false;
		} catch (Exception e) {
			log.error("Errror in isCurrentlyConnected: " + e.getClass() + ", " + e.getMessage(), e);
			return false;
		}
	}
    
    private boolean isLastReceivedMessageTimeValid() {
		if (keepAliveReceivedInCurrentRequest.get() && connectivityThresholdMillis > 0) {
			long timeFromLastReceivedMessageMs = System.currentTimeMillis() - lastReceivedMessageTime;
			if (timeFromLastReceivedMessageMs > connectivityThresholdMillis) {
				log.warn("time from last received message ( {} ms) is more than the threshold ( {} ). Not connected.", timeFromLastReceivedMessageMs, connectivityThresholdMillis );
				return false;
			}
		}
		return true;
	}

    protected void scheduleConnectivityRefreshTask(Runnable connectivityRefreshTask) {
        connectivityRefreshPoolScheduler.scheduleWithFixedDelay(connectivityRefreshTask , connectivityRefreshDelay, connectivityRefreshDelay, connectivityRefreshDelayTimeUnit);
    }
    
    private void processKeepAliveMessage() {
		if (firstKeepAliveMessageTimes.size() < KEEP_ALIVE_SAMPLING_MESSAGES_COUNT) {
			firstKeepAliveMessageTimes.add(lastReceivedMessageTime);
			int firstKeepAliveMessageTimesSize = firstKeepAliveMessageTimes.size();
			if (firstKeepAliveMessageTimesSize == 1) {
				log.info("First keep-alive received from current request-response.");
			}
			if (firstKeepAliveMessageTimesSize == KEEP_ALIVE_SAMPLING_MESSAGES_COUNT) {
				log.info("Received enough keep-alive messages for calculating interval.");
				setConnectivityThresholdByKeepAliveIntervalAverage();
			}
		}
	}

	/*
	 * We calculate the keep-alive messages interval by average, since it depends on NSO configuration.
	 */
	private void setConnectivityThresholdByKeepAliveIntervalAverage() {
		int keepAliveIntervalsSum = 0;
		int end = firstKeepAliveMessageTimes.size() - 1;
		for (int i = 0; i < end; i++) {
			long keepAliveInterval = firstKeepAliveMessageTimes.get(i+1)
				- firstKeepAliveMessageTimes.get(i);
			keepAliveIntervalsSum += keepAliveInterval;
		}
		log.debug("keepAliveIntervalsSum: {}", keepAliveIntervalsSum);
		int keepAliveIntervalsAverageSeconds = (int) Math.rint((double)keepAliveIntervalsSum / (double)(end) / 1000.0);
		log.info("keepAliveIntervalsAverageSeconds: {}", keepAliveIntervalsAverageSeconds);
		long keepAliveIntervalsAverageMultiplier = 4L;
		connectivityThresholdMillis = keepAliveIntervalsAverageSeconds * keepAliveIntervalsAverageMultiplier * 1000L;
		long minConnectivityThresholdMillis = minConnectivityThresholdSeconds * 1000L;
		if (connectivityThresholdMillis < minConnectivityThresholdMillis) {
			connectivityThresholdMillis = minConnectivityThresholdMillis;
			log.info("calculated connectivity threshold average ( {} ms ) is lower than the minimum ( {} ms ). Using the minimum value.", keepAliveIntervalsAverageSeconds, minConnectivityThresholdMillis );
		} else {
			log.info("Setting connectivity threshold by received keep-alive messages to {} times than the average: {} ms.", keepAliveIntervalsAverageMultiplier, connectivityThresholdMillis);
		}
	}
	
	public long getReconnectionsCount() {
		return reconnectionsCount.get();
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
