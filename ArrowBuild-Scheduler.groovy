import groovy.json.JsonSlurper

String calcDate() { ['date', '+%Y%m%d'].execute().text.trim()}
String calcTimestamp() { ['date', '+%s'].execute().text.trim()}

int NO_OF_NODES = 3
def jsonParse(def json) { new groovy.json.JsonSlurperClassic().parseText(json) }
nodeStructureUrl = "https://raw.githubusercontent.com/ArrowOS/android_vendor_arrow/arrow-10.0/node_structure.json".toURL()
officialDevicesUrl = "https://raw.githubusercontent.com/ArrowOS/android_vendor_arrow/arrow-10.0/arrow.devices".toURL()
def activeDevices = active_devices.split(",");

def node1_devices = []
def node2_devices = []
def node3_devices = []

@NonCPS
String getDeviceHal(def device) {
    String gotHal = null
    officialDevicesUrl.eachLine {
        if(it.charAt(0) != null && it.charAt(0) != "#") {
            if(it.split(" ")[1] == device) {
                gotHal = it.split(" ")[3]
            }
        }
    }
    return gotHal
}

@NonCPS
Boolean isExplicitN1(def device) {
    Boolean isN1 = false
    officialDevicesUrl.eachLine {
        if(it.charAt(0) != null && it.charAt(0) != "#") {
            if(it.split(" ")[0] == "\$" && it.split(" ")[1] == device) {
                isN1 = true
            }
        }
    }
    return isN1
}

@NonCPS
Boolean isExplicitN2(def device) {
    Boolean isN2 = false
    officialDevicesUrl.eachLine {
        if(it.charAt(0) != null && it.charAt(0) != "#") {
            if(it.split(" ")[0] == "@" && it.split(" ")[1] == device) {
                isN2 = true
            }
        }
    }
    return isN2
}

@NonCPS
Boolean isExplicitN3(def device) {
    Boolean isN3 = false
    officialDevicesUrl.eachLine {
        if(it.charAt(0) != null && it.charAt(0) != "#") {
            if(it.split(" ")[0] == "!" && it.split(" ")[1] == device) {
                isN3 = true
            }
        }
    }
    return isN3
}

node("master") {
    stage("Assigning Nodes!") {
        try {
            String nodeStructure = nodeStructureUrl.text
            def nodeStJson = jsonParse(nodeStructure)
    
            if(activeDevices != null && activeDevices.length !=0 && activeDevices[0] != "none") {
                for(device in activeDevices) {
                    String assign_node = null
                    String devHal = getDeviceHal(device)

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

                        if(nodeStJson["arrow-"+i][0]["hals"].contains(devHal)) {
                            assign_node = "Arrow-${i}"
                        }
                    }

                    if(assign_node == "Arrow-1") {
                        node1_devices.add(device)
                    } else if(assign_node == "Arrow-2") {
                        node2_devices.add(device)
                    } else if(assign_node == "Arrow-3") {
                        node3_devices.add(device)
                    } else {
                        echo "No node assigned for ${device}"
                    }
                }

                // Node 3
                if(node3_devices != null && node3_devices.length !=0) {
                    echo "-------------------------------"
                    echo "Devices assigned for Arrow-3"
                    echo "-------------------------------"
                    for(n3dev in node3_devices) {
                        if(n3dev != null && !n3dev.isEmpty() && n3dev != "none") {
                            echo "Triggering build for ${n3dev}"
                            build job: 'Arrow-Builder', parameters: [
                                string(name: 'DEVICE', value: n3dev),
                                string(name: 'ASSIGNED_NODE', value: "Arrow-3"),
                                string(name: 'BUILD_TIMESTAMP', value: calcDate() + calcTimestamp())
                            ], propagate: false, wait: false
                            sleep 2
                        }
                    }
                }

                // Node 2
                if(node2_devices != null && node2_devices.length !=0) {
                    echo "-------------------------------"
                    echo "Devices assigned for Arrow-2"
                    echo "-------------------------------"
                    for(n2dev in node2_devices) {
                        if(n2dev != null && !n2dev.isEmpty() && n2dev != "none") {
                            echo "Triggering build for ${n2dev}"
                            build job: 'Arrow-Builder', parameters: [
                                string(name: 'DEVICE', value: n2dev),
                                string(name: 'ASSIGNED_NODE', value: "Arrow-2"),
                                string(name: 'BUILD_TIMESTAMP', value: calcDate() + calcTimestamp())
                            ], propagate: false, wait: false
                            sleep 2
                        }
                    }
                }

                // Node 1
                if(node1_devices != null && node1_devices.length !=0) {
                    echo "-------------------------------"
                    echo "Devices assigned for Arrow-1"
                    echo "-------------------------------"
                    for(n1dev in node1_devices) {
                        if(n1dev != null && !n1dev.isEmpty() && n1dev != "none") {
                            echo "Triggering build for ${n1dev}"
                            build job: 'Arrow-Builder', parameters: [
                                string(name: 'DEVICE', value: n1dev),
                                string(name: 'ASSIGNED_NODE', value: "Arrow-1"),
                                string(name: 'BUILD_TIMESTAMP', value: calcDate() + calcTimestamp())
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