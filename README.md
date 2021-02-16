# commons-networking

Commons networking related utils.

**Note**: This is not an official Cisco product.

## Features
* [SSE (Server-sent Events) client](#sse-client)  
* [GNMI Utils](#gnmi-utils)  

### SSE client
SSE client implementation based on Java 11 HttpClient.  
Server-Sent Events (SSE) is a [server push](https://en.wikipedia.org/wiki/Push_technology) technology 
enabling a client to receive automatic updates from a server via HTTP connection.  
The Server-Sent Events EventSource API is standardized as part of 
[HTML5](https://www.w3.org/TR/eventsource) by the W3C.  
It is used for unidirectional server to client events, as opposed to the full-duplex bidirectional WebSockets.  
One of the use cases is by IEFT Netconf/Restconf protocols:
[IETF reference](https://tools.ietf.org/id/draft-ietf-netconf-restconf-notif-08.html#rfc.section.3.4)

#### Example usage

[SSEClientTest](./commons-networking/src/test/java/com/cisco/commons/networking/SSEClientTest.java) 

```
EventHandler eventHandler = eventText -> { events.add(eventText); };
SSEClient sseClient = SSEClient.builder().url(url).eventHandler(eventHandler)
	.build();
sseClient.start();
```

### GNMI Utils
Parsing string representation of GNMI paths.  
Following:  
[Representing GNMI paths as strings](https://github.com/openconfig/reference/blob/master/rpc/gnmi/gnmi-path-strings.md#representing-gnmi-paths-as-strings)

This is useful for device collection / GNMI path configuration.

#### Example usage

[GNMIUtilsTest](./gnmi-utils/src/test/java/com/cisco/gnmi/utils/GNMIUtilsTest.java) 

```
String gnmiPathStr = "openconfig-interfaces/interfaces/interface[name=Ethernet/1/2/3]/state";
Path parsedPath = GNMIUtils.parseGNMIPathStr(gnmiPathStr);
```

## Quality Assurance

### Code analysis
Code analysis done with Sonar, and code review.

### Testing
Flows are covered by unit tests and manual testing.

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
