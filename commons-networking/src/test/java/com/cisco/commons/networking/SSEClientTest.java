package com.cisco.commons.networking;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;

import org.junit.Test;

import com.cisco.commons.networking.SSEClient.SubscribeStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import lombok.extern.slf4j.Slf4j;

/**
 * SSEClient test.
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
public class SSEClientTest {
	
	private static final String EVENT1 = "event1";
	private static final String EVENT2 = "event2";
	private static final String KEEPALIVE_MESSAGE = ":keepalive\n";
	
	@Test
	public void sseClientTestEmbeddedServer() throws Exception {
		String api = "/api";
		HttpServer httpServer = createHttpServer(0, api);
		log.info("Starting server");
		httpServer.start();

		try {
			List<String> events = new LinkedList<>();
			log.info("Listening to port: {}", httpServer.getAddress().getPort());
			String url = "http://localhost:" + httpServer.getAddress().getPort() + api;
			EventHandler eventHandler = eventText -> { events.add(eventText); };
			SSEClient sseClient = SSEClient.builder().url(url).eventHandler(eventHandler)
				.build();
			log.info("Starting SSE Client");
			sseClient.start();
			sleepQuitely(2 * 1000);
			assertEquals(SubscribeStatus.SUCCESS, sseClient.getStatus());
			assertEquals(2, events.size());
			assertEquals(EVENT2, events.get(1));
			log.info("Stopping SSE Client");
			sseClient.shutdown();
			assertEquals(SubscribeStatus.STOPPED, sseClient.getStatus());
		} finally {
			log.info("Stopping server");
			httpServer.stop(0);
		}
	}
	
	@Test
	public void sseClientTestEmbeddedServerReconnectDueToServerStopped() throws Exception {
		log.info("sseClientTestEmbeddedServerReconnectDueToServerStopped - begin");
		String api = "/api";
		HttpServer httpServer = createHttpServer(0, api);
		log.info("Starting server");
		httpServer.start();

		List<String> events = new LinkedList<>();
		int port = httpServer.getAddress().getPort();
		log.info("Listening to port: {}", port);
		String url = "http://localhost:" + port + api;
		EventHandler eventHandler = eventText -> { events.add(eventText); };
		SSEClient sseClient = SSEClient.builder().url(url).eventHandler(eventHandler).reconnectSamplingTimeMillis(1000L)
			.build();
		log.info("Starting SSE Client");
		sseClient.start();
		sleepQuitely(2 * 1000);
		assertEquals(SubscribeStatus.SUCCESS, sseClient.getStatus());
		assertEquals(2, events.size());
		assertEquals(EVENT2, events.get(1));
		
		log.info("Simulating disconnection, server restart.");
		log.info("Stopping server");
		httpServer.stop(0);
		
		sleepQuitely(1000);
		assertEquals(SubscribeStatus.RECONNECTING, sseClient.getStatus());
		
		log.info("Starting server");
		httpServer = createHttpServer(port, api);
		httpServer.start();
		
		sleepQuitely(3000);
		
		assertEquals(4, events.size());
		assertEquals(EVENT2, events.get(3));
		
		log.info("Stopping SSE Client");
		sseClient.shutdown();
		
		assertEquals(SubscribeStatus.STOPPED, sseClient.getStatus());
		
		log.info("Stopping server");
		httpServer.stop(0);
		
		log.info("sSEClientTestEmbeddedServerReconnect - end");
	}
	
	@Test
	public void sseClientTestEmbeddedServerReconnectDueToMissingKeepAlive() throws Exception {
		log.info("sseClientTestEmbeddedServerReconnectDueToMissingKeepAlive - begin");
		String api = "/api";
		HttpServer httpServer = createHttpServer(0, api, true);
		log.info("Starting server");
		httpServer.start();

		List<String> events = new LinkedList<>();
		int port = httpServer.getAddress().getPort();
		log.info("Listening to port: {}", port);
		String url = "http://localhost:" + port + api;
		EventHandler eventHandler = eventText -> { events.add(eventText); };
		SSEClient sseClient = SSEClient.builder().url(url).eventHandler(eventHandler)
			.reconnectSamplingTimeMillis(1000L)
			.useKeepAliveMechanismIfReceived(true)
			.connectivityCheckIntervalSeconds(1).minConnectivityThresholdSeconds(1)
			.build();
		log.info("Starting SSE Client");
		sseClient.start();
		sleepQuitely(2 * 1000);
		assertEquals(SubscribeStatus.SUCCESS, sseClient.getStatus());
		assertEquals(2, events.size());
		assertEquals(EVENT2, events.get(1));
		
		log.info("Simulating keep-alive not sent.");
		sleepQuitely(8 * 1000);
		assertEquals(1, sseClient.getReconnectionsCount());
		assertEquals(SubscribeStatus.SUCCESS, sseClient.getStatus());
		
		log.info("Stopping SSE Client");
		sseClient.shutdown();
		
		assertEquals(SubscribeStatus.STOPPED, sseClient.getStatus());
		
		log.info("Stopping server");
		httpServer.stop(0);
		
		log.info("sSEClientTestEmbeddedServerReconnect - end");
	}
	
	private HttpServer createHttpServer(int port, String api) throws IOException {
		return createHttpServer(port, api, false);
	}

	private HttpServer createHttpServer(int port, String api, boolean sendKeepAliveMessagesBlocking) throws IOException {
		HttpServer httpServer = HttpServer.create(new InetSocketAddress(port), 0);
		httpServer.setExecutor(Executors.newCachedThreadPool());
		httpServer.createContext(api, new HttpHandler() {
			
			private boolean returnedResponse = false;
			
			@Override
			public void handle(HttpExchange exchange) throws IOException {
				byte[] response = ("data: " + EVENT1 + "\n\n").getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0);
				exchange.getResponseBody().write(response);
				exchange.getResponseBody().flush();
				response = ("error: " + "error_message" + "\n\n").getBytes(StandardCharsets.UTF_8);
				exchange.getResponseBody().write(response);
				exchange.getResponseBody().flush();
				sendKeepAlive(exchange);
				sleepQuitely(1000);
				response = ("data: " + EVENT2 + "\n\n").getBytes(StandardCharsets.UTF_8);
				exchange.getResponseBody().write(response);
				exchange.getResponseBody().flush();
				
				if (sendKeepAliveMessagesBlocking) {
					sendKeepAliveMessagesBlocking(exchange, 3);
				}
				
				// Intentionally not closing, imitate a non-ending stream response.
//				exchange.close();
			}

			private void sendKeepAlive(HttpExchange exchange) throws IOException {
				byte[] response;
				response = (": " + "keepalive" + "\n\n").getBytes(StandardCharsets.UTF_8);
				exchange.getResponseBody().write(response);
				exchange.getResponseBody().flush();
			}
			
			private void sendKeepAliveMessagesBlocking(HttpExchange exchange, int seconds) throws IOException {
				String changes;
				for (int i = 0; i < seconds; i++) {
					changes = KEEPALIVE_MESSAGE;
					exchange.getResponseBody().write(changes.getBytes(StandardCharsets.UTF_8));
					exchange.getResponseBody().flush();
					sleepQuitely(1000);
				}
			}
		});
		httpServer.createContext("/" + SSEClient.CONNECTIVITY_CHECK, new HttpHandler() {
			@Override
			public void handle(HttpExchange exchange) throws IOException {
				log.debug("Got connectivity check request");
				byte[] response = ("ok").getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0);
				exchange.getResponseBody().write(response);
				exchange.getResponseBody().flush();
				exchange.close();
			}
		});
		return httpServer;
	}
	
	private void sleepQuitely(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            log.error("Error sleeping: " + e.getMessage());
        }
    }
}
