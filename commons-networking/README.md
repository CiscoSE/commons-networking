# commons-networking

Commons networking related utils.

**Note**: This is not an official Cisco product.

## Features
* [SSE (Server-sent Events) client](#sse-client)  

### SSE client
Server-Sent Events (SSE) is a [server push](https://en.wikipedia.org/wiki/Push_technology) technology 
enabling a client to receive automatic updates from a server via HTTP connection.  
The Server-Sent Events EventSource API is standardized as part of 
[HTML5](https://www.w3.org/TR/eventsource) by the W3C.  
It is used for unidirectional server to client events, as opposed to the full-duplex bidirectional WebSockets.

#### Example usage

[SSEClientTest](./commons-networking/src/test/java/com/cisco/commons/networking/SSEClientTest.java) 

```
SSEClient sseClient = SSEClient.builder().url(url).eventHandler(eventHandler)
	.build();
sseClient.start();
```

## Quality Assurance

### Code analysis
Code analysis done with Sonar.

### Testing
Flows are covered by unit tests.

## Build
Run maven install on commons-networking parent.

## Contributions
 * [Contributing](CONTRIBUTING.md) - how to contribute.
 * [Contributors](docs/CONTRIBUTORS.md) - Folks who have contributed, thanks very much!

## Licensing

```

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

### Author
Liran Mendelovich  

Cisco
