package esw.ocs.dsl.epics

import akka.Done
import csw.params.core.generics.Key
import csw.params.core.generics.Parameter
import csw.params.events.Event
import csw.params.events.EventKey
import esw.ocs.dsl.add
import esw.ocs.dsl.highlevel.EventServiceDsl
import esw.ocs.dsl.highlevel.models.EventSubscription
import esw.ocs.dsl.params.first
import esw.ocs.dsl.params.invoke
import kotlin.time.Duration

open class EventVariable protected constructor(
        initial: Event,
        private val eventService: EventServiceDsl,
        private val duration: Duration? = null
) {
    private val eventKey: String = initial.eventKey().key()

    private var latestEvent: Event = initial
    private val subscribers: MutableSet<Refreshable> = mutableSetOf()
    private var eventSubscription: EventSubscription? = null

    suspend fun bind(refreshable: Refreshable): FsmSubscription {
        subscribers.add(refreshable)
        if (subscribers.size == 1) eventSubscription = startSubscription()
        val fsmSubscription = FsmSubscription { unsubscribe(refreshable) }
        refreshable.addFsmSubscription(fsmSubscription)
        return fsmSubscription
    }

    fun getEvent(): Event = latestEvent

    private suspend fun startSubscription(): EventSubscription = if (duration != null) polling(duration) else subscribe()

    private suspend fun polling(duration: Duration): EventSubscription =
            eventService.onEvent(eventKey, duration = duration) {
                if (it != latestEvent) refresh(it)
            }

    private suspend fun subscribe(): EventSubscription = eventService.onEvent(eventKey) { refresh(it) }

    private suspend fun refresh(event: Event) {
        if (!event.isInvalid) {
            latestEvent = event
            subscribers.forEach { it.refresh() }
        }
    }

    private suspend fun unsubscribe(refreshable: Refreshable) {
        subscribers.remove(refreshable)
        if (subscribers.isEmpty()) eventSubscription?.cancel()
    }

    companion object {
        suspend fun make(eventKey: EventKey, eventService: EventServiceDsl, duration: Duration? = null): EventVariable {
            val initial = eventService.getEvent(eventKey.key()).first()
            return EventVariable(initial, eventService, duration)
        }
    }
}

class ParamVariable<T> private constructor(
        initial: Event,
        private val key: Key<T>,
        private val eventService: EventServiceDsl,
        duration: Duration? = null
) : EventVariable(initial, eventService, duration) {

    fun getParam(): Parameter<T> = (getEvent().paramType()).invoke(key)

    // extract first value from a parameter against provided key from param set
    // if not present, throw an exception
    fun first(): T = getParam().first

    suspend fun setParam(vararg value: T): Done = eventService.publishEvent(getEvent().add(key.set(*value)))

    companion object {
        suspend fun <T> make(initial: T, key: Key<T>, eventKey: EventKey, eventService: EventServiceDsl, duration: Duration? = null): ParamVariable<T> {
            val availableEvent = eventService.getEvent(eventKey.key()).first()
            val initialEvent = availableEvent.add(key.set(initial))
            eventService.publishEvent(initialEvent)

            return ParamVariable(initialEvent, key, eventService, duration)
        }
    }
}