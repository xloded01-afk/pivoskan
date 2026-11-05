package ru.pivoskan.app.data.remote

import ru.pivoskan.app.data.*
import ru.pivoskan.app.data.scan.*
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal fun sourceProblem(error: Exception, stage: String): SourceProblem {
    val source = error as? SourceException
    return when {
        error is InterruptedException -> SourceProblem(ProblemKind.CANCELLED, stage, "CANCELLED")
        source?.statusCode != null -> SourceProblem(if (source.blocked) ProblemKind.NEEDS_ACTION else ProblemKind.HTTP,
            stage, "HTTP_${source.statusCode}")
        source?.blocked == true -> SourceProblem(ProblemKind.NEEDS_ACTION, stage, "NEEDS_ACTION")
        error is java.io.IOException || error.cause is java.io.IOException -> SourceProblem(ProblemKind.NETWORK, stage, "NETWORK")
        else -> SourceProblem(ProblemKind.INVALID_DATA, stage, "INVALID_DATA")
    }
}

abstract class DirectCitySource(private val executor: Executor,
    private val factory: () -> CancellableHttpTransport) : CitySource {
    final override val lane = SourceLane.DIRECT
    protected fun <T> execute(work: (HttpTransport) -> T, failure: (Exception) -> T, done: (T) -> Unit): Cancellable {
        val cancelled = AtomicBoolean(false)
        val transport = factory()
        executor.execute {
            if (!cancelled.get()) {
                val result = try { work(transport) } catch (e: Exception) { failure(e) }
                if (!cancelled.get()) done(result)
            }
        }
        return Cancellable { cancelled.set(true); transport.cancel() }
    }
    protected fun failedDiscovery(e: Exception, scope: PriceScope = PriceScope.STORE) =
        DiscoveryResult(emptyList(), scope, false, problem = sourceProblem(e, "discover"))
}
