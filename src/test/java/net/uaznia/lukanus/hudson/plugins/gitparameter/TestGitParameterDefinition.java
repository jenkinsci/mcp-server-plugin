package net.uaznia.lukanus.hudson.plugins.gitparameter;

import hudson.cli.CLICommand;
import hudson.model.Failure;
import hudson.model.ParameterValue;
import java.io.IOException;

/**
 * Test helper that bypasses SCM validation by returning the provided value directly.
 */
public class TestGitParameterDefinition extends GitParameterDefinition {
    private final boolean failValidation;

    public TestGitParameterDefinition(boolean failValidation) {
        super("Branch", Consts.PARAMETER_TYPE_BRANCH, "master", "", null, null, null, null, null, null, false);
        this.failValidation = failValidation;
    }

    @Override
    public ParameterValue createValue(CLICommand command, String value) throws IOException, InterruptedException {
        if (failValidation) {
            throw new Failure("invalid value");
        }
        return new GitParameterValue(getName(), value);
    }

    @Override
    public ParameterValue getDefaultParameterValue() {
        return new GitParameterValue(getName(), "master");
    }
}
