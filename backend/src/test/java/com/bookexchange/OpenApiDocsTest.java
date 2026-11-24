package com.bookexchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class OpenApiDocsTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    private String spec() throws Exception {
        return mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void specIsAValidOpenApi3Document() throws Exception {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        SwaggerParseResult result = new OpenAPIParser().readContents(spec(), null, options);

        assertThat(result.getMessages()).isEmpty();
        assertThat(result.getOpenAPI().getOpenapi()).startsWith("3.");
        assertThat(result.getOpenAPI().getInfo().getTitle()).isEqualTo("Christmas Book Exchange API");
    }

    @Test
    void submitEndpointDocumentsEveryResponse() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/exchanges/{exchangeCode}/books'].post.summary").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/exchanges/{exchangeCode}/books'].post.parameters[0].name")
                        .value("exchangeCode"))
                .andExpect(jsonPath("$.paths['/api/exchanges/{exchangeCode}/books'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/exchanges/{exchangeCode}/books'].post.responses['201'].headers.Location")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/exchanges/{exchangeCode}/books'].post.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/api/exchanges/{exchangeCode}/books'].post.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/exchanges/{exchangeCode}/books'].post.responses['409']").exists());
    }

    @Test
    void retrieveEndpointsAreDocumented() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/exchanges/{exchangeCode}/books'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/exchanges/{exchangeCode}/books'].get.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/exchanges/{exchangeCode}/books/{bookId}'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/exchanges/{exchangeCode}/books/{bookId}'].get.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/exchanges/{exchangeCode}/books/{bookId}'].get.parameters[?(@.name=='X-Participant-Name')]").isNotEmpty())
                .andExpect(jsonPath("$.components.schemas.BookView.properties.amazonUrl").exists());
    }

    @Test
    void claimEndpointIsDocumented() throws Exception {
        String path = "$.paths['/api/exchanges/{exchangeCode}/books/{bookId}/claim'].post";
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath(path + ".summary").isNotEmpty())
                .andExpect(jsonPath(path + ".responses['200']").exists())
                .andExpect(jsonPath(path + ".responses['400']").exists())
                .andExpect(jsonPath(path + ".responses['404']").exists())
                .andExpect(jsonPath(path + ".responses['409']").exists())
                .andExpect(jsonPath("$.components.schemas.ClaimBookRequest.properties.participantName").exists());
    }

    @Test
    void errorResponsesUseTheSharedErrorSchema() throws Exception {
        String spec = spec();
        assertThat(spec).contains("#/components/schemas/ApiError");
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.schemas.ApiError.properties.error").exists())
                .andExpect(jsonPath("$.components.schemas.ApiError.properties.details.type").value("array"));
    }

    @Test
    void validationRulesAppearInTheRequestSchema() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.schemas.SubmitBookRequest.required.length()").value(4))
                .andExpect(jsonPath("$.components.schemas.SubmitBookRequest.properties.submitterName.maxLength").value(100))
                .andExpect(jsonPath("$.components.schemas.SubmitBookRequest.properties.bookTitle.maxLength").value(300))
                .andExpect(jsonPath("$.components.schemas.SubmitBookRequest.properties.amazonUrl.maxLength").value(2048))
                .andExpect(jsonPath("$.components.schemas.SubmitBookRequest.properties.amazonUrl.description")
                        .value(org.hamcrest.Matchers.containsString("amazon.com, amzn.to, a.co")))
                .andExpect(jsonPath("$.components.schemas.SubmitBookRequest.properties.wrappingPaper.enum.length()")
                        .value(6))
                .andExpect(jsonPath("$.components.schemas.SubmitBookRequest.properties.wrappingPaper.enum[0]")
                        .value("candy-cane"));
    }

    @Test
    void internalTestEndpointsAndActuatorAreNotDocumented() throws Exception {
        assertThat(spec()).doesNotContain("/test/").doesNotContain("/actuator");
    }

    @Test
    void swaggerUiIsServed() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
