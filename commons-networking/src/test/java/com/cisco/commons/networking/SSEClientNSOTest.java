package com.cisco.commons.networking;

import java.util.HashMap;
import java.util.Map;

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
public class SSEClientNSOTest {
	
	// TODO - LOCAL TEST
	
	public static void main(String[] args) throws Exception {
		sSEClientTestNSOServer();
	}
	
//	@Test
	public static void sSEClientTestNSOServer() throws Exception {
//		String url = "http://10.56.58.145:8080/restconf/streams/service-state-changes/json?start-time=2021-02-01T11:24:11Z";
		String url = "http://10.56.58.145:8080/restconf/streams/service-state-changes/json";
		Map<String, String> customHeaderParams = new HashMap<>();
		
		customHeaderParams.put("Authorization", "Basic bnNvNTUyOlB1YmxpYzEyMzQh");
		EventHandler eventHandler = eventText -> { log.info("Handle event: {}", eventText); };
		SSEClient sseClient = SSEClient.builder().url(url).headerParams(customHeaderParams).eventHandler(eventHandler )
			.build();
		sseClient.start();
		Thread.sleep(10 * 60 * 1000);
		sseClient.shutdown();
	}
}
