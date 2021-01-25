/**
 *  Partly based on source originally copyright 2018-2019 SmartThings
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 */
metadata {
	definition (name: "Lutron Aurora Dimmer - Custom", namespace: "edu", author: "Eduardo Simioni") {
		capability "Actuator"
        capability "Battery"
		capability "Configuration"
		capability "Pushable Button"
        capability "Refresh"
        capability "Switch"
		capability "Switch Level"

		fingerprint profileId: "0104", inClusters: "0000,0001,0003,1000,FC00", outClusters: "0003,0004,0006,0008,0019,1000", manufacturer: "Lutron", model: "Z3-1BRL"
    }
    preferences {
        input(name: "debug", type: "bool", title: "Enable debug logging", description: "", defaultValue: false, submitOnChange: true, displayDuringSetup: false, required: false)
        
        input(name: "useDeviceSpeed", type: "bool", title: "Use level change speed from the physical device", description: "Not recommended (default: disabled)", defaultValue: false)
        input(name: "useButtonAsSwitch", type: "bool", title: "Use button click to switch on/off", description: "If disabled the event is available to be used in the Rule Machine (default: disabled)", defaultValue: false)
        input(name: "silentTimeAfterClick", type: "number", title: "Ignore rotation after a click", description: "In milliseconds, due to a device bug, see docs.", defaultValue: "500", range: "1..2000")
        
        input(name: "speedFastestThreshold", type: "number", title: "Fastest speed threshold", description: "In milliseconds", defaultValue: "25", range: "1..1000")
        input(name: "speedFastThreshold", type: "number", title: "Fast speed threshold", description: "In milliseconds", defaultValue: "100", range: "1..1000")
        input(name: "speedMediumThreshold", type: "number", title: "Medium speed threshold", description: "In milliseconds", defaultValue: "200", range: "1..1000")
        input(name: "speedLowThreshold", type: "number", title: "Low speed threshold", description: "In milliseconds", defaultValue: "300", range: "1..1000")
        
        input(name: "speedFastestIncrement", type: "number", title: "Fastest speed increment", description: "In level %", defaultValue: "12", range: "1..100")
        input(name: "speedFastIncrement", type: "number", title: "Fast speed increment", description: "In level %", defaultValue: "8", range: "1..100")
        input(name: "speedMediumIncrement", type: "number", title: "Medium speed increment", description: "In level %", defaultValue: "4", range: "1..100")
        input(name: "speedLowIncrement", type: "number", title: "Low speed increment", description: "In level %", defaultValue: "2", range: "1..100")
    }
}

def parse(String description) {
	if (debug) log.debug "description = $description"
	def event = zigbee.getEvent(description)
	if (event) {
        if (debug) log.debug "sending ${event}"
        if (event.name == "battery") {
            sendEvent(name: event.name, value: event.value, unit: "%", isStateChange: false)
		} else if (event.name == "batteryVoltage") {
            sendEvent(name: event.name, value: event.value, unit: "v", isStateChange: false)
		} else {
            log.warn("Unknown event!!!")
			sendEvent(event)
        }
	} else {
		def descMap = zigbee.parseDescriptionAsMap(description)
        if (descMap && descMap.clusterInt == 0x008) {
            if (descMap.data.size() < 3) {
                if (debug) log.debug "Ignoring data ${descMap.data}"
            } else {
               handleLevelEvent(descMap)
            }
        } else if (descMap && descMap.clusterInt == 0x0006) {
            handleButtonEvent()
		} else {
			log.warn "DID NOT PARSE MESSAGE for description : $description"
			if (debug) log.debug "${descMap}"
		}
	}
}

def handleButtonEvent() {
    state.lastClick = now()
    if (useButtonAsSwitch) {
        if (debug) log.debug "Button pressed, handling as switch"
        if (state.level == 0) {
            setLevel(1)
        } else {
            sendSwitchEvent(device.currentValue("switch") != "on")
        }
    } else {
        if (debug) log.debug "Button pressed, handling as pushed"
        sendEvent(name: "pushed", value: 1, isStateChange: true, descriptionText: "Aurora button was pushed")
    }
}

def sendSwitchEvent(boolean on) {
    sendEvent(name: "switch", value: on ? "on" : "off", isStateChange: true)
}

def setLevel(value) {
    if (value > 0 && device.currentValue("switch") == "off") {
        sendSwitchEvent(true)
    }
    state.level = value
    sendEvent(name: "level", value: value)
}

def handleLevelEvent(descMap) {
    state.changeTime = now()
    if (state.changeTime - state.lastClick < silentTimeAfterClick) {
        if (debug) log.debug "Ignoring level even under ${silentTimeAfterClick}ms after a click"
        return
    }
    if (debug) log.debug "Level data = ${descMap.data}"
    if (debug) log.debug "Button turned, setting level from driver data"
    int deviceLevel = descMap.data.size() < 3 ? 0 : Integer.parseInt(descMap.data[0], 16) * (100/254)
    calculateSpeed(deviceLevel)
    if (deviceLevel > state.previousDeviceLevel || (deviceLevel == 100 && state.previousDeviceLevel == 100)) {
        if (debug) log.debug "Increasing driver level"
        state.level = state.level + state.speed
    } else if (deviceLevel < state.previousDeviceLevel || (deviceLevel == 0 && state.previousDeviceLevel == 0)) {
        if (debug) log.debug "Decreasing driver level"
        state.level = state.level - state.speed
    }
    state.previousDeviceLevel = deviceLevel
    if (state.level > 100) {
        if (debug) log.debug "Limiting driver level to 100"
        state.level = 100;
    } else if (state.level < 0) {
        if (debug) log.debug "Limiting driver level to 0"
        state.level = 0;
    }
    if (debug) log.debug "Device level = ${deviceLevel}"
    if (debug) log.debug "Driver level = ${state.level}%"
    setLevel(state.level)
}

def calculateSpeed(deviceLevel) {
    if (useDeviceSpeed && deviceLevel - state.previousDeviceLevel != 0) {
        state.speed = Math.abs(deviceLevel - state.previousDeviceLevel);
        if (debug) log.debug "Device speed is ${state.speed}"
        return
    }
    long intervalMillis = state.changeTime - state.previousChangeTime

    if (intervalMillis <= speedFastestThreshold) {
        state.speed = speedFastestIncrement
    } else if (intervalMillis <= speedFastThreshold) {
        state.speed = speedFastIncrement
    } else if (intervalMillis <= speedMediumThreshold) {
        state.speed = speedMediumIncrement
    } else if (intervalMillis <= speedLowThreshold) {
        state.speed = speedLowIncrement
    } else {
        state.speed = 1
    }
    state.previousChangeTime = state.changeTime
    if (debug) log.debug "Calculated speed is ${state.speed} (${intervalMillis}ms)"
}

def off() {
	sendEvent(name: "switch", value: "off", isStateChange: true)
}

def on() {
	sendEvent(name: "switch", value: "on", isStateChange: true)
}

def installed() {
    log.info "Installed..."
    refresh()   
}

def refresh() {
    log.info "Refreshed..."
    resetParameters()
    resetState()
    setDriverVersion()
}

def resetParameters() {
    sendEvent(name: "switch", value: "off", displayed: false)
	sendEvent(name: "level", value: 0, displayed: false)
	sendEvent(name: "pushed", value: "1", displayed: false)
	sendEvent(name: "numberOfButtons", value: 1, displayed: false)
}

def resetState() {
    state.previousDeviceLevel = 0
    state.speed = 1
    state.level = 0
    state.lastClick = 0;
    state.changeTime = 0;
    state.previousChangeTime = 0;
}

def setDriverVersion() {
    state.comment = "Custom driver for Lutron Aurora Dimmer"
    sendEvent(name: "driver", value: "1.0.0")
}

def configure() {
    log.info "Configured..."
    refresh()
    zigbee.onOffConfig() + 
        zigbee.levelConfig() +
        zigbee.enrollResponse() + 
        zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x20)
}