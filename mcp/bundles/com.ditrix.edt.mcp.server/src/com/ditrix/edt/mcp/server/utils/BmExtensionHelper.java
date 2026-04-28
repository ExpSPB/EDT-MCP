/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Configuration-extension borrowing operations for {@code extension_workshop}.
 * <p>
 * <b>1.37 status:</b> probes the EDT adopt service across known candidate
 * packages and exposes a single best-effort entry point
 * {@link #attemptBorrow(IProject, String, String, String)} that returns a
 * structured {@link BorrowResult}. When the service or its API contract is
 * not reachable the result carries {@code adoptServiceNotFound=true} with a
 * GUI workaround hint - callers (the tool dispatcher) surface this as a
 * structured response tag so AI agents can branch on it.
 */
public final class BmExtensionHelper
{
    private static final String[] CANDIDATE_PACKAGES = {
        "com._1c.g5.v8.dt.md.extension.adopt.IMdAdoptObjectsService", //$NON-NLS-1$
        "com._1c.g5.v8.dt.metadata.extension.IMdAdoptObjectsService", //$NON-NLS-1$
        "com._1c.g5.v8.dt.md.extension.IMdAdoptObjectsService", //$NON-NLS-1$
        "com._1c.g5.v8.dt.md.adopt.IMdAdoptObjectsService" //$NON-NLS-1$
    };

    private static volatile String cachedClassName;
    private static volatile Boolean cachedProbed;

    private BmExtensionHelper()
    {
        // utility class
    }

    public static String resolvedAdoptServiceClass()
    {
        if (cachedProbed != null)
        {
            return cachedClassName;
        }
        synchronized (BmExtensionHelper.class)
        {
            if (cachedProbed != null)
            {
                return cachedClassName;
            }
            for (String candidate : CANDIDATE_PACKAGES)
            {
                try
                {
                    Class.forName(candidate);
                    cachedClassName = candidate;
                    break;
                }
                catch (ClassNotFoundException ignored)
                {
                    // try next
                }
            }
            cachedProbed = Boolean.TRUE;
            if (cachedClassName == null)
            {
                Activator.logWarning(
                    "BmExtensionHelper: adopt service not found in any candidate package"); //$NON-NLS-1$
            }
        }
        return cachedClassName;
    }

    public static boolean isAvailable()
    {
        return resolvedAdoptServiceClass() != null;
    }

    public static String deferredMessage(String operation)
    {
        String resolved = resolvedAdoptServiceClass();
        return "Extension operation '" + operation //$NON-NLS-1$
            + "' did not complete. " //$NON-NLS-1$
            + (resolved != null
                ? "Adopt service discovered: " + resolved //$NON-NLS-1$
                    + " - the API contract is best-effort in 1.37 and may need an update." //$NON-NLS-1$
                : "Adopt service NOT reachable in this EDT version. " //$NON-NLS-1$
                    + "Use EDT GUI: right-click base object - Borrow into extension."); //$NON-NLS-1$
    }

    /**
     * Outcome of a borrow attempt.
     */
    public static final class BorrowResult
    {
        public boolean ok;
        public boolean adoptServiceNotFound;
        public boolean alreadyBorrowed;
        public String error;
        public String discoveredApi;
        public Map<String, Object> tags = new LinkedHashMap<>();
    }

    /**
     * Best-effort borrow of {@code targetFqn} from {@code baseProjectName}
     * into the {@code extension} project. Resolves the adopt service via
     * OSGi {@link BundleContext} and invokes the discovered method through
     * reflection.
     * <p>
     * On success returns {@code BorrowResult.ok = true} and populates
     * {@code tags.borrowed} with the FQN that was actually borrowed.
     * <p>
     * On failure populates either {@code adoptServiceNotFound=true} (probe
     * failed) or {@code tags.adoptInvocationFailed} (probe found service
     * but invocation threw).
     */
    public static BorrowResult attemptBorrow(IProject extension, String baseProjectName,
        String targetFqn, String childKind)
    {
        BorrowResult r = new BorrowResult();
        r.discoveredApi = resolvedAdoptServiceClass();
        if (r.discoveredApi == null)
        {
            r.adoptServiceNotFound = true;
            r.error = "Adopt service not available in this EDT runtime"; //$NON-NLS-1$
            populateNotFoundTag(r, "borrow", targetFqn); //$NON-NLS-1$
            return r;
        }
        BundleContext bc = FrameworkUtil.getBundle(BmExtensionHelper.class).getBundleContext();
        if (bc == null)
        {
            r.adoptServiceNotFound = true;
            r.error = "BundleContext not available"; //$NON-NLS-1$
            populateNotFoundTag(r, "borrow", targetFqn); //$NON-NLS-1$
            return r;
        }
        ServiceReference<?> ref = bc.getServiceReference(r.discoveredApi);
        if (ref == null)
        {
            r.adoptServiceNotFound = true;
            r.error = "Adopt service registered class not exposed via OSGi"; //$NON-NLS-1$
            populateNotFoundTag(r, "borrow", targetFqn); //$NON-NLS-1$
            return r;
        }
        Object service = bc.getService(ref);
        if (service == null)
        {
            r.adoptServiceNotFound = true;
            r.error = "OSGi service instance is null"; //$NON-NLS-1$
            populateNotFoundTag(r, "borrow", targetFqn); //$NON-NLS-1$
            bc.ungetService(ref);
            return r;
        }
        try
        {
            Method adoptMethod = findAdoptMethod(service.getClass(), childKind);
            if (adoptMethod == null)
            {
                Map<String, Object> tag = new LinkedHashMap<>();
                tag.put("targetFqn", targetFqn); //$NON-NLS-1$
                tag.put("baseProject", baseProjectName); //$NON-NLS-1$
                tag.put("discoveredApi", r.discoveredApi); //$NON-NLS-1$
                tag.put("hint", //$NON-NLS-1$
                    "Service found but no `adopt` / `borrow` method recognised. " //$NON-NLS-1$
                        + "Use EDT GUI as a workaround."); //$NON-NLS-1$
                r.tags.put("adoptInvocationFailed", tag); //$NON-NLS-1$
                r.error = "Adopt method not found on service"; //$NON-NLS-1$
                return r;
            }
            // Best-effort invocation: most adopt APIs accept (IProject, String) for
            // (extension, base FQN). Wider variants (project, project, fqn) are
            // tried by reflection on a per-method basis below.
            try
            {
                Object result;
                Class<?>[] paramTypes = adoptMethod.getParameterTypes();
                if (paramTypes.length == 2 && paramTypes[0].equals(IProject.class)
                    && paramTypes[1].equals(String.class))
                {
                    result = adoptMethod.invoke(service, extension, targetFqn);
                }
                else
                {
                    Map<String, Object> tag = new LinkedHashMap<>();
                    tag.put("targetFqn", targetFqn); //$NON-NLS-1$
                    tag.put("methodSignature", adoptMethod.toString()); //$NON-NLS-1$
                    tag.put("hint", //$NON-NLS-1$
                        "Adopt service method has an unexpected signature. " //$NON-NLS-1$
                            + "1.37 supports (IProject, String). Update to bridge."); //$NON-NLS-1$
                    r.tags.put("adoptInvocationFailed", tag); //$NON-NLS-1$
                    r.error = "Unsupported adopt method signature: " + adoptMethod; //$NON-NLS-1$
                    return r;
                }
                Map<String, Object> okTag = new LinkedHashMap<>();
                okTag.put("targetFqn", targetFqn); //$NON-NLS-1$
                okTag.put("baseProject", baseProjectName); //$NON-NLS-1$
                if (result != null)
                {
                    okTag.put("returned", result.toString()); //$NON-NLS-1$
                }
                r.tags.put("borrowed", okTag); //$NON-NLS-1$
                r.ok = true;
                return r;
            }
            catch (Exception invokeEx)
            {
                Throwable cause = invokeEx.getCause() != null ? invokeEx.getCause() : invokeEx;
                String msg = cause.getMessage() != null ? cause.getMessage()
                    : cause.getClass().getSimpleName();
                if (msg != null && msg.toLowerCase().contains("already")) //$NON-NLS-1$
                {
                    r.alreadyBorrowed = true;
                    Map<String, Object> tag = new LinkedHashMap<>();
                    tag.put("targetFqn", targetFqn); //$NON-NLS-1$
                    r.tags.put("alreadyBorrowed", tag); //$NON-NLS-1$
                    r.ok = true;
                    return r;
                }
                Map<String, Object> tag = new LinkedHashMap<>();
                tag.put("targetFqn", targetFqn); //$NON-NLS-1$
                tag.put("error", msg); //$NON-NLS-1$
                r.tags.put("adoptInvocationFailed", tag); //$NON-NLS-1$
                r.error = "Adopt invocation failed: " + msg; //$NON-NLS-1$
                return r;
            }
        }
        finally
        {
            try
            {
                bc.ungetService(ref);
            }
            catch (Throwable ignored)
            {
                // best-effort
            }
        }
    }

    private static Method findAdoptMethod(Class<?> serviceClass, String childKind)
    {
        // 1) named match: adopt + childKind (e.g. "adoptForm", "adoptChild")
        if (childKind != null && !childKind.isEmpty())
        {
            String capitalized = Character.toUpperCase(childKind.charAt(0)) + childKind.substring(1);
            Method m = findMethodIgnoringCase(serviceClass, "adopt" + capitalized); //$NON-NLS-1$
            if (m != null)
            {
                return m;
            }
            m = findMethodIgnoringCase(serviceClass, "borrow" + capitalized); //$NON-NLS-1$
            if (m != null)
            {
                return m;
            }
        }
        // 2) generic adopt / borrow
        Method m = findMethodIgnoringCase(serviceClass, "adopt"); //$NON-NLS-1$
        if (m != null)
        {
            return m;
        }
        return findMethodIgnoringCase(serviceClass, "borrow"); //$NON-NLS-1$
    }

    private static Method findMethodIgnoringCase(Class<?> cls, String prefix)
    {
        for (Method m : cls.getMethods())
        {
            if (m.getName().equalsIgnoreCase(prefix))
            {
                return m;
            }
        }
        // 2-arg public method whose name starts with the prefix
        for (Method m : cls.getMethods())
        {
            if (m.getName().toLowerCase().startsWith(prefix.toLowerCase())
                && m.getParameterCount() == 2)
            {
                return m;
            }
        }
        return null;
    }

    private static void populateNotFoundTag(BorrowResult r, String operation, String targetFqn)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", operation); //$NON-NLS-1$
        data.put("targetFqn", targetFqn); //$NON-NLS-1$
        data.put("hint", //$NON-NLS-1$
            "EDT adopt service not reachable. Workaround: open base + extension in EDT, " //$NON-NLS-1$
                + "right-click the base object - Borrow into extension."); //$NON-NLS-1$
        r.tags.put("adoptServiceNotFound", data); //$NON-NLS-1$
    }
}
