package io.jenkins.plugins.mcp.server.extensions.util;

import static org.assertj.core.api.Assertions.assertThat;

import hudson.model.ParameterValue;
import hudson.model.StringParameterValue;
import net.uaznia.lukanus.hudson.plugins.gitparameter.TestGitParameterDefinition;
import org.junit.jupiter.api.Test;

class ParameterValueFactoryGitParameterTest {

    @Test
    void createsGitParameterValueUsingCliOverload() {
        var param = new TestGitParameterDefinition(false);

        ParameterValue value = ParameterValueFactory.createParameterValue(param, "feature/new");

        assertThat(value).isInstanceOfSatisfying(StringParameterValue.class, v -> assertThat(v.getValue())
                .isEqualTo("feature/new"));
    }

    @Test
    void fallsBackToDefaultWhenGitParameterRejectsValue() {
        var param = new TestGitParameterDefinition(true);

        ParameterValue value = ParameterValueFactory.createParameterValue(param, "bad-branch");

        assertThat(value).isInstanceOfSatisfying(StringParameterValue.class, v -> assertThat(v.getValue())
                .isEqualTo("master"));
    }
}
