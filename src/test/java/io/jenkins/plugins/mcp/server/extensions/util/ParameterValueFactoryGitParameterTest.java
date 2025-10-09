package io.jenkins.plugins.mcp.server.extensions.util;

import static org.assertj.core.api.Assertions.assertThat;

import hudson.model.ParameterValue;
import hudson.model.StringParameterValue;
import java.lang.reflect.Field;
import net.uaznia.lukanus.hudson.plugins.gitparameter.Consts;
import net.uaznia.lukanus.hudson.plugins.gitparameter.GitParameterDefinition;
import org.junit.jupiter.api.Test;

class ParameterValueFactoryGitParameterTest {

    @Test
    void createsGitParameterValueUsingCliOverload() {
        ParameterValue value = createParameterValue(true, "feature/new");

        assertThat(value).isInstanceOfSatisfying(StringParameterValue.class, v -> assertThat(v.getValue())
                .isEqualTo("feature/new"));
    }

    @Test
    void fallsBackToDefaultWhenGitParameterRejectsValue() {
        ParameterValue value = createParameterValue(false, "bad-branch");

        assertThat(value).isInstanceOfSatisfying(StringParameterValue.class, v -> assertThat(v.getValue())
                .isEqualTo("master"));
    }

    private ParameterValue createParameterValue(boolean allowAny, String inputValue) {
        var definition = new GitParameterDefinition(
                "Branch",
                Consts.PARAMETER_TYPE_BRANCH,
                "master",
                "",
                null,
                null,
                null,
                null,
                null,
                null,
                false);
        return withAllowAnyParameterValue(
                allowAny, () -> ParameterValueFactory.createParameterValue(definition, inputValue));
    }

    private ParameterValue withAllowAnyParameterValue(boolean flag, ParameterValueSupplier supplier) {
        try {
            Field field = GitParameterDefinition.class.getDeclaredField("allowAnyParameterValue");
            field.setAccessible(true);
            boolean previous = field.getBoolean(null);
            field.setBoolean(null, flag);
            try {
                return supplier.get();
            } finally {
                field.setBoolean(null, previous);
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    private interface ParameterValueSupplier {
        ParameterValue get();
    }
}
