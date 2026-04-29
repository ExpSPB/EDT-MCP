/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Direct DCS save to disk for extension projects, working around the
 * EDT 2025.2 bug where built-in serialization silently drops .dcs writes
 * after the first one. Mirrors the conventional pattern.
 * <p>
 * Pure reflection: optional Import-Package
 * {@code com._1c.g5.v8.dt.dcs.util}, {@code com._1c.g5.v8.dt.platform.version},
 * {@code com._1c.g5.v8.dt.core.filesystem}.
 */
public final class DcsExtensionExportHelper
{
    private static final String SVC_RUNTIME_VERSION_SUPPORT =
        "com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport"; //$NON-NLS-1$
    private static final String SVC_RESOURCE_LOOKUP =
        "com._1c.g5.v8.dt.core.platform.IResourceLookup"; //$NON-NLS-1$
    private static final String SVC_QN_FILE_PATH_CONVERTER =
        "com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter"; //$NON-NLS-1$
    private static final String CLS_DCS_V8_SERIALIZER =
        "com._1c.g5.v8.dt.dcs.util.DcsV8Serializer"; //$NON-NLS-1$
    private static final String CLS_VERSION =
        "com._1c.g5.v8.dt.platform.version.Version"; //$NON-NLS-1$
    private static final String CLS_DT_PROJECT =
        "com._1c.g5.v8.dt.core.platform.IDtProject"; //$NON-NLS-1$
    private static final String CLS_BM_TASK =
        "com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask"; //$NON-NLS-1$
    private static final String CLS_PREFERENCE_UTILS =
        "com._1c.g5.v8.dt.common.PreferenceUtils"; //$NON-NLS-1$

    private DcsExtensionExportHelper()
    {
        // utility
    }

    public static final class Result
    {
        public String schemaFqn;
        public boolean ok;
        public boolean notFound;
        public String filePath;
        public int bytesWritten;
        public long totalMs;
        public String error;
        public Object schemaEClass;
    }

    /**
     * Reads the schema from BM, serializes via DcsV8Serializer, writes to .dcs
     * on disk. Returns a Result with diagnostics.
     */
    public static Result exportSchemaToDisk(IBmModelManager manager, IProject project, String schemaFqn)
    {
        Result r = new Result();
        r.schemaFqn = schemaFqn;
        long t0 = System.currentTimeMillis();

        if (manager == null)
        {
            r.error = "IBmModelManager is null"; //$NON-NLS-1$
            return r;
        }
        if (project == null)
        {
            r.error = "project is null"; //$NON-NLS-1$
            return r;
        }
        if (schemaFqn == null || schemaFqn.isEmpty())
        {
            r.error = "schemaFqn is empty"; //$NON-NLS-1$
            return r;
        }

        BundleContext bc = org.osgi.framework.FrameworkUtil.getBundle(DcsExtensionExportHelper.class)
            .getBundleContext();
        if (bc == null)
        {
            r.error = "BundleContext is null - plugin stopping"; //$NON-NLS-1$
            return r;
        }

        ServiceReference rvsRef = bc.getServiceReference(SVC_RUNTIME_VERSION_SUPPORT);
        ServiceReference lookupRef = bc.getServiceReference(SVC_RESOURCE_LOOKUP);
        if (rvsRef == null)
        {
            r.error = "OSGi service not found: " + SVC_RUNTIME_VERSION_SUPPORT; //$NON-NLS-1$
            return r;
        }
        if (lookupRef == null)
        {
            r.error = "OSGi service not found: " + SVC_RESOURCE_LOOKUP; //$NON-NLS-1$
            return r;
        }

        Object rvs = bc.getService(rvsRef);
        Object lookup = bc.getService(lookupRef);
        try
        {
            if (rvs == null)
            {
                r.error = "IRuntimeVersionSupport service is null"; //$NON-NLS-1$
                return r;
            }
            if (lookup == null)
            {
                r.error = "IResourceLookup service is null"; //$NON-NLS-1$
                return r;
            }

            Object dtProject = resolveDtProject(manager, project);
            if (dtProject == null)
            {
                r.error = "Cannot resolve IDtProject for " + project.getName(); //$NON-NLS-1$
                return r;
            }
            Object version = resolveVersion(rvs, project);
            if (version == null)
            {
                r.error = "Cannot resolve runtime Version for project " + project.getName(); //$NON-NLS-1$
                return r;
            }

            byte[] bytes = readAndSerialize(manager, dtProject, schemaFqn, rvs, lookup, version,
                project, r);
            if (bytes == null)
            {
                return r;
            }
            r.bytesWritten = bytes.length;

            IFile dcsFile = resolveDcsFile(project, schemaFqn, bc, r.schemaEClass);
            if (dcsFile == null)
            {
                r.error = "Cannot resolve .dcs file path for FQN: " + schemaFqn; //$NON-NLS-1$
                return r;
            }
            r.filePath = dcsFile.getFullPath().toOSString();
            ensureParents(dcsFile);
            ByteArrayInputStream in = new ByteArrayInputStream(bytes);
            if (dcsFile.exists())
            {
                dcsFile.setContents((InputStream) in, true, true,
                    (IProgressMonitor) new NullProgressMonitor());
            }
            else
            {
                dcsFile.create((InputStream) in, true,
                    (IProgressMonitor) new NullProgressMonitor());
            }
            r.ok = true;
        }
        catch (Throwable t)
        {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            r.error = "exportSchemaToDisk failed: " + cause.getClass().getSimpleName() //$NON-NLS-1$
                + ": " + cause.getMessage(); //$NON-NLS-1$
            Activator.logWarning(r.error);
        }
        finally
        {
            try
            {
                bc.ungetService(rvsRef);
            }
            catch (Throwable ignored)
            {
            }
            try
            {
                bc.ungetService(lookupRef);
            }
            catch (Throwable ignored)
            {
            }
            r.totalMs = System.currentTimeMillis() - t0;
        }
        return r;
    }

    private static Object resolveDtProject(IBmModelManager manager, IProject project) throws Exception
    {
        Method m = manager.getClass().getMethod("getDtProject", String.class); //$NON-NLS-1$
        return m.invoke(manager, project.getName());
    }

    private static Object resolveVersion(Object rvs, IProject project)
    {
        try
        {
            Method m = rvs.getClass().getMethod("getRuntimeVersion", IProject.class); //$NON-NLS-1$
            Object v = m.invoke(rvs, project);
            if (v != null)
            {
                return v;
            }
        }
        catch (Throwable ignored)
        {
            // try fallback
        }
        try
        {
            Class<?> versionClass = Class.forName(CLS_VERSION);
            Object latest = versionClass.getField("LATEST").get(null); //$NON-NLS-1$
            Method m = rvs.getClass().getMethod("getRuntimeVersionOrDefault", //$NON-NLS-1$
                IProject.class, versionClass);
            Object v = m.invoke(rvs, project, latest);
            return v != null ? v : latest;
        }
        catch (Throwable t)
        {
            Activator.logWarning("resolveVersion fallback failed: " + t.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private static byte[] readAndSerialize(IBmModelManager manager, Object dtProject, String fqn,
        Object rvs, Object lookup, Object version, IProject project, Result r) throws Exception
    {
        ClassLoader cl = DcsExtensionExportHelper.class.getClassLoader();
        Class<?> taskIface = cl.loadClass(CLS_BM_TASK);
        Class<?> serializerClass = cl.loadClass(CLS_DCS_V8_SERIALIZER);
        Class<?> dtProjectIface = cl.loadClass(CLS_DT_PROJECT);
        Class<?> versionClass = cl.loadClass(CLS_VERSION);
        Class<?> lookupClass = cl.loadClass(SVC_RESOURCE_LOOKUP);
        Class<?> rvsClass = cl.loadClass(SVC_RUNTIME_VERSION_SUPPORT);
        Class<?> eobjectClass = cl.loadClass("org.eclipse.emf.ecore.EObject"); //$NON-NLS-1$

        Constructor<?> ctor = serializerClass.getConstructor(dtProjectIface, versionClass,
            lookupClass);
        Object serializer = ctor.newInstance(dtProject, version, lookup);

        // Optional: set runtimeVersionSupport field if exposed
        try
        {
            java.lang.reflect.Field rvsField = serializerClass.getDeclaredField("runtimeVersionSupport"); //$NON-NLS-1$
            rvsField.setAccessible(true);
            if (rvsField.get(serializer) == null)
            {
                rvsField.set(serializer, rvsClass.cast(rvs));
            }
        }
        catch (NoSuchFieldException ignored)
        {
            // newer bundle - not fatal
        }

        Method serializeXml = serializerClass.getMethod("serializeXML", //$NON-NLS-1$
            eobjectClass, OutputStream.class, String.class, dtProjectIface);
        String lineSep = resolveLineSeparator(project);

        Method executeReadOnly = findExecuteReadOnly(manager, dtProjectIface);
        if (executeReadOnly == null)
        {
            r.error = "executeReadOnlyTask(IDtProject,...) not found"; //$NON-NLS-1$
            return null;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
        boolean[] foundFlag = new boolean[1];
        String[] errFlag = new String[1];
        Object[] eclassFlag = new Object[1];

        Object proxy = Proxy.newProxyInstance(taskIface.getClassLoader(),
            new Class<?>[] { taskIface }, (p, m, args) -> {
                if (!"execute".equals(m.getName())) //$NON-NLS-1$
                {
                    return null;
                }
                Object tx = args[0];
                Object top = tx.getClass().getMethod("getTopObjectByFqn", String.class) //$NON-NLS-1$
                    .invoke(tx, fqn);
                if (top == null)
                {
                    errFlag[0] = "DCS schema top-object not found in BM by FQN: " + fqn; //$NON-NLS-1$
                    return null;
                }
                foundFlag[0] = true;
                try
                {
                    eclassFlag[0] = top.getClass().getMethod("eClass").invoke(top); //$NON-NLS-1$
                }
                catch (Throwable ignored)
                {
                }
                try
                {
                    serializeXml.invoke(serializer, top, baos, lineSep, dtProject);
                }
                catch (InvocationTargetException ite)
                {
                    Throwable cause = ite.getTargetException();
                    errFlag[0] = "serializeXML failed: " + cause.getClass().getSimpleName() //$NON-NLS-1$
                        + ": " + cause.getMessage(); //$NON-NLS-1$
                }
                return null;
            });
        executeReadOnly.invoke(manager, dtProject, proxy);

        if (!foundFlag[0])
        {
            r.notFound = true;
            r.error = errFlag[0] != null ? errFlag[0] : "DCS schema not found: " + fqn; //$NON-NLS-1$
            return null;
        }
        if (errFlag[0] != null)
        {
            r.error = errFlag[0];
            return null;
        }
        r.schemaEClass = eclassFlag[0];
        return baos.toByteArray();
    }

    private static Method findExecuteReadOnly(Object manager, Class<?> dtProjectIface)
    {
        for (Method m : manager.getClass().getMethods())
        {
            if (!"executeReadOnlyTask".equals(m.getName())) //$NON-NLS-1$
            {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 2
                && (params[0].isAssignableFrom(dtProjectIface) || dtProjectIface.isAssignableFrom(params[0])))
            {
                return m;
            }
        }
        return null;
    }

    private static String resolveLineSeparator(IProject project)
    {
        try
        {
            Class<?> prefUtils = Class.forName(CLS_PREFERENCE_UTILS);
            Method m = prefUtils.getMethod("getLineSeparator", IProject.class); //$NON-NLS-1$
            Object v = m.invoke(null, project);
            return v instanceof String ? (String) v : System.lineSeparator();
        }
        catch (Throwable ignored)
        {
            return System.lineSeparator();
        }
    }

    private static IFile resolveDcsFile(IProject project, String fqn, BundleContext bc, Object eClass)
    {
        if (eClass != null)
        {
            ServiceReference convRef = null;
            try
            {
                convRef = bc.getServiceReference(SVC_QN_FILE_PATH_CONVERTER);
                if (convRef != null)
                {
                    Object conv = bc.getService(convRef);
                    if (conv != null)
                    {
                        Class<?> eClassClass = Class.forName("org.eclipse.emf.ecore.EClass"); //$NON-NLS-1$
                        Method m = conv.getClass().getMethod("getFilePath", String.class, eClassClass); //$NON-NLS-1$
                        Object path = m.invoke(conv, fqn, eClass);
                        if (path instanceof IPath)
                        {
                            return project.getFile((IPath) path);
                        }
                    }
                }
            }
            catch (Throwable t)
            {
                Activator.logWarning("IQualifiedNameFilePathConverter failed for " + fqn //$NON-NLS-1$
                    + " - falling back: " + t.getMessage()); //$NON-NLS-1$
            }
            finally
            {
                if (convRef != null)
                {
                    try
                    {
                        bc.ungetService(convRef);
                    }
                    catch (Throwable ignored)
                    {
                    }
                }
            }
        }
        return resolveDcsFileLegacy(project, fqn);
    }

    private static IFile resolveDcsFileLegacy(IProject project, String fqn)
    {
        // FQN like: Report.Sales.Template.MainDCS.Template
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        if (parts.length != 5 || !"Template".equals(parts[2]) || !"Template".equals(parts[4])) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Activator.logWarning("Unexpected DCS schema FQN format: " + fqn); //$NON-NLS-1$
            return null;
        }
        String typeName = parts[0];
        String objectName = parts[1];
        String templateName = parts[3];
        String dirName = MetadataTypeUtils.getDirectoryName(typeName);
        if (dirName == null)
        {
            Activator.logWarning("Unknown md type for legacy DCS path: " + typeName); //$NON-NLS-1$
            return null;
        }
        IPath path = new Path("src/" + dirName + "/" + objectName //$NON-NLS-1$ //$NON-NLS-2$
            + "/Templates/" + templateName + "/Template.dcs"); //$NON-NLS-1$ //$NON-NLS-2$
        return project.getFile(path);
    }

    private static void ensureParents(IFile file) throws Exception
    {
        if (file.getParent() instanceof IFolder)
        {
            IFolder folder = (IFolder) file.getParent();
            if (!folder.exists())
            {
                createFolderRecursive(folder);
            }
        }
    }

    private static void createFolderRecursive(IFolder folder) throws Exception
    {
        if (folder == null || folder.exists())
        {
            return;
        }
        if (folder.getParent() instanceof IFolder)
        {
            createFolderRecursive((IFolder) folder.getParent());
        }
        folder.create(true, true, (IProgressMonitor) new NullProgressMonitor());
    }
}
