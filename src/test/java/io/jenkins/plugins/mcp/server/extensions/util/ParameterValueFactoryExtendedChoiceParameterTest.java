package io.jenkins.plugins.mcp.server.extensions.util;

import static com.cwctravel.hudson.plugins.extended_choice_parameter.ExtendedChoiceParameterDefinition.PARAMETER_TYPE_CHECK_BOX;
import static org.assertj.core.api.Assertions.assertThat;

import com.cwctravel.hudson.plugins.extended_choice_parameter.ExtendedChoiceParameterDefinition;
import com.cwctravel.hudson.plugins.extended_choice_parameter.ExtendedChoiceParameterValue;
import hudson.model.ParameterValue;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ParameterValueFactoryExtendedChoiceParameterTest {

    public static Stream<Arguments> extendedChoiceSources() {
        return Stream.of(Arguments.of("feature, bug"), Arguments.of(List.of("feature", "bug")));
    }

    @ParameterizedTest
    @MethodSource("extendedChoiceSources")
    void createsExtendedChoiceParameterValueUsingCliOverload(Object inputValue) {
        var param = new ExtendedChoiceParameterDefinition(
                "test",
                PARAMETER_TYPE_CHECK_BOX,
                "feature,bug,fix",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                5,
                "test",
                ",");
        ParameterValue value = ParameterValueFactory.createParameterValue(param, inputValue);

        assertThat(value).isInstanceOfSatisfying(ExtendedChoiceParameterValue.class, v -> assertThat(v.getValue())
                .isEqualTo("feature,bug"));
    }
}
