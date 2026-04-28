/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.common.util.EList;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Two safety checks reused by {@code edit_metadata}, {@code add_metadata_attribute}
 * and {@code write_module_source}:
 * <ol>
 *   <li>{@link #checkStandardAttributeConflict(MdObject, String)} - rejects
 *       attribute names that collide with platform-standard attributes
 *       (Date / Number / Posted / Code / Description / Owner / etc.) before
 *       the object becomes invalid in EDT.</li>
 *   <li>{@link #checkSupplierLock(MdObject)} - reports "supplier-locked"
 *       state by reading whatever support-mode getter the EDT runtime
 *       exposes (different versions name it differently). When no API is
 *       reachable, the guard returns {@code null} so callers do not block
 *       on a missing probe.</li>
 * </ol>
 */
public final class MetadataGuards
{
    /**
     * Hard-coded fallback list of platform-standard attribute names we never
     * allow user-defined attributes to shadow. Used when the runtime cannot
     * surface {@code MdObject.getStandardAttributes()}.
     * Names are stored lowercased; both English and Russian variants are
     * matched via separate sets.
     */
    private static final Set<String> EN_STANDARD = new HashSet<>(Arrays.asList(
        "ref", "deletionmark", "predefined", "predefineddataname", "ismarked", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "code", "description", "owner", "parent", "isfolder", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "date", "number", "posted", "deletion", "moment", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "linenumber", "recorder", "period", "active", "recordtype" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    ));

    private static final Set<String> RU_STANDARD = new HashSet<>(Arrays.asList(
        "ссылка", "пометкаудаления", "предопределенный", "имяпредопределенныхданных", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "код", "наименование", "владелец", "родитель", "этогруппа", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "дата", "номер", "проведен", "момент", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "номерстроки", "регистратор", "период", "активность", "видзаписи" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    ));

    private MetadataGuards()
    {
        // utility class
    }

    /**
     * Result of a guard check.
     * {@code error == null} -> the operation may proceed.
     */
    public static final class Verdict
    {
        public boolean blocked;
        public String error;
        public String hint;
        public String discoveredApi; // when applicable
        public ErrorTag tag; // structured tag surfaced into the JSON response

        public static Verdict pass()
        {
            return new Verdict();
        }

        public static Verdict block(String error, String hint)
        {
            Verdict v = new Verdict();
            v.blocked = true;
            v.error = error;
            v.hint = hint;
            return v;
        }

        public static Verdict block(String error, String hint, ErrorTag tag)
        {
            Verdict v = block(error, hint);
            v.tag = tag;
            return v;
        }
    }

    /**
     * Machine-readable tag attached to a {@link Verdict}. Surfaces as a
     * top-level field on the tool's JSON response so that AI agents can
     * branch on it (e.g. {@code response.standardAttributeConflict != null}).
     * <p>
     * Standard names:
     * <ul>
     *   <li>{@code supportLock} - object on vendor support, editing not allowed</li>
     *   <li>{@code standardAttributeConflict} - candidate name shadows a standard one</li>
     *   <li>{@code alreadyExists} - target child already exists at the destination</li>
     *   <li>{@code notFound} - target child does not exist</li>
     *   <li>{@code dryRunNotSupported} - operation lacks a dryRun preview path</li>
     * </ul>
     */
    public static final class ErrorTag
    {
        public final String name;
        public final Map<String, Object> data;

        public ErrorTag(String name, Map<String, Object> data)
        {
            this.name = name;
            this.data = data != null ? data : new LinkedHashMap<>();
        }

        public ErrorTag(String name)
        {
            this(name, new LinkedHashMap<>());
        }

        public ErrorTag put(String key, Object value)
        {
            this.data.put(key, value);
            return this;
        }
    }

    /**
     * Sentinel exception used by helpers to abort a BM transaction with a
     * structured {@link Verdict}. Callers (helpers + dispatchers) catch this
     * to surface {@code verdict.tag} into the response.
     */
    public static final class BlockedGuardException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
        public final Verdict verdict;

        public BlockedGuardException(Verdict v)
        {
            super(v != null && v.error != null ? v.error : "blocked"); //$NON-NLS-1$
            this.verdict = v != null ? v : Verdict.pass();
        }

        /**
         * Walk the cause chain looking for a {@code BlockedGuardException}.
         * BM's {@code IBmModel.execute} can wrap our throwable in an
         * intermediate {@link RuntimeException}; this helper unwraps it.
         */
        public static BlockedGuardException unwrap(Throwable t)
        {
            Throwable cur = t;
            while (cur != null)
            {
                if (cur instanceof BlockedGuardException)
                {
                    return (BlockedGuardException) cur;
                }
                cur = cur.getCause();
            }
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Standard attribute conflict
    // -----------------------------------------------------------------------

    /**
     * Returns a {@link Verdict#block(String, String)} when the candidate
     * attribute name collides with a standard one on the given owner.
     * Comparison is case-insensitive and accepts both English and Russian
     * variants ("Date" / "Дата" / "date" / "ДАТА").
     */
    @SuppressWarnings("unchecked")
    public static Verdict checkStandardAttributeConflict(MdObject owner, String candidate)
    {
        if (owner == null || candidate == null || candidate.isEmpty())
        {
            return Verdict.pass();
        }
        String norm = candidate.trim().toLowerCase();

        // Live API path: MdObject.getStandardAttributes() returns a list of
        // StandardAttribute, each carrying a getName(). When the runtime
        // exposes it, we use the actual list - this captures
        // configuration-specific tweaks (e.g. UseStandardCommands hides some).
        try
        {
            Method m = owner.getClass().getMethod("getStandardAttributes"); //$NON-NLS-1$
            Object v = m.invoke(owner);
            if (v instanceof EList)
            {
                EList<? extends MdObject> list = (EList<? extends MdObject>) v;
                for (MdObject sa : list)
                {
                    String saName = sa.getName();
                    if (saName != null && saName.equalsIgnoreCase(norm))
                    {
                        ErrorTag tag = new ErrorTag("standardAttributeConflict") //$NON-NLS-1$
                            .put("name", candidate) //$NON-NLS-1$
                            .put("conflictsWith", saName) //$NON-NLS-1$
                            .put("ownerType", owner.eClass().getName()) //$NON-NLS-1$
                            .put("source", "live"); //$NON-NLS-1$ //$NON-NLS-2$
                        return Verdict.block(
                            "Name '" + candidate + "' clashes with the standard attribute '" //$NON-NLS-1$ //$NON-NLS-2$
                                + saName + "'", //$NON-NLS-1$
                            "Pick a different name. The standard attribute is " //$NON-NLS-1$
                                + "controlled by the parent object's properties.", //$NON-NLS-1$
                            tag);
                    }
                }
            }
        }
        catch (NoSuchMethodException nsme)
        {
            // type does not expose standard attributes - fall through to fallback list
        }
        catch (Exception e)
        {
            Activator.logWarning("checkStandardAttributeConflict reflection failed: " //$NON-NLS-1$
                + e.getMessage());
        }

        // Fallback: hard-coded English + Russian standard names
        if (EN_STANDARD.contains(norm) || RU_STANDARD.contains(norm))
        {
            ErrorTag tag = new ErrorTag("standardAttributeConflict") //$NON-NLS-1$
                .put("name", candidate) //$NON-NLS-1$
                .put("conflictsWith", norm) //$NON-NLS-1$
                .put("ownerType", owner.eClass().getName()) //$NON-NLS-1$
                .put("source", "fallback"); //$NON-NLS-1$ //$NON-NLS-2$
            return Verdict.block(
                "Name '" + candidate + "' matches a known platform-standard attribute", //$NON-NLS-1$ //$NON-NLS-2$
                "Use a different name. Standard attributes are managed by the platform " //$NON-NLS-1$
                    + "and cannot be shadowed by user-defined ones.", //$NON-NLS-1$
                tag);
        }
        return Verdict.pass();
    }

    // -----------------------------------------------------------------------
    // Supplier lock
    // -----------------------------------------------------------------------

    /**
     * Returns a {@link Verdict#block(String, String)} when the object is
     * locked by a supplier configuration ("On vendor support" with
     * "Editing not allowed"). Returns a pass with {@code discoveredApi}
     * filled when the support-mode API exists but the object is editable;
     * returns a plain pass when no API is reachable (best-effort).
     */
    public static Verdict checkSupplierLock(MdObject owner)
    {
        Verdict v = new Verdict();
        if (owner == null)
        {
            return v;
        }

        String resolved = null;
        Object mode = null;

        // EDT exposes the support mode under various names depending on
        // version. Try the common ones in order.
        String[] modeGetters = {
            "getUserSupportMode", "getSupportMode", "getSupport", "isOnSupport" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        };
        for (String getter : modeGetters)
        {
            try
            {
                Method m = owner.getClass().getMethod(getter);
                Object value = m.invoke(owner);
                if (value != null)
                {
                    resolved = getter;
                    mode = value;
                    break;
                }
            }
            catch (NoSuchMethodException ignored)
            {
                // try next
            }
            catch (Exception ignored)
            {
                // best-effort
            }
        }

        if (mode == null)
        {
            // No support API in this EDT runtime - we cannot block.
            return v;
        }
        v.discoveredApi = resolved;

        String modeStr = mode.toString();
        // Heuristic: any string mentioning "NotAllowed" or "Запрещено" or
        // ending with "_DISABLED" is a hard block. EDT enums:
        // CHANGES_NOT_ALLOWED / EDITING_NOT_ALLOWED / DENIED.
        String upper = modeStr.toUpperCase();
        if (upper.contains("NOT_ALLOWED") //$NON-NLS-1$
            || upper.contains("DENIED") //$NON-NLS-1$
            || upper.contains("DISABLED")) //$NON-NLS-1$
        {
            v.blocked = true;
            v.error = "Object '" + owner.getName() //$NON-NLS-1$
                + "' is on vendor support and editing is not allowed (mode=" //$NON-NLS-1$
                + modeStr + ")"; //$NON-NLS-1$
            v.hint = "Either enable editing in EDT (right-click -> Support -> " //$NON-NLS-1$
                + "Enable change), or work via a configuration extension " //$NON-NLS-1$
                + "(adoptObject + extension-side operations)."; //$NON-NLS-1$
            v.tag = new ErrorTag("supportLock") //$NON-NLS-1$
                .put("target", owner.getName()) //$NON-NLS-1$
                .put("ownerType", owner.eClass().getName()) //$NON-NLS-1$
                .put("userSupportMode", modeStr) //$NON-NLS-1$
                .put("discoveredApi", resolved) //$NON-NLS-1$
                .put("hint", v.hint); //$NON-NLS-1$
        }
        return v;
    }

    /**
     * Returns the list of standard attribute names matching a typical
     * Catalog/Document/Register profile. Used by `edit_metadata help` for
     * the "types" topic.
     */
    public static List<String> commonStandardAttributeNames()
    {
        return List.of("Ref", "DeletionMark", "Predefined", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Code", "Description", "Owner", "Parent", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "Date", "Number", "Posted", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "LineNumber", "Recorder", "Period"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
