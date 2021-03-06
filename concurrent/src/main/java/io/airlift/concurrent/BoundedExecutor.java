package io.airlift.concurrent;

import com.google.common.base.Preconditions;
import io.airlift.log.Logger;

import javax.annotation.concurrent.ThreadSafe;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Guarantees that no more than maxThreads will be used to execute tasks submitted
 * through {@link #execute(Runnable) execute()}.
 * <p>
 * There are a few interesting properties:
 * <ul>
 * <li>Multiple BoundedExecutors over a single coreExecutor will have fair sharing
 * of the coreExecutor threads proportional to their relative maxThread counts, but
 * can use less if not as active.</li>
 * <li>Tasks submitted to a BoundedExecutor is guaranteed to have those tasks
 * handed to threads in that order.</li>
 * <li>Will not encounter starvation</li>
 * </ul>
 * <p>
 * Note that each task submitted to this executor will still submit a trigger
 * task to the underlying executor. Thus, use of this class in conjunction
 * with an unbounded thread pool, such as one created by
 * {@link java.util.concurrent.Executors#newCachedThreadPool() newCachedThreadPool()},
 * will not prevent a spike in thread creations when many tasks are submitted
 * at once. Use {@link BoundedThreadPool} to provide a hard limit on thread creation.
 */
@ThreadSafe
public class BoundedExecutor
        implements Executor
{
    private static final Logger log = Logger.get(BoundedExecutor.class);

    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger(0);
    private final Runnable triggerTask = new Runnable()
    {
        @Override
        public void run()
        {
            executeOrMerge();
        }
    };

    private final Executor coreExecutor;
    private final int maxThreads;

    public BoundedExecutor(Executor coreExecutor, int maxThreads)
    {
        Preconditions.checkNotNull(coreExecutor, "coreExecutor is null");
        Preconditions.checkArgument(maxThreads > 0, "maxThreads must be greater than zero");
        this.coreExecutor = coreExecutor;
        this.maxThreads = maxThreads;
    }

    @Override
    public void execute(Runnable task)
    {
        queue.add(task);
        coreExecutor.execute(triggerTask);
        // INVARIANT: every enqueued task is matched with an executeOrMerge() triggerTask
    }

    private void executeOrMerge()
    {
        int size = queueSize.incrementAndGet();
        if (size <= maxThreads) {
            do {
                try {
                    queue.poll().run();
                }
                catch (Throwable e) {
                    log.error(e, "Task failed");
                }
            }
            while (queueSize.getAndDecrement() > maxThreads);
        }
    }
}
