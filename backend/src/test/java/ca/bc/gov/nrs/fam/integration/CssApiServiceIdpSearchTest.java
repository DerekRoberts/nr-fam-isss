package ca.bc.gov.nrs.fam.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.integration.CssApiService.CssUserDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CssApiService.searchIdpUsers (identity-provider user search)")
class CssApiServiceIdpSearchTest {

  private static final String TOKEN_BODY = "{\"access_token\":\"tok-1\",\"expires_in\":300}";

  private MockWebServer server;
  private CssApiService service;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    FamProperties.Integration.Css css = new FamProperties.Integration.Css(
        server.url("/api/v1").toString(), server.url("/token").toString(), "client-id",
        "client-secret", null, new FamProperties.Integration.Css.IdpAliases(null, null),
        new FamProperties.Integration.Timeouts(Duration.ofSeconds(2), Duration.ofSeconds(2)));
    FamProperties properties = new FamProperties("dev", null,
        new FamProperties.Integration(null, css, null, null));
    ObjectMapper objectMapper = new ObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    service = new CssApiService(properties, new RestClientFactory(),
        new UpstreamErrorTranslator(objectMapper), objectMapper);
    service.initClients();
    server.enqueue(new MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json").setBody(TOKEN_BODY));
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  @DisplayName("builds the provider path with the environment, encodes the query and maps rows")
  void buildsPathAndMapsRows() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json").setBody("""
            {"data":[{"username":"76a4033a7fe24371b2ee1ecb7f41a217@azureidir",
                      "email":"james.gagan@gov.bc.ca","firstName":"James","lastName":"Gagan",
                      "attributes":{"idir_username":["JGAGAN"],
                                    "idir_user_guid":["76a4033a7fe24371b2ee1ecb7f41a217"],
                                    "display_name":["Gagan, James CITZ:EX"]}}]}"""));

    List<CssUserDto> users = service.searchIdpUsers("dev", "/{env}/azure-idir/users",
        Map.of("lastName", "Ga gan"));

    RecordedRequest token = server.takeRequest();
    assertThat(token.getPath()).isEqualTo("/token");
    RecordedRequest search = server.takeRequest();
    assertThat(search.getMethod()).isEqualTo("GET");
    assertThat(search.getPath()).isEqualTo("/api/v1/dev/azure-idir/users?lastName=Ga%20gan");
    assertThat(search.getHeader("Authorization")).isEqualTo("Bearer tok-1");

    assertThat(users).singleElement().satisfies(u -> {
      assertThat(u.username()).isEqualTo("76a4033a7fe24371b2ee1ecb7f41a217@azureidir");
      assertThat(u.firstName()).isEqualTo("James");
      assertThat(u.attributes()).containsEntry("idir_username", List.of("JGAGAN"));
      assertThat(u.displayUsername()).isEqualTo("JGAGAN");
    });
  }

  @Test
  @DisplayName("an empty data envelope is no users, not an error")
  void emptyEnvelopeIsEmpty() {
    server.enqueue(new MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json").setBody("{\"data\":[]}"));

    assertThat(service.searchIdpUsers("dev", "/{env}/business-bceid/users",
        Map.of("guid", "abc"))).isEmpty();
  }
}
