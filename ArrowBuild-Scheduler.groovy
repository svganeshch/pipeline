import groovy.json.JsonSlurper
import groovy.transform.Field

String calcDate() { ['date', '+%Y%m%d'].execute().text.trim()}
String calcTimestamp() { ['date', '+%s'].execute().text.trim()}

int NO_OF_NODES = 4
@Field Boolean isCommunity = false
def jsonParse(def json) { new groovy.json.JsonSlurperClassic().parseText(json) }
def activeDevices = active_devices.split(",");

@Field node1_devices = []
@Field node2_devices = []
@Field node3_devices = []
@Field node4_devices = []
@Field node5_devices = []

@Field nodeStructureUrl
@Field officialDevicesUrl
@Field communityDevicesUrl
public void setInfraUrls(def version) {
    nodeStructureUrl = "https://raw.githubusercontent.com/ArrowOS/arrow_infrastructure_devices/${version}/node_structure.json".toURL()
    officialDevicesUrl = "https://raw.githubusercontent.com/ArrowOS/arrow_infrastructure_devices/${version}/arrow.devices".toURL()
    communityDevicesUrl = "https://raw.githubusercontent.com/ArrowOS/arrow_infrastructure_devices/${version}/arrow-community.devices".toURL()
}

@NonCPS
String getDeviceHal(def device) {
    String gotHal = null
    def devicesUrl = isCommunity ? communityDevicesUrl : officialDevicesUrl
    devicesUrl.eachLine {
        if(it != "" && it != null) {
            if(it.charAt(0) != null && it.charAt(0) != "#") {
                if(it.split(" ")[1] == device) {
                    gotHal = it.split(" ")[3]
                }
            }
        }
    }
    return gotHal
}

@NonCPS
Boolean isExplicitN1(def device) {
    Boolean isN1 = false
    def devicesUrl = isCommunity ? communityDevicesUrl : officialDevicesUrl
    devicesUrl.eachLine {
        if(it != "" && it != null) {
            if(it.charAt(0) != null && it.charAt(0) != "" && it.charAt(0) != "#") {
                if(it.split(" ")[0] == "\$" && it.split(" ")[1] == device) {
                    isN1 = true
                }
            }
        }
    }
    return isN1
}

@NonCPS
Boolean isExplicitN2(def device) {
    Boolean isN2 = false
    def devicesUrl = isCommunity ? communityDevicesUrl : officialDevicesUrl
    devicesUrl.eachLine {
        if(it != "" && it != null) {
            if(it.charAt(0) != null && it.charAt(0) != "" && it.charAt(0) != "#") {
                if(it.split(" ")[0] == "@" && it.split(" ")[1] == device) {
                    isN2 = true
                }
            }
        }
    }
    return isN2
}

@NonCPS
Boolean isExplicitN3(def device) {
    Boolean isN3 = false
    def devicesUrl = isCommunity ? communityDevicesUrl : officialDevicesUrl
    devicesUrl.eachLine {
        if(it != "" && it != null) {
            if(it.charAt(0) != null && it.charAt(0) != "" && it.charAt(0) != "#") {
                if(it.split(" ")[0] == "!" && it.split(" ")[1] == device) {
                    isN3 = true
                }
            }
        }
    }
    return isN3
}

@NonCPS
Boolean isExplicitN4(def device) {
    Boolean isN4 = false
    def devicesUrl = isCommunity ? communityDevicesUrl : officialDevicesUrl
    devicesUrl.eachLine {
        if(it != "" && it != null) {
            if(it.charAt(0) != null && it.charAt(0) != "" && it.charAt(0) != "#") {
                if(it.split(" ")[0] == "^" && it.split(" ")[1] == device) {
                    isN4 = true
                }
            }
        }
    }
    return isN4
}

public void assignNode(def assign_node, def device) {
    if(assign_node == "Arrow-1") {
        node1_devices.add(device)
    } else if(assign_node == "Arrow-2") {
        node2_devices.add(device)
    } else if(assign_node == "Arrow-3") {
        node3_devices.add(device)
    } else if(assign_node == "Arrow-4") {
        node4_devices.add(device)
    } else if(assign_node == "Arrow-5") {
        node5_devices.add(device)
    } else {
        echo "No node assigned for ${device}"
    }
}

node("master") {
    stage("Assigning Nodes!") {
        try {
            if(activeDevices != null && activeDevices.length !=0 && activeDevices[0] != "none") {
                for(device in activeDevices) {
                    String assign_node = null

                    if(!version.isEmpty()) {
                        if(version.contains("community")) {
                            version = version.split('_')[0]
                            isCommunity = true
                        }
                    }
                    // set infra urls now
                    setInfraUrls(version)
                    
                    // Force node if specified
                    if(!force_node.isEmpty() && force_node != "default") {
                        assign_node = force_node.trim()
                        echo "Forcing node to ${assign_node}"
                        assignNode(assign_node, device)
                        continue
                    }

                    if(assign_node == null) {
                        for(i=1; i<=NO_OF_NODES; i++) {
                            if(isExplicitN1(device)) {
                                assign_node = "Arrow-1"
                                echo "Explictly assigning ${assign_node} node for ${device}"
                                break
                            }

                            if(isExplicitN2(device)) {
                                assign_node = "Arrow-2"
                                echo "Explictly assigning ${assign_node} node for ${device}"
                                break
                            }

                            if(isExplicitN3(device)) {
                                assign_node = "Arrow-3"
                                echo "Explictly assigning ${assign_node} node for ${device}"
                                break
                            }

                            if(isExplicitN4(device)) {
                                assign_node = "Arrow-4"
                                echo "Explictly assigning ${assign_node} node for ${device}"
                                break
                            }

                            String nodeStructure = nodeStructureUrl.text
                            def nodeStJson = jsonParse(nodeStructure)
                            String devHal = getDeviceHal(device)
                            if(i != 4) {
                                if(nodeStJson["arrow-"+i][0]["hals"].contains(devHal)) {
                                    assign_node = "Arrow-${i}"
                                }
                            }
                        }
                    }

                    assignNode(assign_node, device)
                }
                
                // Node 5 (Super node temp)
                if(node5_devices != null && !node5_devices.isEmpty()) {
                    echo "-------------------------------"
                    echo "Devices assigned for Arrow-5"
                    echo "-------------------------------"
                    for(n5dev in node5_devices) {
                        if(n5dev != null && !n5dev.isEmpty() && n5dev != "none") {
                            echo "Triggering build for ${n5dev}"
                            build job: 'Arrow-Builder', parameters: [
                                string(name: 'DEVICE', value: n5dev),
                                string(name: 'ASSIGNED_NODE', value: "Arrow-5"),
                                string(name: 'BUILD_TIMESTAMP', value: calcDate() + calcTimestamp()),
                                string(name: 'VERSION', value: version),
                                string(name: 'IS_COMMUNITY', value: isCommunity.toString()),
                                string(name: 'DEVICE_PROFILE', value: device_profile)
                            ], propagate: false, wait: false
                            sleep 2
                        }
                    }
                }
                
                // Node 4
                if(node4_devices != null && !node4_devices.isEmpty()) {
                    echo "-------------------------------"
                    echo "Devices assigned for Arrow-4"
                    echo "-------------------------------"
                    for(n4dev in node4_devices) {
                        if(n4dev != null && !n4dev.isEmpty() && n4dev != "none") {
                            echo "Triggering build for ${n4dev}"
                            build job: 'Arrow-Builder', parameters: [
                                string(name: 'DEVICE', value: n4dev),
                                string(name: 'ASSIGNED_NODE', value: "Arrow-4"),
                                string(name: 'BUILD_TIMESTAMP', value: calcDate() + calcTimestamp()),
                                string(name: 'VERSION', value: version),
                                string(name: 'IS_COMMUNITY', value: isCommunity.toString()),
                                string(name: 'DEVICE_PROFILE', value: device_profile)
                            ], propagate: false, wait: false
                            sleep 2
                        }
                    }
                }

                // Node 3
                if(node3_devices != null && !node3_devices.isEmpty()) {
                    echo "-------------------------------"
                    echo "Devices assigned for Arrow-3"
                    echo "-------------------------------"
                    for(n3dev in node3_devices) {
                        if(n3dev != null && !n3dev.isEmpty() && n3dev != "none") {
                            echo "Triggering build for ${n3dev}"
                            build job: 'Arrow-Builder', parameters: [
                                string(name: 'DEVICE', value: n3dev),
                                string(name: 'ASSIGNED_NODE', value: "Arrow-3"),
                                string(name: 'BUILD_TIMESTAMP', value: calcDate() + calcTimestamp()),
                                string(name: 'VERSION', value: version),
                                string(name: 'IS_COMMUNITY', value: isCommunity.toString()),
                                string(name: 'DEVICE_PROFILE', value: device_profile)
                            ], propagate: false, wait: false
                            sleep 2
                        }
                    }
                }

                // Node 2
                if(node2_devices != null && !node2_devices.isEmpty()) {
                    echo "-------------------------------"
                    echo "Devices assigned for Arrow-2"
                    echo "-------------------------------"
                    for(n2dev in node2_devices) {
                        if(n2dev != null && !n2dev.isEmpty() && n2dev != "none") {
                            echo "Triggering build for ${n2dev}"
                            build job: 'Arrow-Builder', parameters: [
                                string(name: 'DEVICE', value: n2dev),
                                string(name: 'ASSIGNED_NODE', value: "Arrow-2"),
                                string(name: 'BUILD_TIMESTAMP', value: calcDate() + calcTimestamp()),
                                string(name: 'VERSION', value: version),
                                string(name: 'IS_COMMUNITY', value: isCommunity.toString()),
                                string(name: 'DEVICE_PROFILE', value: device_profile)
                            ], propagate: false, wait: false
                            sleep 2
                        }
                    }
                }

                // Node 1
                if(node1_devices != null && !node1_devices.isEmpty()) {
                    echo "-------------------------------"
                    echo "Devices assigned for Arrow-1"
                    echo "-------------------------------"
                    for(n1dev in node1_devices) {
                        if(n1dev != null && !n1dev.isEmpty() && n1dev != "none") {
                            echo "Triggering build for ${n1dev}"
                            build job: 'Arrow-Builder', parameters: [
                                string(name: 'DEVICE', value: n1dev),
                                string(name: 'ASSIGNED_NODE', value: "Arrow-1"),
                                string(name: 'BUILD_TIMESTAMP', value: calcDate() + calcTimestamp()),
                                string(name: 'VERSION', value: version),
                                string(name: 'IS_COMMUNITY', value: isCommunity.toString()),
                                string(name: 'DEVICE_PROFILE', value: device_profile)
                            ], propagate: false, wait: false
                            sleep 2
                        }
                    }
                }
            } else {
                echo "No active devices passed!"
            }
        } catch (e) {
            currentBuild.result = "FAILED"
            throw e
        }
    }
}
