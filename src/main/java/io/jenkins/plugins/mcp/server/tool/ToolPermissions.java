/*
 *
 * The MIT License
 *
 * Copyright (c) 2026, Olivier Lamy
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */

package io.jenkins.plugins.mcp.server.tool;

import hudson.security.ACL;
import hudson.security.Permission;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import jenkins.model.Jenkins;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

/** Turns {@code @Tool(permissions = ...)} ids into {@link Permission}s and checks them against a caller. */
public final class ToolPermissions {

    private ToolPermissions() {}

    /**
     * Resolves permission ids to {@link Permission}s. An unknown id throws right away, so a typo in a
     * {@code @Tool} is caught at startup instead of quietly letting everyone through.
     */
    public static List<Permission> resolve(@Nullable String[] ids) {
        if (ids == null || ids.length == 0) {
            return List.of();
        }
        List<Permission> permissions = new ArrayList<>(ids.length);
        for (String id : ids) {
            Permission permission = Permission.fromId(id);
            if (permission == null) {
                throw new IllegalStateException("Unknown Jenkins permission id in @Tool(permissions): " + id);
            }
            permissions.add(permission);
        }
        return permissions;
    }

    /**
     * Whether {@code auth} may use a tool requiring {@code required}. No requirement, or security off,
     * means yes; otherwise the user needs at least one of them. Checked against the Jenkins root, so
     * overall permissions only - not item-level ones.
     */
    public static boolean isAllowed(@Nullable Authentication auth, Collection<Permission> required) {
        if (required.isEmpty()) {
            return true;
        }
        Jenkins jenkins = Jenkins.get();
        if (!jenkins.isUseSecurity()) {
            return true;
        }
        if (auth == null) {
            return false;
        }
        ACL acl = jenkins.getACL();
        return required.stream().anyMatch(permission -> acl.hasPermission2(auth, permission));
    }
}
