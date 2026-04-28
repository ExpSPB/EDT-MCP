/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.utils;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.FileEditorInput;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/**
 * Reusable helper for capturing screenshots from EDT visual editors (forms, print forms, etc.).
 * <p>
 * All UI-modifying methods must be called from the SWT UI thread (via {@code Display.syncExec}).
 */
public final class EditorScreenshotHelper
{
    private static final String FORM_EDITOR_CLASS = "com._1c.g5.v8.dt.form.ui.editor.FormEditor"; //$NON-NLS-1$
    private static final String FORM_EDITOR_ID = "com._1c.g5.v8.dt.form.ui.formEditor"; //$NON-NLS-1$
    private static final String FORM_MAIN_PAGE_ID = "editors.form.pages.main"; //$NON-NLS-1$
    private static final String WYSIWYG_VIEWER_FIELD = "wysiwygViewer"; //$NON-NLS-1$
    private static final String WYSIWYG_REPRESENTATION_FIELD = "wysiwygRepresentation"; //$NON-NLS-1$
    private static final String FORM_IMAGE_METHOD = "getFormImageData"; //$NON-NLS-1$
    private static final String GET_CONTROL_METHOD = "getControl"; //$NON-NLS-1$
    private static final String REFRESH_METHOD = "refresh"; //$NON-NLS-1$
    private static final int WYSIWYG_WAIT_RETRIES = 15;
    private static final int WYSIWYG_WAIT_INTERVAL_MS = 500;

    // Form model classes (loaded via reflection for activatePageInForm)
    private static final String FORM_CLASS = "com._1c.g5.v8.dt.form.model.Form"; //$NON-NLS-1$
    private static final String FORM_GROUP_CLASS = "com._1c.g5.v8.dt.form.model.FormGroup"; //$NON-NLS-1$
    private static final String FORM_ITEM_CONTAINER_CLASS = "com._1c.g5.v8.dt.form.model.FormItemContainer"; //$NON-NLS-1$
    private static final String NAMED_ELEMENT_CLASS = "com._1c.g5.v8.dt.mcore.NamedElement"; //$NON-NLS-1$
    private static final String PAGE_GROUP_EXT_INFO_CLASS = "com._1c.g5.v8.dt.form.model.PageGroupExtInfo"; //$NON-NLS-1$

    // WYSIWYG render classes accessed by simple name to avoid x-internal package imports
    private static final String TAB_CONTROL_SIMPLE_NAME = "TabControl"; //$NON-NLS-1$
    /** Substring expected in FQN of EDT's TabControl - guards against accidental simple name collisions. */
    private static final String TAB_CONTROL_PACKAGE_HINT = "com._1c.g5.v8.dt.form."; //$NON-NLS-1$
    private static final String OPEN_TAB_METHOD = "openTab"; //$NON-NLS-1$
    private static final String GET_RELATED_CONTROL_METHOD = "getRelatedControl"; //$NON-NLS-1$
    private static final String GET_PARENT_METHOD = "getParent"; //$NON-NLS-1$
    private static final int CONTROL_LOOKUP_RETRIES = 5;
    private static final int CONTROL_LOOKUP_INTERVAL_MS = 200;

    private EditorScreenshotHelper()
    {
        // Utility class
    }

    // ==================== Result container ====================

    /**
     * Result of a screenshot capture — either base64 PNG data or an error JSON string.
     */
    public static class CaptureResult
    {
        private final String base64Data;
        private final String error;

        private CaptureResult(String base64Data, String error)
        {
            this.base64Data = base64Data;
            this.error = error;
        }

        public static CaptureResult success(String base64)
        {
            return new CaptureResult(base64, null);
        }

        public static CaptureResult error(String errorJson)
        {
            return new CaptureResult(null, errorJson);
        }

        public boolean isSuccess()
        {
            return error == null;
        }

        public String getBase64Data()
        {
            return base64Data;
        }

        public String getError()
        {
            return error;
        }
    }

    // ==================== Native render mode ====================

    /**
     * Ensures that the native buffered render mode is enabled so that
     * {@code getFormImageData()} returns valid image data.
     * Should be called before opening a form editor.
     */
    public static void ensureBufferedNativeRenderMode()
    {
        final String nativeRenderServiceClass = "com._1c.g5.v8.dt.form.layout.service.NativeRenderService"; //$NON-NLS-1$
        final String bufferedFlagField = "NATIVE_FORM_BUFFERED_LAYOUT_RENDER"; //$NON-NLS-1$
        final String propertyName = "nativeFormBufferedLayoutRender"; //$NON-NLS-1$

        try
        {
            System.setProperty(propertyName, "true"); //$NON-NLS-1$

            Class<?> serviceClass = Class.forName(nativeRenderServiceClass);
            Method isNativeRenderMethod = serviceClass.getMethod("isNativeRender"); //$NON-NLS-1$
            Method isBufferedRenderMethod = serviceClass.getMethod("isBufferedRender"); //$NON-NLS-1$

            boolean nativeRender = (Boolean)isNativeRenderMethod.invoke(null);
            boolean bufferedBefore = (Boolean)isBufferedRenderMethod.invoke(null);

            if (nativeRender && !bufferedBefore)
            {
                try
                {
                    Field bufferedField = serviceClass.getDeclaredField(bufferedFlagField);
                    bufferedField.setAccessible(true);
                    bufferedField.setBoolean(null, true);
                }
                catch (Exception e)
                {
                    ReflectionUtils.forceStaticFinalBoolean(serviceClass, bufferedFlagField, true);
                }
            }

            boolean bufferedAfter = (Boolean)isBufferedRenderMethod.invoke(null);
            if (!bufferedAfter)
            {
                Activator.logWarning("Buffered native render is still disabled. " + //$NON-NLS-1$
                    "Restart EDT with VM option: -DnativeFormBufferedLayoutRender=true"); //$NON-NLS-1$
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Failed to ensure buffered native render mode: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    // ==================== Editor opening ====================

    /**
     * Opens a form file in the editor and activates the WYSIWYG (main) page.
     * Must be called on the UI thread.
     *
     * @param projectName EDT project name
     * @param formPath FQN path like "Catalog.Products.Forms.ItemForm" or "CommonForm.MyForm"
     * @return {@code null} on success, error JSON string on failure
     */
    public static String openAndActivateForm(String projectName, String formPath)
    {
        String relativePath = MetadataPathResolver.resolveFormFilePath(formPath);
        if (relativePath == null)
        {
            return ToolResult.error(
                "Cannot resolve form path: " + formPath + ". " + //$NON-NLS-1$ //$NON-NLS-2$
                "Expected format: 'MetadataType.ObjectName.Forms.FormName' " + //$NON-NLS-1$
                "or 'CommonForm.FormName'.").toJson(); //$NON-NLS-1$
        }

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }

        IFile formFile = project.getFile(new Path(relativePath));
        if (!formFile.exists())
        {
            return ToolResult.error(
                "Form file not found: " + relativePath + " in project " + projectName).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        IWorkbenchPage page = getWorkbenchPage();
        if (page == null)
        {
            return ToolResult.error("No active workbench page").toJson(); //$NON-NLS-1$
        }

        try
        {
            // Close existing editor so we apply current render mode
            IEditorPart existingEditor = page.findEditor(new FileEditorInput(formFile));
            if (existingEditor != null)
            {
                page.closeEditor(existingEditor, false);
            }

            IEditorPart editorPart = IDE.openEditor(page, formFile, FORM_EDITOR_ID, true);
            if (editorPart == null)
            {
                return ToolResult.error("Could not open form editor for: " + formPath).toJson(); //$NON-NLS-1$
            }

            activateFormMainPage(editorPart);
            return null; // success
        }
        catch (Exception e)
        {
            Activator.logError("Failed to open form editor for: " + formPath, e); //$NON-NLS-1$
            return ToolResult.error("Failed to open form editor: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Gets the active workbench page, trying all available windows.
     */
    public static IWorkbenchPage getWorkbenchPage()
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
        {
            IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
            if (windows.length > 0)
            {
                window = windows[0];
            }
        }
        if (window == null)
        {
            return null;
        }
        return window.getActivePage();
    }

    // ==================== WYSIWYG page detection ====================

    /**
     * Waits for the form editor WYSIWYG page to become available.
     * Processes UI events while waiting to allow the editor to initialize.
     * Must be called on the UI thread.
     *
     * @return the FormEditorPage, or {@code null} if not available after timeout
     */
    public static Object waitForFormEditorPage()
    {
        Display display = Display.getCurrent();
        for (int i = 0; i < WYSIWYG_WAIT_RETRIES; i++)
        {
            processEvents(display);

            try
            {
                Object page = getActiveFormEditorPage();
                if (page != null)
                {
                    Object viewer = ReflectionUtils.getFieldValue(page, WYSIWYG_VIEWER_FIELD);
                    if (viewer != null)
                    {
                        return page;
                    }
                }
            }
            catch (Exception e)
            {
                // Editor still initializing, keep waiting
            }

            sleep(WYSIWYG_WAIT_INTERVAL_MS);
            processEvents(display);
        }

        // Final attempt
        try
        {
            return getActiveFormEditorPage();
        }
        catch (Exception e)
        {
            Activator.logError("Failed to get form editor page after waiting", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Gets the active form editor page via the static FormEditor API.
     */
    public static Object getActiveFormEditorPage() throws Exception
    {
        Class<?> editorClass = Class.forName(FORM_EDITOR_CLASS);
        Method method = editorClass.getMethod("getActiveFormEditorPage"); //$NON-NLS-1$
        return method.invoke(null);
    }

    // ==================== Image capture ====================

    /**
     * Extracts the form image data from the WYSIWYG representation.
     * This is the primary (preferred) capture method using {@code getFormImageData()}.
     *
     * @param wysiwygViewer the WYSIWYG viewer instance
     * @return image data, or {@code null} if not available
     */
    public static ImageData extractFormImageData(Object wysiwygViewer) throws Exception
    {
        Object representation = ReflectionUtils.getFieldValue(wysiwygViewer, WYSIWYG_REPRESENTATION_FIELD);
        if (representation == null)
        {
            return null;
        }

        // Trigger rebuild to get up-to-date image
        try
        {
            Method rebuildMethod = representation.getClass().getDeclaredMethod("rebuild", boolean.class); //$NON-NLS-1$
            rebuildMethod.setAccessible(true);
            rebuildMethod.invoke(representation, true);

            Display display = Display.getCurrent();
            if (display != null)
            {
                for (int i = 0; i < 5; i++)
                {
                    processEvents(display);
                    sleep(200);
                }
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Could not call rebuild: " + e.getMessage()); //$NON-NLS-1$
        }

        // Get the image data
        try
        {
            Method method = representation.getClass().getDeclaredMethod(FORM_IMAGE_METHOD);
            method.setAccessible(true);
            ImageData data = (ImageData)method.invoke(representation);
            if (data != null && data.width > 0 && data.height > 0)
            {
                return data;
            }
        }
        catch (NoSuchMethodException e)
        {
            Activator.logWarning("Method " + FORM_IMAGE_METHOD + " not found"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return null;
    }

    /**
     * Fallback capture method: captures the WYSIWYG control image via {@code Control.print()}.
     *
     * @param wysiwygViewer the WYSIWYG viewer instance
     * @return image data, or {@code null} if the control is not available or has invalid bounds
     */
    public static ImageData captureControlImageData(Object wysiwygViewer) throws Exception
    {
        Control control = (Control)ReflectionUtils.invokeMethod(wysiwygViewer, GET_CONTROL_METHOD);
        if (control == null || control.isDisposed())
        {
            return null;
        }

        Rectangle bounds = control.getBounds();
        if (bounds.width <= 0 || bounds.height <= 0)
        {
            return null;
        }

        control.update();

        Image image = new Image(control.getDisplay(), bounds.width, bounds.height);
        GC gc = new GC(image);
        try
        {
            gc.setBackground(control.getDisplay().getSystemColor(SWT.COLOR_WHITE));
            gc.fillRectangle(0, 0, bounds.width, bounds.height);
            control.print(gc);
            return image.getImageData();
        }
        finally
        {
            gc.dispose();
            image.dispose();
        }
    }

    /**
     * Refreshes the WYSIWYG viewer and waits for it to complete.
     * Must be called on the UI thread.
     *
     * @param wysiwygViewer the WYSIWYG viewer instance
     */
    public static void refreshViewer(Object wysiwygViewer)
    {
        try
        {
            ReflectionUtils.invokeMethod(wysiwygViewer, REFRESH_METHOD);
            Display display = Display.getCurrent();
            if (display != null)
            {
                for (int i = 0; i < 3; i++)
                {
                    processEvents(display);
                    sleep(100);
                }
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Failed to refresh WYSIWYG viewer: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    // ==================== Encoding ====================

    /**
     * Encodes {@link ImageData} as a base64 PNG string.
     *
     * @param imageData the image data to encode
     * @return base64-encoded PNG string
     */
    public static String encodePng(ImageData imageData)
    {
        ImageLoader loader = new ImageLoader();
        loader.data = new ImageData[] { imageData };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        loader.save(output, SWT.IMAGE_PNG);
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }

    // ==================== Form page activation ====================

    /**
     * Activates a Page form element (FormGroup with PageGroupExtInfo) inside a Pages
     * container so that the WYSIWYG render shows it before the screenshot is taken.
     * <p>
     * Search is recursive across the whole form by element name. If multiple Page
     * elements share a name, the first one encountered (depth-first) is used.
     * <p>
     * Must be called on the UI thread, after the form editor is opened and its
     * WYSIWYG page has become available.
     *
     * @param editorPage the active FormEditorPage instance
     * @param pageName the name of the Page form element to activate
     * @return {@code null} on success, an error JSON string on failure
     */
    public static String activatePageInForm(Object editorPage, String pageName)
    {
        if (pageName == null || pageName.isEmpty())
        {
            return null;
        }
        try
        {
            Object viewer = ReflectionUtils.getFieldValue(editorPage, WYSIWYG_VIEWER_FIELD);
            if (viewer == null)
            {
                return ToolResult.error("WYSIWYG viewer not available for page activation").toJson(); //$NON-NLS-1$
            }
            Object representation = ReflectionUtils.getFieldValue(viewer, WYSIWYG_REPRESENTATION_FIELD);
            if (representation == null)
            {
                return ToolResult
                    .error("WYSIWYG representation not available for page activation").toJson(); //$NON-NLS-1$
            }

            Object form = resolveFormObject(editorPage, viewer, representation);
            if (form == null)
            {
                Activator.logWarning("Cannot resolve Form EMF model. Tried fields/methods on " //$NON-NLS-1$
                    + "representation/viewer/editor - none returned a Form instance. EDT API may have changed."); //$NON-NLS-1$
                return ToolResult.error("Cannot resolve Form model from editor for page activation").toJson(); //$NON-NLS-1$
            }

            Class<?> containerIface = Class.forName(FORM_ITEM_CONTAINER_CLASS);
            Class<?> namedIface = Class.forName(NAMED_ELEMENT_CLASS);
            Object pageItem = findFormItemByName(form, pageName, containerIface, namedIface);
            if (pageItem == null)
            {
                List<String> available = collectPageNames(form);
                String hint = available.isEmpty()
                    ? "form has no Page elements" //$NON-NLS-1$
                    : "available pages: " + available; //$NON-NLS-1$
                return ToolResult
                    .error("Page '" + pageName + "' not found in form (" + hint + ")").toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }

            Object control = lookupRelatedControl(representation, pageItem);
            if (control == null)
            {
                return ToolResult
                    .error("Page '" + pageName + "' is not rendered in WYSIWYG yet (control not found)") //$NON-NLS-1$ //$NON-NLS-2$
                    .toJson();
            }

            Object tabControl = findAncestorByClassSimpleName(control, TAB_CONTROL_SIMPLE_NAME, TAB_CONTROL_PACKAGE_HINT);
            if (tabControl == null)
            {
                return ToolResult.error(
                    "TabControl ancestor not found for page '" + pageName //$NON-NLS-1$
                        + "' (page may not be inside a Pages group)").toJson(); //$NON-NLS-1$
            }

            Method openTabMethod = findCompatibleMethod(representation.getClass(), OPEN_TAB_METHOD, tabControl);
            if (openTabMethod == null)
            {
                return ToolResult.error("openTab method not found on " //$NON-NLS-1$
                    + representation.getClass().getSimpleName()).toJson();
            }
            openTabMethod.setAccessible(true);
            openTabMethod.invoke(representation, tabControl);

            Display display = Display.getCurrent();
            for (int i = 0; i < 3; i++)
            {
                processEvents(display);
                sleep(100);
            }
            return null;
        }
        catch (Exception e)
        {
            Activator.logError("Failed to activate page '" + pageName + "' in form", e); //$NON-NLS-1$ //$NON-NLS-2$
            return ToolResult
                .error("Failed to activate page '" + pageName + "': " + e.getMessage()).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Resolves the {@code Form} EMF object reachable from the editor / viewer / representation.
     * Tries common field and method names, then scans for any field whose value is an instance of {@code Form}.
     */
    private static Object resolveFormObject(Object editorPage, Object viewer, Object representation)
    {
        Class<?> formIface;
        try
        {
            formIface = Class.forName(FORM_CLASS);
        }
        catch (ClassNotFoundException e)
        {
            return null;
        }

        String[] candidateFields = { "form", "formModel", "model", "rootForm" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String[] candidateMethods = { "getForm", "getModel", "getRootForm" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Object editor = null;
        try
        {
            editor = editorPage.getClass().getMethod("getEditor").invoke(editorPage); //$NON-NLS-1$
        }
        catch (Exception ignored)
        {
            // try field next
        }
        if (editor == null)
        {
            editor = safeGetField(editorPage, "editor"); //$NON-NLS-1$
        }

        // Order matters: representation is closest to the rendered form (single Form instance).
        // viewer/editor/editorPage are progressively further away. We do NOT scan all fields by
        // type - that risks returning a Form from a stale cache or a sibling editor.
        Object[] sources = { representation, viewer, editor, editorPage };
        for (Object source : sources)
        {
            if (source == null)
            {
                continue;
            }
            for (String fieldName : candidateFields)
            {
                Object value = safeGetField(source, fieldName);
                if (value != null && formIface.isInstance(value))
                {
                    return value;
                }
            }
            for (String methodName : candidateMethods)
            {
                Object value = safeInvokeNoArg(source, methodName);
                if (value != null && formIface.isInstance(value))
                {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * Recursively searches a {@code FormItemContainer} for an item with the given name.
     */
    private static Object findFormItemByName(Object container, String name, Class<?> containerIface,
        Class<?> namedIface) throws Exception
    {
        Object items = containerIface.getMethod("getItems").invoke(container); //$NON-NLS-1$
        int size = (Integer)items.getClass().getMethod("size").invoke(items); //$NON-NLS-1$
        for (int i = 0; i < size; i++)
        {
            Object item = items.getClass().getMethod("get", Integer.TYPE).invoke(items, i); //$NON-NLS-1$
            try
            {
                String itemName = (String)namedIface.getMethod("getName").invoke(item); //$NON-NLS-1$
                if (name.equals(itemName))
                {
                    return item;
                }
            }
            catch (Exception ignored)
            {
                // not all items are named
            }
            if (containerIface.isInstance(item))
            {
                Object found = findFormItemByName(item, name, containerIface, namedIface);
                if (found != null)
                {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Collects names of all Page form elements (FormGroup with PageGroupExtInfo) reachable from the form.
     */
    private static List<String> collectPageNames(Object form)
    {
        List<String> result = new ArrayList<>();
        try
        {
            Class<?> containerIface = Class.forName(FORM_ITEM_CONTAINER_CLASS);
            Class<?> namedIface = Class.forName(NAMED_ELEMENT_CLASS);
            Class<?> formGroupIface = Class.forName(FORM_GROUP_CLASS);
            Class<?> pageExtInfoIface;
            try
            {
                pageExtInfoIface = Class.forName(PAGE_GROUP_EXT_INFO_CLASS);
            }
            catch (ClassNotFoundException e)
            {
                pageExtInfoIface = null;
            }
            collectPageNamesRecursive(form, result, containerIface, namedIface, formGroupIface, pageExtInfoIface);
        }
        catch (Exception e)
        {
            Activator.logWarning("Failed to collect page names: " + e.getMessage()); //$NON-NLS-1$
        }
        return result;
    }

    private static void collectPageNamesRecursive(Object container, List<String> result, Class<?> containerIface,
        Class<?> namedIface, Class<?> formGroupIface, Class<?> pageExtInfoIface) throws Exception
    {
        Object items = containerIface.getMethod("getItems").invoke(container); //$NON-NLS-1$
        int size = (Integer)items.getClass().getMethod("size").invoke(items); //$NON-NLS-1$
        for (int i = 0; i < size; i++)
        {
            Object item = items.getClass().getMethod("get", Integer.TYPE).invoke(items, i); //$NON-NLS-1$
            if (formGroupIface.isInstance(item) && pageExtInfoIface != null)
            {
                try
                {
                    Object extInfo = formGroupIface.getMethod("getExtInfo").invoke(item); //$NON-NLS-1$
                    if (extInfo != null && pageExtInfoIface.isInstance(extInfo))
                    {
                        String name = (String)namedIface.getMethod("getName").invoke(item); //$NON-NLS-1$
                        if (name != null && !name.isEmpty())
                        {
                            result.add(name);
                        }
                    }
                }
                catch (Exception ignored)
                {
                    // skip items without proper accessors
                }
            }
            if (containerIface.isInstance(item))
            {
                collectPageNamesRecursive(item, result, containerIface, namedIface, formGroupIface, pageExtInfoIface);
            }
        }
    }

    /**
     * Calls {@code representation.getRelatedControl(pageItem)} and retries while the WYSIWYG is still building.
     */
    private static Object lookupRelatedControl(Object representation, Object pageItem) throws Exception
    {
        Display display = Display.getCurrent();
        Object lastResult = null;
        for (int attempt = 0; attempt < CONTROL_LOOKUP_RETRIES; attempt++)
        {
            lastResult = invokeWithCompatibleParam(representation, GET_RELATED_CONTROL_METHOD, pageItem);
            if (lastResult != null)
            {
                return lastResult;
            }
            processEvents(display);
            sleep(CONTROL_LOOKUP_INTERVAL_MS);
        }
        return lastResult;
    }

    /**
     * Walks parent chain via {@code getParent()} until a control whose class matches both
     * {@code simpleClassName} and contains {@code packageHint} in its FQN is found.
     * <p>
     * The package hint guards against simple-name collisions (other bundles in OSGi runtime
     * may have unrelated {@code TabControl} classes).
     */
    private static Object findAncestorByClassSimpleName(Object start, String simpleClassName, String packageHint)
    {
        Object current = start;
        int safety = 100;
        while (current != null && safety-- > 0)
        {
            Class<?> cls = current.getClass();
            if (simpleClassName.equals(cls.getSimpleName())
                && (packageHint == null || cls.getName().contains(packageHint)))
            {
                return current;
            }
            Object parent = safeInvokeNoArg(current, GET_PARENT_METHOD);
            if (parent == null || parent == current)
            {
                return null;
            }
            current = parent;
        }
        return null;
    }

    /**
     * Finds and invokes a single-parameter method whose parameter type is assignable from the argument's class.
     */
    private static Object invokeWithCompatibleParam(Object target, String methodName, Object arg) throws Exception
    {
        Method method = findCompatibleMethod(target.getClass(), methodName, arg);
        if (method == null)
        {
            return null;
        }
        method.setAccessible(true);
        return method.invoke(target, arg);
    }

    /**
     * Finds a single-parameter method by name whose parameter type accepts the given argument.
     * <p>
     * Among all matching overloads, returns the one with the most specific parameter type - that is,
     * the deepest in the class hierarchy of {@code arg}. This avoids dispatching to a generic
     * {@code openTab(Object)} when {@code openTab(TabControl)} also exists.
     */
    private static Method findCompatibleMethod(Class<?> targetClass, String methodName, Object arg)
    {
        Class<?> argType = (arg != null) ? arg.getClass() : null;
        Method best = null;
        Class<?> bestParamType = null;

        Class<?> type = targetClass;
        while (type != null)
        {
            for (Method m : type.getDeclaredMethods())
            {
                if (!methodName.equals(m.getName()) || m.getParameterCount() != 1)
                {
                    continue;
                }
                Class<?> paramType = m.getParameterTypes()[0];
                if (argType != null && !paramType.isAssignableFrom(argType))
                {
                    continue;
                }
                if (bestParamType == null || bestParamType.isAssignableFrom(paramType))
                {
                    best = m;
                    bestParamType = paramType;
                }
            }
            type = type.getSuperclass();
        }
        for (Method m : targetClass.getMethods())
        {
            if (!methodName.equals(m.getName()) || m.getParameterCount() != 1)
            {
                continue;
            }
            Class<?> paramType = m.getParameterTypes()[0];
            if (argType != null && !paramType.isAssignableFrom(argType))
            {
                continue;
            }
            if (bestParamType == null || bestParamType.isAssignableFrom(paramType))
            {
                best = m;
                bestParamType = paramType;
            }
        }
        return best;
    }

    private static Object safeGetField(Object target, String fieldName)
    {
        try
        {
            return ReflectionUtils.getFieldValue(target, fieldName);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static Object safeInvokeNoArg(Object target, String methodName)
    {
        Class<?> type = target.getClass();
        while (type != null)
        {
            try
            {
                Method m = type.getDeclaredMethod(methodName);
                m.setAccessible(true);
                return m.invoke(target);
            }
            catch (NoSuchMethodException e)
            {
                type = type.getSuperclass();
            }
            catch (Exception e)
            {
                return null;
            }
        }
        try
        {
            Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    // ==================== Internal helpers ====================

    /**
     * Activates the main (WYSIWYG) page of the form editor via reflection.
     */
    private static void activateFormMainPage(IEditorPart editorPart)
    {
        try
        {
            Class<?> editorClass = Class.forName(FORM_EDITOR_CLASS);
            if (!editorClass.isInstance(editorPart))
            {
                return;
            }

            Method setActivePageMethod =
                ReflectionUtils.findMethod(editorPart.getClass(), "setActivePage", String.class); //$NON-NLS-1$
            if (setActivePageMethod != null)
            {
                setActivePageMethod.setAccessible(true);
                setActivePageMethod.invoke(editorPart, FORM_MAIN_PAGE_ID);
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("Could not activate form main page: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Processes all pending SWT events.
     */
    public static void processEvents(Display display)
    {
        if (display != null)
        {
            while (display.readAndDispatch())
            {
                // drain event queue
            }
        }
    }

    /**
     * Sleeps with interrupt handling.
     */
    private static void sleep(int millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}
