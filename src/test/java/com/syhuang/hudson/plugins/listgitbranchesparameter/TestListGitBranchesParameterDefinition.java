package com.syhuang.hudson.plugins.listgitbranchesparameter;

import hudson.cli.CLICommand;
import hudson.model.Failure;
import hudson.model.ParameterValue;
import hudson.model.StringParameterValue;

/**
 * Test helper that bypasses SCM validation by returning the provided value directly.
 */
public class TestListGitBranchesParameterDefinition extends ListGitBranchesParameterDefinition {
    private final boolean failValidation;

    public TestListGitBranchesParameterDefinition(boolean failValidation) {
        super(
                "branch",
                "PT_BRANCH",
                ".*",
                "*",
                "",
                SortMode.NONE,
                SelectedValue.DEFAULT,
                false,
                "main",
                "5",
                "https://example.invalid/repo.git",
                "");
        this.failValidation = failValidation;
    }

    @Override
    public ParameterValue createValue(CLICommand command, String value) {
        if (failValidation) {
            throw new Failure("invalid value");
        }
        return new StringParameterValue(getName(), value);
    }

    @Override
    public ParameterValue getDefaultParameterValue() {
        return new StringParameterValue(getName(), "main");
    }
}
