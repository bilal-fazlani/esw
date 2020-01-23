@file:Suppress("UNUSED_VARIABLE")

package esw.ocs.scripts.examples.paradox

import csw.params.events.Event
import csw.params.events.EventKey
import csw.params.events.ObserveEvent
import csw.params.events.SystemEvent
import esw.ocs.dsl.core.script
import esw.ocs.dsl.epics.EventVariable
import esw.ocs.dsl.params.intKey
import kotlin.time.seconds

script {

    onSetup("publish-event") {


        //#system-event
        //#observe-event
        val parameters = intKey("temperature").set(0)
        //#observe-event
        //#publish
        val systemEvent: SystemEvent = SystemEvent("esw.temperature", "temp", parameters)
        //#publish
        //#system-event

        //#observe-event
        val observeEvent: ObserveEvent = ObserveEvent("ocs.master", "observationStarted")
        //#observe-event

        //#publish
        publishEvent(systemEvent)
        //#publish

        //#publish-async
        publishEvent(10.seconds) {
            // event generator which returns event to publish after the given interval
            systemEvent
        }
        //#publish-async


        //#subscribe
        //#get-event
        val tempEventKey = "esw.temperature.temp"
        val stateEventKey = "esw.temperature.state"
        //#get-event
        onEvent(tempEventKey, stateEventKey) { event ->
            // logic to execute on every event
            println(event.eventKey())
        }
        //#subscribe

        //#subscribe-async
        onEvent(tempEventKey, stateEventKey, duration = 2.seconds) { event ->
            // logic to execute on every event
            println(event.eventKey())
        }
        //#subscribe-async

        //#get-event
        val events: Set<Event> = getEvent(tempEventKey, stateEventKey)
        //#get-event

        //#event-key
        // full event key string
        val tempKey: EventKey = EventKey("esw.temperature.temp")

        // prefix and event name strings
        val tempKey1: EventKey = EventKey("esw.temperature", "temp")
        //#event-key

        //#system-var
        val locKey = intKey("current-location")
        val systemVar: EventVariable<Int> = SystemVar(0, "ocs.motor.position", locKey)
        //#system-var

        //#observe-var
        val readNumberKey = intKey("readNumber")
        val observeVar: EventVariable<Int> = ObserveVar(0, "IRIS.ifs.detector.readCompleted", readNumberKey)
        //#observe-var

    }

}
