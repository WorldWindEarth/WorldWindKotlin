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
val prepareEventDefault: PrepareEventHandler = { event ->
    when (true) {
        event.type.startsWith("mouse") -> {
            val e0 = event.unsafeCast<MouseEvent>()
            MouseEvent(
                e0.type, MouseEventInit(
                    bubbles = e0.bubbles,
                    cancelable = e0.cancelable,
                    clientX = e0.clientX,
                    clientY = e0.clientY,
                    screenX = e0.screenX,
                    screenY = e0.screenY,
                    button = e0.button,
                    buttons = e0.buttons,
                    relatedTarget = e0.relatedTarget
                )
            )
        }

        event.type.startsWith("pointer") -> {
            val e0 = event.unsafeCast<PointerEvent>()
            PointerEvent(
                e0.type, PointerEventInit(
                    bubbles = e0.bubbles,
                    cancelable = e0.cancelable,
                    composed = e0.composed,

                    clientX = e0.clientX,
                    clientY = e0.clientY,
                    screenX = e0.screenX,
                    screenY = e0.screenY,

                    button = e0.button,
                    buttons = e0.buttons,
                    relatedTarget = e0.relatedTarget,

                    pointerId = e0.pointerId,
                    width = e0.width,
                    height = e0.height,
                    pressure = e0.pressure,
                    tangentialPressure = e0.tangentialPressure,
                    tiltX = e0.tiltX,
                    tiltY = e0.tiltY,
                    twist = e0.twist,

                    pointerType = e0.pointerType,
                    isPrimary = e0.isPrimary
                )
            )
        }

        (event.type == "wheel") -> {
            val e0 = event.unsafeCast<WheelEvent>()
            WheelEvent(
                e0.type, WheelEventInit(
                    bubbles = e0.bubbles,
                    cancelable = e0.cancelable,
                    composed = e0.composed,
                    deltaX = e0.deltaX,
                    deltaY = e0.deltaY,
                    deltaZ = e0.deltaZ,
                    deltaMode = e0.deltaMode,
                    clientX = e0.clientX,
                    clientY = e0.clientY,
                    screenX = e0.screenX,
                    screenY = e0.screenY,
                    ctrlKey = e0.ctrlKey,
                    shiftKey = e0.shiftKey,
                    altKey = e0.altKey,
                    metaKey = e0.metaKey,
                    button = e0.button,
                    buttons = e0.buttons,
                    relatedTarget = e0.relatedTarget
                )
            )
        }

        /**
         * It may work not in all browsers correctly.
         */
        event.type.startsWith("touch") -> try {
            val e0 = event.unsafeCast<TouchEvent>()
            // Create new event this way, because `TouchEvent` constructor doesn't accept event type.
            val clone = js(
                "new TouchEvent(e0.type, {" +
                        "bubbles: e0.bubbles," +
                        "cancelable: e0.cancelable," +
                        "composed: e0.composed," +
                        "touches: e0.touches," +
                        "targetTouches: e0.targetTouches," +
                        "changedTouches: e0.changedTouches," +
                        "ctrlKey: e0.ctrlKey," +
                        "shiftKey: e0.shiftKey," +
                        "altKey: e0.altKey," +
                        "metaKey: e0.metaKey," +
                        "detail: e0.detail," +
                        "which: e0.which," +
                        "})"
            ) as TouchEvent
            clone
        } catch (e: Throwable) {
            console.warn("Failed to adapt touch event: ${event.type}. May window will fail even type checks.", e)
            event
        }

        else -> {
            console.info("Event converter between windows is not implemented for event type: ${event.type}.")
            event
        }
    }
}