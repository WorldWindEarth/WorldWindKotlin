package earth.worldwind.examples

import kotlinx.coroutines.*

@OptIn(DelicateCoroutinesApi::class)
abstract class CoroutinesAsyncTask<Params, Progress, Result> {
    enum class Status { PENDING, RUNNING, FINISHED }

    var status = Status.PENDING
    private var job: Job? = null

    abstract fun doInBackground(vararg params: Params): Result?
    open fun onPreExecute() {}
    open fun onProgressUpdate(vararg values: Progress) {}
    open fun onPostExecute(result: Result?) {}

    fun execute(vararg params: Params) {
        when (status) {
            Status.RUNNING -> error("Cannot execute task: the task is already running.")
            Status.FINISHED -> error("Cannot execute task: the task has already been executed")
            else -> status = Status.RUNNING
        }

        // it can be used to set up UI - it should have access to Main Thread
        job = GlobalScope.launch(Dispatchers.Main) {
            onPreExecute()
            val result = withContext(Dispatchers.Default) { doInBackground(*params) }
            if (isActive) onPostExecute(result)
            status = Status.FINISHED
        }
    }

    fun cancel(mayInterruptIfRunning: Boolean = true) {
        if (mayInterruptIfRunning || job?.isActive != true) {
            job?.cancel()
            status = Status.FINISHED
        }
    }

    fun publishProgress(vararg progress: Progress) {
        //need to update main thread
        GlobalScope.launch(Dispatchers.Main) {
            if (job?.isActive == true) onProgressUpdate(*progress)
        }
    }
}