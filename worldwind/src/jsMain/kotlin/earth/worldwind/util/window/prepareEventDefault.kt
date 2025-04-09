package earth.worldwind.util.window

import org.w3c.dom.TouchEvent
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import org.w3c.dom.events.WheelEvent
import org.w3c.dom.events.WheelEventInit
import org.w3c.dom.pointerevents.PointerEvent
import org.w3c.dom.pointerevents.PointerEventInit

/**
 * If we use multi window features of a browser,
 * we need to create window.*Event instances, to work
 * with them in the code of the main window (where the application is running).
 *
 * This function isn't part of the class itself and may be altered
 * with another implementations, because the way of cloning may be imprecise
 * for purposes of particular application.
 */
val prepareEventDefault: PrepareEventHandler = { dirtyEvent ->
    when (true) {
        dirtyEvent.type.startsWith("mouse") -> {
            val event = dirtyEvent.unsafeCast<MouseEvent>()
            MouseEvent(
                event.type, MouseEventInit(
                    bubbles = event.bubbles,
                    cancelable = event.cancelable,
                    clientX = event.clientX,
                    clientY = event.clientY,
                    screenX = event.screenX,
                    screenY = event.screenY,
                    button = event.button,
                    buttons = event.buttons,
                    relatedTarget = event.relatedTarget
                )
            )
        }

        dirtyEvent.type.startsWith("pointer") -> {
            val event = dirtyEvent.unsafeCast<PointerEvent>()
            PointerEvent(
                event.type, PointerEventInit(
                    bubbles = event.bubbles,
                    cancelable = event.cancelable,
                    composed = event.composed,

                    clientX = event.clientX,
                    clientY = event.clientY,
                    screenX = event.screenX,
                    screenY = event.screenY,

                    button = event.button,
                    buttons = event.buttons,
                    relatedTarget = event.relatedTarget,

                    pointerId = event.pointerId,
                    width = event.width,
                    height = event.height,
                    pressure = event.pressure,
                    tangentialPressure = event.tangentialPressure,
                    tiltX = event.tiltX,
                    tiltY = event.tiltY,
                    twist = event.twist,

                    pointerType = event.pointerType,
                    isPrimary = event.isPrimary
                )
            )
        }

        (dirtyEvent.type == "wheel") -> {
            val event = dirtyEvent.unsafeCast<WheelEvent>()
            WheelEvent(
                event.type, WheelEventInit(
                    bubbles = event.bubbles,
                    cancelable = event.cancelable,
                    composed = event.composed,
                    deltaX = event.deltaX,
                    deltaY = event.deltaY,
                    deltaZ = event.deltaZ,
                    deltaMode = event.deltaMode,
                    clientX = event.clientX,
                    clientY = event.clientY,
                    screenX = event.screenX,
                    screenY = event.screenY,
                    ctrlKey = event.ctrlKey,
                    shiftKey = event.shiftKey,
                    altKey = event.altKey,
                    metaKey = event.metaKey,
                    button = event.button,
                    buttons = event.buttons,
                    relatedTarget = event.relatedTarget
                )
            )
        }

        /**
         * It may work not in all browsers correctly.
         */
        dirtyEvent.type.startsWith("touch") -> try {
            val event = dirtyEvent.unsafeCast<TouchEvent>()
            // Create new event this way, because `TouchEvent` constructor doesn't accept event type.
            val clone = js(
                "new TouchEvent(event.type, {" +
                        "bubbles: event.bubbles," +
                        "cancelable: event.cancelable," +
                        "composed: event.composed," +
                        "touches: event.touches," +
                        "targetTouches: event.targetTouches," +
                        "changedTouches: event.changedTouches," +
                        "ctrlKey: event.ctrlKey," +
                        "shiftKey: event.shiftKey," +
                        "altKey: event.altKey," +
                        "metaKey: event.metaKey," +
                        "detail: event.detail," +
                        "which: event.which," +
                        "})"
            ) as TouchEvent
            clone
        } catch (e: Throwable) {
            console.warn("Failed to adapt touch event: ${dirtyEvent.type}. May window will fail even type checks.", e)
            dirtyEvent
        }

        else -> {
            console.info("Event converter between windows is not implemented for event type: ${dirtyEvent.type}.")
            dirtyEvent
        }
    }
}