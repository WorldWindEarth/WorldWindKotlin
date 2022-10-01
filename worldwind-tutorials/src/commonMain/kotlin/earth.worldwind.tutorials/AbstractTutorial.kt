package earth.worldwind.tutorials

abstract class AbstractTutorial {

    /**
     * Defines a list of custom actions
     */
    open val actions: ArrayList<String>? = null

    /**
     * Runs any of custom actions listed in [actions]
     */
    open fun runAction(actionName: String) {}

    /**
     * Runs after switching to this example
     */
    open fun start() {}

    /**
     * Runs before switching to another example
     */
    open fun stop() {}

}