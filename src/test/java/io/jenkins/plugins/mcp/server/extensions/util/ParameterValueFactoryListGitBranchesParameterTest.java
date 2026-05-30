package io.jenkins.plugins.mcp.server.extensions.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.syhuang.hudson.plugins.listgitbranchesparameter.TestListGitBranchesParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.StringParameterValue;
import org.junit.jupiter.api.Test;

class ParameterValueFactoryListGitBranchesParameterTest {

    @Test
    void createsListGitBranchesParameterValueUsingCliOverload() {
        var param = new TestListGitBranchesParameterDefinition(false);

        ParameterValue value = ParameterValueFactory.createParameterValue(param, "release/1.0");

        assertThat(value).isInstanceOfSatisfying(StringParameterValue.class, v -> assertThat(v.getValue())
                .isEqualTo("release/1.0"));
    }

    @Test
    void fallsBackToDefaultWhenListGitBranchesParameterRejectsValue() {
        var param = new TestListGitBranchesParameterDefinition(true);

        ParameterValue value = ParameterValueFactory.createParameterValue(param, "bad-branch");

        assertThat(value).isInstanceOfSatisfying(StringParameterValue.class, v -> assertThat(v.getValue())
                .isEqualTo("main"));
    }
}
