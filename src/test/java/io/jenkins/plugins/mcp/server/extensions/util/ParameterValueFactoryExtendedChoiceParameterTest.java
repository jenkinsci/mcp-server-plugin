package io.jenkins.plugins.mcp.server.extensions.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.cwctravel.hudson.plugins.extended_choice_parameter.ExtendedChoiceParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.StringParameterValue;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParameterValueFactoryExtendedChoiceParameterTest {

    @Test
    void createsExtendedChoiceParameterValueForSingleString() {
        var param = new ExtendedChoiceParameterDefinition("TEST_PARAM", false);

        ParameterValue value = ParameterValueFactory.createParameterValue(param, "sanity");

        assertThat(value)
                .isInstanceOfSatisfying(
                        StringParameterValue.class,
                        v -> assertThat(v.getValue()).isEqualTo("sanity"));
    }

    @Test
    void createsExtendedChoiceParameterValueForListOfStrings() {
        var param = new ExtendedChoiceParameterDefinition("TEST_PARAM", false);

        ParameterValue value = ParameterValueFactory.createParameterValue(param, List.of("smoke", "sanity"));

        assertThat(value)
                .isInstanceOfSatisfying(
                        StringParameterValue.class,
                        v -> assertThat(v.getValue()).isEqualTo("smoke,sanity"));
    }

    @Test
    void createsExtendedChoiceParameterValueForStringArray() {
        var param = new ExtendedChoiceParameterDefinition("TEST_PARAM", false);

        ParameterValue value = ParameterValueFactory.createParameterValue(param, new String[] {"smoke", "sanity"});

        assertThat(value)
                .isInstanceOfSatisfying(
                        StringParameterValue.class,
                        v -> assertThat(v.getValue()).isEqualTo("smoke,sanity"));
    }

    @Test
    void fallsBackToDefaultWhenExtendedChoiceParameterRejectsValue() {
        var param = new ExtendedChoiceParameterDefinition("TEST_PARAM", true);

        ParameterValue value = ParameterValueFactory.createParameterValue(param, "bad-value");

        assertThat(value)
                .isInstanceOfSatisfying(
                        StringParameterValue.class,
                        v -> assertThat(v.getValue()).isEqualTo("default-choice"));
    }

    @Test
    void returnsDefaultWhenInputValueIsNull() {
        var param = new ExtendedChoiceParameterDefinition("TEST_PARAM", false);

        ParameterValue value = ParameterValueFactory.createParameterValue(param, null);

        assertThat(value)
                .isInstanceOfSatisfying(
                        StringParameterValue.class,
                        v -> assertThat(v.getValue()).isEqualTo("default-choice"));
    }

    @Test
    void createsGenericPluginParameterValueUsingCliMethod() {
        var param = new TestGenericCliParameterDefinition("GENERIC_PARAM", false);

        ParameterValue value = ParameterValueFactory.createParameterValue(param, "generic-val");

        assertThat(value)
                .isInstanceOfSatisfying(
                        StringParameterValue.class,
                        v -> assertThat(v.getValue()).isEqualTo("generic-val"));
    }

    @Test
    void fallsBackToDefaultWhenGenericCliParameterRejectsValue() {
        var param = new TestGenericCliParameterDefinition("GENERIC_PARAM", true);

        ParameterValue value = ParameterValueFactory.createParameterValue(param, "bad-val");

        assertThat(value)
                .isInstanceOfSatisfying(
                        StringParameterValue.class,
                        v -> assertThat(v.getValue()).isEqualTo("generic-default"));
    }
}
