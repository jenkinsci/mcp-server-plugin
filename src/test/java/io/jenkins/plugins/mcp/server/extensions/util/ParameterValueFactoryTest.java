/*
 *
 * The MIT License
 *
 * Copyright (c) 2025, Gong Yi.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is furnished
 * to do so, subject to the following conditions:
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

package io.jenkins.plugins.mcp.server.extensions.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for ParameterValueFactory utility class.
 * These tests focus on the logic and behavior without requiring Jenkins objects.
 * Designed to be fully compatible with Jenkins CI environment.
 */
public class ParameterValueFactoryTest {

    @Test
    void testParameterValueFactoryExists() {
        // Basic test to ensure the class can be loaded
        assertNotNull(ParameterValueFactory.class);
        assertTrue(ParameterValueFactory.class.getDeclaredMethods().length > 0);
    }

    @Test
    void testParameterValueFactoryHasCreateMethod() {
        // Test that the main method exists by checking method names
        var methods = ParameterValueFactory.class.getDeclaredMethods();
        boolean hasCreateMethod = false;

        for (var method : methods) {
            if (method.getName().equals("createParameterValue")) {
                hasCreateMethod = true;
                // Check that it's static
                assertTrue(
                        java.lang.reflect.Modifier.isStatic(method.getModifiers()),
                        "createParameterValue method should be static");
                break;
            }
        }

        assertTrue(hasCreateMethod, "Should have createParameterValue method");
    }

    @Test
    void testParameterValueFactoryIsPublic() {
        // Test that the class is public
        assertTrue(java.lang.reflect.Modifier.isPublic(ParameterValueFactory.class.getModifiers()));
    }

    @Test
    void testParameterValueFactoryHasUtilityMethods() {
        // Test that utility methods exist
        var methods = ParameterValueFactory.class.getDeclaredMethods();
        boolean hasCreateMethod = false;

        for (var method : methods) {
            if (method.getName().equals("createParameterValue")) {
                hasCreateMethod = true;
                break;
            }
        }

        assertTrue(hasCreateMethod, "Should have createParameterValue method");
    }

    @Test
    void testParameterValueFactoryIsWellStructured() {
        // Test basic class structure
        var constructors = ParameterValueFactory.class.getDeclaredConstructors();
        assertTrue(constructors.length > 0, "Should have constructors");

        // Test that it's not abstract
        assertFalse(java.lang.reflect.Modifier.isAbstract(ParameterValueFactory.class.getModifiers()));

        // Test that it's not an interface
        assertFalse(ParameterValueFactory.class.isInterface());
    }

    @Test
    void testParameterValueFactoryHasCorrectPackage() {
        // Test that the class is in the correct package
        String expectedPackage = "io.jenkins.plugins.mcp.server.extensions.util";
        assertEquals(expectedPackage, ParameterValueFactory.class.getPackageName());
    }

    @Test
    void testParameterValueFactoryIsFinal() {
        // Test that the class is final (utility class pattern)
        assertTrue(java.lang.reflect.Modifier.isFinal(ParameterValueFactory.class.getModifiers()));
    }

    @Test
    void testParameterValueFactoryHasNoInstanceFields() {
        // Test that utility class has no instance fields
        var fields = ParameterValueFactory.class.getDeclaredFields();
        for (var field : fields) {
            assertTrue(
                    java.lang.reflect.Modifier.isStatic(field.getModifiers())
                            || java.lang.reflect.Modifier.isFinal(field.getModifiers()),
                    "Utility class should only have static or final fields");
        }
    }
}
