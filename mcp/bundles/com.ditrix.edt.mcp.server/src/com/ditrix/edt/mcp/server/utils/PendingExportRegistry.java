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
 * 1.41: Async registry for {@code export_object} long-running .epf / .erf
 * builds.
 * <p>
 * Independent class from {@link PendingReferencesRegistry} (avoids cross-phase
 * coupling). Same structure: SHA-prefix runKey, completed/abandoned TTL,
 * bounded executor 2/8/20 with {@link ThreadPoolExecutor.CallerRunsPolicy}.
 *
 * <p>Lifecycle mirrors the references registry:
 * <ol>
 *   <li>{@link #getOrStart} - returns or creates the entry, dispatching the
 *       export work on a worker thread.</li>
 *   <li>{@link PendingEntry#await(long)} - blocks up to the soft timeout.</li>
 *   <li>If completed within the window: caller returns the result and
 *       {@link #remove}s the entry.</li>
 *   <li>If timeout elapses: caller returns Pending JSON with the runKey;
 *       entry remains for subsequent retries.</li>
 *   <li>{@link #pruneExpired} evicts completed-not-retrieved entries (5 min)
 *       and abandoned entries (30 min).</li>
 * </ol>
 */
public final class PendingExportRegistry
{
    /** TTL for completed entries that were never retrieved. 5 minutes. */
    private static final long COMPLETED_TTL_MS = 5 * 60 * 1000L;

    /** TTL for never-completed entries (runaway). 30 minutes. */
    private static final long ABANDONED_TTL_MS = 30 * 60 * 1000L;

    private static final PendingExportRegistry INSTANCE = new PendingExportRegistry();

    private final ConcurrentHashMap<String, PendingEntry> entries = new ConcurrentHashMap<>();

    private final ThreadFactory threadFactory = new ThreadFactory()
    {
        private final AtomicLong counter = new AtomicLong(0);

        @Override
        public Thread newThread(Runnable r)
        {
            Thread t = new Thread(r, "export-object-async-" + counter.incrementAndGet()); //$NON-NLS-1$
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        }
    };

    /**
     * 1.41: bounded executor matching {@link PendingReferencesRegistry}
     * (corePoolSize=2, maxPoolSize=8, queue=20) with
     * {@link ThreadPoolExecutor.CallerRunsPolicy}. Independent executor
     * for export work to keep references and exports isolated under
     * load.
     *
     * <p>Note: CallerRunsPolicy blocks the calling MCP HTTP-handler thread
     * (from McpServer.mainExecutor). Acceptable for the target audience of
     * 1-2 concurrent AI clients.
     */
    private final ExecutorService executor = new ThreadPoolExecutor(
        2, 8,
        60L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(20),
        threadFactory,
        new ThreadPoolExecutor.CallerRunsPolicy());

    private PendingExportRegistry()
    {
        // singleton
    }

    public static PendingExportRegistry getInstance()
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
                    Activator.logError("export_object async work failed for runKey=" + k, t); //$NON-NLS-1$
                    return "Error: " + (t.getMessage() != null ? t.getMessage() //$NON-NLS-1$
                        : t.getClass().getSimpleName());
                }
            }, executor);
            entry.future.whenComplete((result, throwable) ->
            {
                entry.cachedResult = result != null ? result : ("Error: " + throwable); //$NON-NLS-1$
                entry.completedAt = System.currentTimeMillis();
            });
            return entry;
        });
    }

    public PendingEntry get(String runKey)
    {
        return entries.get(runKey);
    }

    public void remove(String runKey)
    {
        entries.remove(runKey);
    }

    /**
     * Evicts entries past their TTL.
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
    public static String computeRunKey(String projectName, String objectName, String outputPath)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("p=").append(orEmpty(projectName)) //$NON-NLS-1$
            .append("|o=").append(orEmpty(objectName)) //$NON-NLS-1$
            .append("|out=").append(orEmpty(outputPath)); //$NON-NLS-1$
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
            return hex.substring(0, 16);
        }
        catch (NoSuchAlgorithmException e)
        {
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
        public volatile String cachedResult;
        public volatile long completedAt;

        PendingEntry(String runKey)
        {
            this.runKey = runKey;
        }

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
