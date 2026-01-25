/*
 *
 * The MIT License
 *
 * Copyright (c) 2025, Ognyan Marinov.
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

import hudson.model.BooleanParameterDefinition;
import hudson.model.ChoiceParameterDefinition;
import hudson.model.FileParameterDefinition;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.PasswordParameterDefinition;
import hudson.model.RunParameterDefinition;
import hudson.model.StringParameterDefinition;
import hudson.model.TextParameterDefinition;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Factory class for creating parameter values from different parameter definitions.
 * Supports both core Jenkins parameter types and common plugin parameter types.
 */
@Slf4j
public final class ParameterValueFactory {

    public static final String GIT_PARAMETER_DEFINITION =
            "net.uaznia.lukanus.hudson.plugins.gitparameter.GitParameterDefinition";
    public static final String EXTENDED_CHOICE_PARAMETER_DEFINITION =
            "com.cwctravel.hudson.plugins.extended_choice_parameter.ExtendedChoiceParameterDefinition";

    /**
     * Creates a parameter value from a parameter definition and input value.
     * This method supports both core Jenkins parameter types and plugin-specific types.
     *
     * @param param the parameter definition
     * @param inputValue the input value from the user
     * @return the created parameter value, or null if creation fails
     */
    public static ParameterValue createParameterValue(ParameterDefinition param, Object inputValue) {
        try {
            if (isParameterDefinitionOf(param, GIT_PARAMETER_DEFINITION)) {
                return createGitParameterValue(param, inputValue);
            } else if (isParameterDefinitionOf(param, EXTENDED_CHOICE_PARAMETER_DEFINITION)) {
                return createExtendedChoiceParameterValue(param, inputValue);
            } else if (param instanceof StringParameterDefinition) {
                return createStringParameterValue((StringParameterDefinition) param, inputValue);
            } else if (param instanceof BooleanParameterDefinition) {
                return createBooleanParameterValue((BooleanParameterDefinition) param, inputValue);
            } else if (param instanceof ChoiceParameterDefinition) {
                return createChoiceParameterValue((ChoiceParameterDefinition) param, inputValue);
            } else if (param instanceof TextParameterDefinition) {
                return createTextParameterValue((TextParameterDefinition) param, inputValue);
            } else if (param instanceof PasswordParameterDefinition) {
                return createPasswordParameterValue((PasswordParameterDefinition) param, inputValue);
            } else if (param instanceof RunParameterDefinition) {
                return createRunParameterValue((RunParameterDefinition) param, inputValue);
            } else if (param instanceof FileParameterDefinition) {
                return createFileParameterValue((FileParameterDefinition) param, inputValue);
            } else {
                // Try to use reflection for plugin parameter types
                return createPluginParameterValue(param, inputValue);
            }
        } catch (Exception e) {
            log.warn(
                    "Failed to create parameter value for {}: {}",
                    param.getClass().getSimpleName(),
                    e.getMessage());
            return null;
        }
    }

    private static ParameterValue createStringParameterValue(StringParameterDefinition param, Object inputValue) {
        if (inputValue != null) {
            return param.createValue(String.valueOf(inputValue));
        }
        return param.getDefaultParameterValue();
    }

    private static ParameterValue createBooleanParameterValue(BooleanParameterDefinition param, Object inputValue) {
        if (inputValue != null) {
            boolean value;
            if (inputValue instanceof Boolean) {
                value = (Boolean) inputValue;
            } else if (inputValue instanceof String) {
                value = Boolean.parseBoolean((String) inputValue);
            } else {
                value = Boolean.parseBoolean(String.valueOf(inputValue));
            }
            return param.createValue(String.valueOf(value));
        }
        return param.getDefaultParameterValue();
    }

    private static ParameterValue createChoiceParameterValue(ChoiceParameterDefinition param, Object inputValue) {
        if (inputValue != null) {
            String value = String.valueOf(inputValue);
            // Validate that the choice is valid
            if (param.getChoices().contains(value)) {
                return param.createValue(value);
            } else {
                log.warn(
                        "Invalid choice '{}' for parameter '{}'. Valid choices: {}",
                        value,
                        param.getName(),
                        param.getChoices());
                return param.getDefaultParameterValue();
            }
        }
        return param.getDefaultParameterValue();
    }

    private static ParameterValue createTextParameterValue(TextParameterDefinition param, Object inputValue) {
        if (inputValue != null) {
            return param.createValue(String.valueOf(inputValue));
        }
        return param.getDefaultParameterValue();
    }

    private static ParameterValue createPasswordParameterValue(PasswordParameterDefinition param, Object inputValue) {
        if (inputValue != null) {
            String value = String.valueOf(inputValue);
            return param.createValue(value);
        }
        return param.getDefaultParameterValue();
    }

    private static ParameterValue createRunParameterValue(RunParameterDefinition param, Object inputValue) {
        if (inputValue != null) {
            String value = String.valueOf(inputValue);
            return param.createValue(value);
        }
        return param.getDefaultParameterValue();
    }

    private static ParameterValue createFileParameterValue(FileParameterDefinition param, Object inputValue) {
        // File parameters require special handling and are not fully supported via MCP
        // For now, we'll log a warning and return null
        log.warn(
                "File parameter '{}' is not supported via MCP. File parameters require file uploads.", param.getName());
        return null;
    }

    private static boolean isParameterDefinitionOf(ParameterDefinition param, String className) {
        Class<?> current = param.getClass();
        while (current != null) {
            if (current.getName().equals(className)) {
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private static ParameterValue createGitParameterValue(ParameterDefinition param, Object inputValue) {
        return createParameterValueViaCli(param, String.valueOf(inputValue));
    }

    private static ParameterValue createExtendedChoiceParameterValue(ParameterDefinition param, Object inputValue) {
        String valuesAsString;
        if (inputValue instanceof List<?> l) {
            valuesAsString = l.stream().map(Object::toString).collect(Collectors.joining(","));
        } else {
            valuesAsString = String.valueOf(inputValue);
        }
        return createParameterValueViaCli(param, valuesAsString);
    }

    private static ParameterValue createParameterValueViaCli(ParameterDefinition param, String inputValue) {
        String paramTypeName = param.getClass().getSimpleName();
        try {
            if (inputValue == null) {
                return param.getDefaultParameterValue();
            }
            var method = param.getClass().getMethod("createValue", hudson.cli.CLICommand.class, String.class);
            try {
                return (ParameterValue) method.invoke(param, null, inputValue);
            } catch (java.lang.reflect.InvocationTargetException e) {
                var cause = e.getTargetException();
                log.warn(
                        "{} parameter '{}' rejected value '{}': {}",
                        paramTypeName,
                        param.getName(),
                        inputValue,
                        cause.getMessage());
                return param.getDefaultParameterValue();
            }
        } catch (NoSuchMethodException e) {
            log.warn(
                    "{} parameter '{}' missing CLI createValue method; falling back to default",
                    paramTypeName,
                    param.getName());
        } catch (Exception e) {
            log.warn(
                    "Failed to create {} parameter value for '{}': {}", paramTypeName, param.getName(), e.getMessage());
        }
        return param.getDefaultParameterValue();
    }

    /**
     * Attempts to create a parameter value for plugin parameter types using reflection.
     * This allows support for custom parameter types without hardcoding them.
     */
    private static ParameterValue createPluginParameterValue(ParameterDefinition param, Object inputValue) {
        try {
            // Try to use the createValue method if it exists
            if (inputValue != null) {
                // First try with the input value
                try {
                    var method = param.getClass().getMethod("createValue", Object.class);
                    if (method != null) {
                        return (ParameterValue) method.invoke(param, inputValue);
                    }
                } catch (NoSuchMethodException e) {
                    // Method doesn't exist, continue to next approach
                }

                // Try with String parameter
                try {
                    var method = param.getClass().getMethod("createValue", String.class);
                    if (method != null) {
                        return (ParameterValue) method.invoke(param, String.valueOf(inputValue));
                    }
                } catch (NoSuchMethodException e) {
                    // Method doesn't exist, continue to next approach
                }

                // Try with boolean parameter
                if (inputValue instanceof Boolean) {
                    try {
                        var method = param.getClass().getMethod("createValue", boolean.class);
                        if (method != null) {
                            return (ParameterValue) method.invoke(param, inputValue);
                        }
                    } catch (NoSuchMethodException e) {
                        // Method doesn't exist, continue to next approach
                    }
                }

                // Try with List parameter (for multi-select parameters)
                if (inputValue instanceof List) {
                    try {
                        var method = param.getClass().getMethod("createValue", List.class);
                        if (method != null) {
                            return (ParameterValue) method.invoke(param, inputValue);
                        }
                    } catch (NoSuchMethodException e) {
                        // Method doesn't exist, continue to next approach
                    }
                }

                // Try with String[] parameter (for multi-select parameters)
                if (inputValue instanceof List) {
                    try {
                        List<?> list = (List<?>) inputValue;
                        String[] array = list.stream().map(String::valueOf).toArray(String[]::new);
                        var method = param.getClass().getMethod("createValue", String[].class);
                        if (method != null) {
                            return (ParameterValue) method.invoke(param, (Object) array);
                        }
                    } catch (NoSuchMethodException e) {
                        // Method doesn't exist, continue to next approach
                    }
                }
            }

            // If all else fails, try to get the default value
            try {
                var method = param.getClass().getMethod("getDefaultParameterValue");
                if (method != null) {
                    return (ParameterValue) method.invoke(param);
                }
            } catch (NoSuchMethodException e) {
                // Method doesn't exist
            }

            log.warn(
                    "Could not create parameter value for plugin parameter type: {}",
                    param.getClass().getName());
            return null;

        } catch (Exception e) {
            log.warn(
                    "Failed to create plugin parameter value for {}: {}",
                    param.getClass().getName(),
                    e.getMessage());
            return null;
        }
    }
}
