package earth.worldwind.util.window

/**
 * If we don't use multi window features of a browser,
 * we are safe to just pass the event further.
 */
val prepareEventVoid: PrepareEventHandler = { event -> event }
