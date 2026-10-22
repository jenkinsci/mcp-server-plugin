package io.jenkins.plugins.mcp.server.extensions.util;

import static org.assertj.core.api.Assertions.assertThat;

import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.StringParameterValue;
import java.util.List;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.kohsuke.stapler.StaplerRequest2;

class ParameterValueFactoryFormSubmissionParameterTest {

    @Test
    void createsValueForDefinitionThatOnlyAcceptsFormSubmission() {
        var param = new FormOnlyParameterDefinition("version");

        ParameterValue value = ParameterValueFactory.createParameterValue(param, "1.2.3-build.45");

        assertThat(value).isInstanceOfSatisfying(StringParameterValue.class, v -> {
            assertThat(v.getName()).isEqualTo("version");
            assertThat(v.getValue()).isEqualTo("1.2.3-build.45");
        });
    }

    @Test
    void passesListValuesAsJsonArray() {
        var param = new FormOnlyParameterDefinition("versions");

        ParameterValue value = ParameterValueFactory.createParameterValue(param, List.of("a", "b"));

        assertThat(value)
                .isInstanceOfSatisfying(
                        StringParameterValue.class,
                        v -> assertThat(v.getValue()).isEqualTo("a\nb"));
    }

    @Test
    void returnsNullWhenDefinitionNeedsTheRequest() {
        var param = new RequestDependentParameterDefinition("version");

        assertThat(ParameterValueFactory.createParameterValue(param, "1.2.3")).isNull();
    }

    /** A definition that only supports form submission: no createValue(String), and the request is ignored. */
    static class FormOnlyParameterDefinition extends ParameterDefinition {
        FormOnlyParameterDefinition(String name) {
            super(name);
        }

        @Override
        public ParameterValue createValue(StaplerRequest2 request, JSONObject json) {
            Object value = json.get("value");
            String text = value instanceof JSONArray array
                    ? String.join("\n", array.stream().map(Object::toString).toList())
                    : String.valueOf(value);
            return new StringParameterValue(json.getString("name"), text);
        }
    }

    /** A definition that reads the HTTP request, which is not available when a build is triggered over MCP. */
    static class RequestDependentParameterDefinition extends ParameterDefinition {
        RequestDependentParameterDefinition(String name) {
            super(name);
        }

        @Override
        public ParameterValue createValue(StaplerRequest2 request, JSONObject json) {
            return new StringParameterValue(json.getString("name"), request.getParameter("value"));
        }
    }
}
