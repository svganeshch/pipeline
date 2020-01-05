import groovy.json.JsonSlurper

String calcDate() { ['date', '+%Y%m%d'].execute().text.trim()}
String calcTimestamp() { ['date', '+%s'].execute().text.trim()}

int NO_OF_NODES = 3
def jsonParse(def json) { new groovy.json.JsonSlurperClassic().parseText(json) }
nodeStructureUrl = "https://raw.githubusercontent.com/ArrowOS/android_vendor_arrow/arrow-10.0/node_structure.json".toURL()
officialDevicesUrl = "https://raw.githubusercontent.com/ArrowOS/android_vendor_arrow/arrow-10.0/arrow.devices".toURL()
def activeDevices = active_devices.split(",");

@NonCPS
String getDeviceHal(device) {
    String gotHal = null
    officialDevicesUrl.eachLine {
        if(it.charAt(0) != null && it.charAt(0) != "#") {
            if(it.split(" ")[1] == device) {
                gotHal = it.split(" ")[2]
            }
        }
    }
    return gotHal
}

@NonCPS
Boolean isExplicitN3(def device) {
    Boolean isN3 = false
    officialDevicesUrl.eachLine {
        if(it.charAt(0) != null && it.charAt(0) != "#") {
            if(it.split(" ")[0] == "!") {
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
                        if(isExplicitN3(device)) {
                            assign_node = "Arrow-3"
                            echo "Explictly assigning ${assign_node} node for ${device}"
                            break
                        }

                        if(nodeStJson["arrow-"+i][0]["hals"].contains(devHal)) {
                            assign_node = "Arrow-${i}"
                        }
                    }
    
                    if(assign_node != null && !assign_node.isEmpty() && assign_node != "none") {
                        echo "Triggering build for ${device} on ${assign_node}"
                        build job: 'Arrow-Builder', parameters: [
                            string(name: 'DEVICE', value: device),
                            string(name: 'ASSIGNED_NODE', value: assign_node),
                            string(name: 'BUILD_TIMESTAMP', value: calcDate() + calcTimestamp())
                        ], propagate: false, wait: false
                        sleep 2
                    } else {
                        echo "No node assigned for ${device}"
                        continue
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