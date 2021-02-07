# gnmi-utils

GNMI utils.

**Note**: This is not an official Cisco product.

## Features 
* [GNMI Utils](#gnmi-utils)  

### GNMI Utils
Parsing string representation of GNMI paths.  
Following:  
[Representing GNMI paths as strings](https://github.com/openconfig/reference/blob/master/rpc/gnmi/gnmi-path-strings.md#representing-gnmi-paths-as-strings)

#### Example usage

[GNMIUtilsTest](./gnmi-utils/src/test/java/com/cisco/gnmi/utils/GNMIUtilsTest.java) 

```
String gnmiPathStr = "openconfig-interfaces/interfaces/interface[name=Ethernet/1/2/3]/state";
Path parsedPath = GNMIUtils.parseGNMIPathStr(gnmiPathStr);
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
