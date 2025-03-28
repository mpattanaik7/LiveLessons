package utils;

import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.StampedLock;

/**
 * Schedule a countdown until a time in the future, with regular
 * notifications on intervals along the way.
 *
 * Example of showing a 30 second countdown in a text field:
 *
 * <pre class="prettyprint">
 * new CountDownTimer(lock, 30000, 1000) {
 *
 *     public void onTick(long millisUntilFinished) {
 *         mTextField.setText("seconds remaining: " + millisUntilFinished / 1000);
 *     }
 *
 *     public void onFinish() {
 *         mTextField.setText("done!");
 *     }
 *  }.start();
 * </pre>
 *
 * The calls to {@link #onTick(long)} are synchronized to this object
 * so that one call to {@link #onTick(long)} will never occur before
 * the previous callback is complete.  This is only relevant when the
 * implementation of {@link #onTick(long)} takes an amount of time to
 * execute that is significant compared to the countdown interval.
 *
 * This framework implementation works fine if the {@link Lock}
 * parameter passed to the {@link CountDownTimer} constructor has reentrant
 * lock semantics.  It will self-deadlock, however, if the {@link Lock}
 * parameter has non-reentrant lock semantics.
 */
public abstract class CountDownTimer {
    /**
     * Millis since epoch when alarm should stop.
     */
    protected final long mMillisInFuture;

    /**
     * The interval in millis that the user receives callbacks
     */
    private final long mCountdownInterval;

    /**
     * Executor service that executes runnables after a given timeout.
     */
    private final ScheduledExecutorService mScheduledExecutorService =
        Executors
        .newScheduledThreadPool(1,
                                r -> {
                                    Thread thr = new Thread(r);
                                    // Use a daemon thread to ensure
                                    // ScheduledThreadPool shuts down
                                    // when the main thread exits.
                                    thr.setDaemon(true);
                                    return thr;
                                });

    /**
     * When to stop the timer.
     */
    private long mStopTimeInFuture;
    
    /**
     * Boolean representing if the timer was cancelled.
     */
    private boolean mCancelled = false;

    /**
     * The lock used to synchronize access to the object.  If this is
     * a reentrant lock then all is well.  However, if it's a
     * non-reentrant lock the calling code can self-deadlock.
     */
    private final Lock mLock;

    /**
     * @param lock The {@link Lock} used to synchronize access to the
     *             object
     * @param millisInFuture The number of millis in the future from
     *                       the call to {@link #start()} until the
     *                       countdown is done and {@link #onFinish()}
     *                       is called
     * @param countDownInterval The interval along the way to receive
     *                          {@link #onTick(long)} callbacks
     */
    public CountDownTimer(Lock lock,
                          long millisInFuture,
                          long countDownInterval) {
        mLock = lock;
        mMillisInFuture = millisInFuture;
        mCountdownInterval = countDownInterval;

        // Obtain the underlying ScheduledThreadPoolExecutor.
        ScheduledThreadPoolExecutor exec =
            (ScheduledThreadPoolExecutor) mScheduledExecutorService;

        // Set the policies to clean everything up on shutdown.
        exec.setRemoveOnCancelPolicy(true);
        exec.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        exec.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    /**
     * Cancel the countdown.
     */
    public final void cancel() {
        // If mLock isn't a reentrant lock this call
        // will self-deadlock!
        mLock.lock();
        try {
            // Update shared mutable state.
            mCancelled = true;

            // Shutdown the ScheduledExecutorService immediately.
            mScheduledExecutorService.shutdownNow();
        } finally {
            mLock.unlock();
        }
    }

    /**
     * @return a {@link CountDownTimer} that has been started
     */
    public CountDownTimer start() {
        mLock.lock();
        try {
            // We haven't been canceled (yet).
            mCancelled = false;

            // Handle odd starting point.
            if (mMillisInFuture <= 0) {
                // Call hook method to indicate we're done.
                onFinish();
                return this;
            }

            // Calculate when to stop.
            mStopTimeInFuture = 
                System.currentTimeMillis() + mMillisInFuture;

            // Schedule the initial timer.
            scheduleTimer();

            // Return this object to support a fluent interface.
            return this;
        } finally {
            // Always unlock the lock.
            mLock.unlock();
        }
    }

    /**
     * Schedules a timer that performs the count down logic.
     */
    private void scheduleTimer() {
        // Create an object that's (re)scheduled to run periodically
        // (a lambda expression can't be used here since 'this' is
        // accessed below).
        Runnable timerHandler = new Runnable() {
            @Override
            public void run() {
                mLock.lock();
                try {
                    // Stop running if we've been canceled.
                    if (mCancelled) 
                        return;

                    // Determine how much time is left.
                    final long millisLeft =
                        mStopTimeInFuture - System.currentTimeMillis();

                    // If all the time has elapsed dispatch the
                    // onFinish() hook method.
                    if (millisLeft <= 0) 
                        onFinish();
                    else {
                        long lastTickStart = System.currentTimeMillis();
                        // Dispatch the onTick() hook method. Note how
                        // mLock is locked!
                        onTick(millisLeft);

                        // Take into account user's onTick taking time to
                        // execute.
                        long lastTickDuration =
                            System.currentTimeMillis() - lastTickStart;
                        long delay;

                        if (millisLeft < mCountdownInterval) {
                            // Just delay until done.
                            delay = millisLeft - lastTickDuration;

                            // Special case: user's onTick took more
                            // than mCountInterval to complete,
                            // trigger onFinish() without delay.
                            if (delay < 0) delay = 0;
                        } else {
                            delay = mCountdownInterval - lastTickDuration;

                            // Special case: user's onTick took more
                            // than mCountInterval to complete, skip
                            // to next interval.
                            while (delay < 0) delay += mCountdownInterval;
                        }

                        // Reschedule this timerHandler to run again at
                        // the appropriate delay in the future.
                        mScheduledExecutorService
                            .schedule(this,
                                      delay,
                                      TimeUnit.MILLISECONDS);
                    }
                } finally {
                    // Always unlock the lock here.
                    mLock.unlock();
                }
            }
        };

        // Initially schedule the timerHandler to run immediately.
        mScheduledExecutorService
            .schedule(timerHandler,
                      0,
                      TimeUnit.MILLISECONDS);
    }

    /**
     * This callback is fired at regular interval.
     *
     * @param millisUntilFinished The amount of time until finished
     */
    public abstract void onTick(long millisUntilFinished);

    /**
     * Callback fired when the whole time period has elapsed.
     */
    public abstract void onFinish();
}
