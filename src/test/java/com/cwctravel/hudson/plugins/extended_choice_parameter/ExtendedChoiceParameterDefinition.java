package com.cwctravel.hudson.plugins.extended_choice_parameter;

import hudson.cli.CLICommand;
import hudson.model.Failure;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.StringParameterValue;

/**
 * Test stub simulating ExtendedChoiceParameterDefinition from extended-choice-parameter plugin.
 * Crucially, it only supports createValue(CLICommand, String) and does not declare createValue(String).
 */
public class ExtendedChoiceParameterDefinition extends ParameterDefinition {
    private final boolean failValidation;

    public ExtendedChoiceParameterDefinition(String name, boolean failValidation) {
        super(name, "Extended choice parameter for testing");
        this.failValidation = failValidation;
    }

    @Override
    public ParameterValue createValue(CLICommand command, String value) {
        if (failValidation) {
            throw new Failure("invalid choice value");
        }
        return new StringParameterValue(getName(), value);
    }

    @Override
    public ParameterValue getDefaultParameterValue() {
        return new StringParameterValue(getName(), "default-choice");
    }
}
