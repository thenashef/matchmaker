package com.matchmaker.common.fx;

import javafx.application.Platform;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class FxAsync {

    private FxAsync() {
    }

    public static ExecutorService daemonExecutor(String threadName) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    public static ScheduledExecutorService startKeepAlive(Duration interval, ThrowingRunnable ping, Logger log) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "keep-alive");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMillis = interval.toMillis();
        executor.scheduleAtFixedRate(() -> {
            try {
                ping.run();
            } catch (Exception e) {
                log.log(Level.WARNING, "keepAlive failed", e);
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        return executor;
    }

    public static <T> void run(ExecutorService executor, ThrowingSupplier<T> action,
                               Consumer<T> onSuccess, Consumer<Throwable> onError) {
        executor.submit(() -> {
            try {
                T result = action.get();
                Platform.runLater(() -> onSuccess.accept(result));
            } catch (Exception e) {
                Platform.runLater(() -> onError.accept(e));
            }
        });
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
