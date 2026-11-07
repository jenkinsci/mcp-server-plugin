package io.jenkins.plugins.mcp.server.extensions.util;

import hudson.cli.CLICommand;
import hudson.model.Failure;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.StringParameterValue;

/**
 * Test stub simulating an arbitrary third-party plugin parameter definition that implements
 * createValue(CLICommand, String) rather than createValue(String).
 */
public class TestGenericCliParameterDefinition extends ParameterDefinition {
    private final boolean failValidation;

    public TestGenericCliParameterDefinition(String name, boolean failValidation) {
        super(name, "Generic CLI parameter for testing");
        this.failValidation = failValidation;
    }

    @Override
    public ParameterValue createValue(CLICommand command, String value) {
        if (failValidation) {
            throw new Failure("generic cli validation failed");
        }
        return new StringParameterValue(getName(), value);
    }

    @Override
    public ParameterValue getDefaultParameterValue() {
        return new StringParameterValue(getName(), "generic-default");
    }
}
