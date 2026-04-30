/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.ditrix.edt.mcp.server.Activator;

/**
 * 1.40.x: Async registry for {@code find_references} long-running searches.
 * <p>
 * Each unique combination of {@code projectName + objectFqn + filter + limit
 * + deep} maps to a single {@link PendingEntry} that holds the
 * {@link CompletableFuture}, started timestamp, and (once available) cached
 * result string.
 * <p>
 * Lifecycle:
 * <ol>
 *   <li>{@link #getOrStart} called by {@code FindReferencesTool} - returns
 *       the entry (creating + dispatching on a worker thread when absent).</li>
 *   <li>The tool calls {@link PendingEntry#await(long)} to wait up to the
 *       configured soft timeout for the future to complete.</li>
 *   <li>If completed within the window: the tool returns the result. The
 *       entry is removed from the registry to free memory.</li>
 *   <li>If the timeout elapses: the tool returns a Pending JSON with the
 *       {@code runKey}; the entry remains for subsequent retries.</li>
 *   <li>Completed entries that were never picked up (the AI never called
 *       again) are evicted by {@link #pruneExpired} every {@code pruneIntervalMs}.</li>
 * </ol>
 *
 * <p>The registry is a singleton because EDT loads our bundle once.
 */
public final class PendingReferencesRegistry
{
    /** TTL for completed entries that were never retrieved. 5 minutes. */
    private static final long COMPLETED_TTL_MS = 5 * 60 * 1000L;

    /** TTL for never-completed entries (runaway). 30 minutes. */
    private static final long ABANDONED_TTL_MS = 30 * 60 * 1000L;

    private static final PendingReferencesRegistry INSTANCE = new PendingReferencesRegistry();

    private final ConcurrentHashMap<String, PendingEntry> entries = new ConcurrentHashMap<>();

    private final ThreadFactory threadFactory = new ThreadFactory()
    {
        private final AtomicLong counter = new AtomicLong(0);

        @Override
        public Thread newThread(Runnable r)
        {
            Thread t = new Thread(r, "find-references-async-" + counter.incrementAndGet()); //$NON-NLS-1$
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        }
    };

    /**
     * 1.41: bounded executor (corePoolSize=2, maxPoolSize=8, queue=20)
     * with {@link ThreadPoolExecutor.CallerRunsPolicy} - backpressure when the
     * queue saturates instead of unbounded thread growth from
     * {@code newCachedThreadPool}. Sized for typical EDT desktop workloads
     * (parallel MCP calls from one or two AI clients).
     *
     * <p>Note: CallerRunsPolicy blocks the calling MCP HTTP-handler thread
     * (from McpServer.mainExecutor). Acceptable for the target audience of
     * 1-2 concurrent AI clients; under load tests of 100+ overflow tasks
     * the HTTP server can saturate.
     */
    private final ExecutorService executor = new ThreadPoolExecutor(
        2, 8,
        60L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(20),
        threadFactory,
        new ThreadPoolExecutor.CallerRunsPolicy());

    private PendingReferencesRegistry()
    {
        // singleton
    }

    public static PendingReferencesRegistry getInstance()
    {
        return INSTANCE;
    }

    /**
     * Returns the existing entry for the given key, or creates and dispatches
     * a new one when absent. Threadsafe.
     */
    public PendingEntry getOrStart(String runKey, java.util.function.Supplier<String> work)
    {
        return entries.computeIfAbsent(runKey, k ->
        {
            PendingEntry entry = new PendingEntry(k);
            entry.future = CompletableFuture.supplyAsync(() ->
            {
                try
                {
                    return work.get();
                }
                catch (Throwable t)
                {
                    Activator.logError("find_references async work failed for runKey=" + k, t); //$NON-NLS-1$
                    return "Error: " + (t.getMessage() != null ? t.getMessage() //$NON-NLS-1$
                        : t.getClass().getSimpleName());
                }
            }, executor);
            // Hook for cleanup: cache the result and stamp completion time
            entry.future.whenComplete((result, throwable) ->
            {
                entry.cachedResult = result != null ? result : ("Error: " + throwable); //$NON-NLS-1$
                entry.completedAt = System.currentTimeMillis();
            });
            return entry;
        });
    }

    /**
     * Returns the entry for the given key if present, or {@code null}.
     * Used by {@code retry} mode (AI explicitly polls a previously-issued
     * runKey).
     */
    public PendingEntry get(String runKey)
    {
        return entries.get(runKey);
    }

    /**
     * Removes an entry once the caller has consumed its result.
     */
    public void remove(String runKey)
    {
        entries.remove(runKey);
    }

    /**
     * Evicts entries past their TTL. Called opportunistically from
     * {@link #getOrStart}.
     */
    public void pruneExpired()
    {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, PendingEntry>> it = entries.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<String, PendingEntry> e = it.next();
            PendingEntry entry = e.getValue();
            if (entry.completedAt > 0 && now - entry.completedAt > COMPLETED_TTL_MS)
            {
                it.remove();
            }
            else if (entry.completedAt == 0 && now - entry.startedAt > ABANDONED_TTL_MS)
            {
                // Cancel and evict abandoned entries to free worker threads
                if (entry.future != null && !entry.future.isDone())
                {
                    entry.future.cancel(true);
                }
                it.remove();
            }
        }
    }

    /**
     * Computes a stable runKey from canonical request parameters.
     */
    public static String computeRunKey(String projectName, String objectFqn, String categoriesCsv,
        boolean skipBsl, boolean bslOnly, int limit, boolean deep)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("p=").append(orEmpty(projectName)) //$NON-NLS-1$
            .append("|o=").append(orEmpty(objectFqn)) //$NON-NLS-1$
            .append("|c=").append(orEmpty(categoriesCsv)) //$NON-NLS-1$
            .append("|sb=").append(skipBsl) //$NON-NLS-1$
            .append("|bo=").append(bslOnly) //$NON-NLS-1$
            .append("|l=").append(limit) //$NON-NLS-1$
            .append("|d=").append(deep); //$NON-NLS-1$
        return sha256(sb.toString());
    }

    private static String orEmpty(String s)
    {
        return s == null ? "" : s; //$NON-NLS-1$
    }

    private static String sha256(String input)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest)
            {
                hex.append(String.format("%02x", b & 0xFF)); //$NON-NLS-1$
            }
            return hex.substring(0, 16); // 64-bit prefix is plenty for our scale
        }
        catch (NoSuchAlgorithmException e)
        {
            // Should never happen on standard JDK
            return Integer.toHexString(input.hashCode());
        }
    }

    /**
     * Per-runKey state for the registry.
     */
    public static final class PendingEntry
    {
        public final String runKey;
        public final long startedAt = System.currentTimeMillis();
        public CompletableFuture<String> future;
        /** Cached result once the future completes. */
        public volatile String cachedResult;
        public volatile long completedAt;

        PendingEntry(String runKey)
        {
            this.runKey = runKey;
        }

        /**
         * Waits up to the given milliseconds for the future to complete.
         * Returns the cached result when ready, or {@code null} on timeout.
         */
        public String await(long timeoutMs)
        {
            if (cachedResult != null)
            {
                return cachedResult;
            }
            try
            {
                return future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            catch (java.util.concurrent.TimeoutException timeout)
            {
                return null;
            }
            catch (Exception e)
            {
                return "Error: " + e.getMessage(); //$NON-NLS-1$
            }
        }

        public boolean isDone()
        {
            return cachedResult != null || (future != null && future.isDone());
        }

        public long elapsedMs()
        {
            long end = completedAt > 0 ? completedAt : System.currentTimeMillis();
            return end - startedAt;
        }
    }
}
