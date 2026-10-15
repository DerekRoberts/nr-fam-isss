package ca.bc.gov.nrs.fam.integration;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.dto.CssIntegrationDto;
import ca.bc.gov.nrs.fam.dto.CssRoleDto;
import ca.bc.gov.nrs.fam.exception.UpstreamException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Client for the BC Gov Common Hosted Single Sign-On (CSS) API.
 *
 * <p>Port of {@code integration/css_api.py}. Source of applications (CSS
 * integrations), roles and role assignments, replacing the FAM tables that held
 * them.
 *
 * <p>A CSS integration spans environments - they arrive as an array on the
 * integration, and the role endpoints take the environment as a path segment. So
 * one integration maps onto several of what FAM calls an application.
 *
 * <p><b>The API account is team scoped.</b> Every call here sees every
 * integration the team owns, with no per-requester filtering. Nothing in this
 * class can answer "may this requester administer this application" - that is
 * resolved from the requester's own token instead.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CssApiService {

  private static final String UPSTREAM = "css-api";

  /** The IDIR provider the assignment endpoint refuses; see {@link #assignUserRoles}. */
  private static final String LEGACY_IDIR_ALIAS = "idir";

  /**
   * Refresh a little before expiry, so a token that passes the check here cannot
   * expire in flight on the request that follows.
   */
  private static final Duration TOKEN_EXPIRY_BUFFER = Duration.ofSeconds(30);

  /** Used when CSS omits {@code expires_in}, matching the Python default. */
  private static final Duration DEFAULT_TOKEN_LIFETIME = Duration.ofSeconds(300);

  private final FamProperties famProperties;
  private final RestClientFactory restClientFactory;
  private final UpstreamErrorTranslator errorTranslator;
  private final ObjectMapper objectMapper;

  private RestClient apiClient;
  private RestClient tokenClient;

  /**
   * Cached client_credentials token. Guarded by a lock so a burst of concurrent
   * requests mints one token rather than one each - the Python original cached on
   * the class with no guard, which under a thread-per-request server would race.
   */
  private final ReentrantLock tokenLock = new ReentrantLock();
  private String token;
  private Instant tokenExpiresAt = Instant.EPOCH;

  @PostConstruct
  void initClients() {
    if (!isConfigured()) {
      log.info("CSS API is not configured; CSS-backed endpoints will fail at call time.");
      return;
    }

    FamProperties.Integration.Css config = config();

    apiClient = restClientFactory.create(
        config.apiBaseUrl(),
        config.timeouts().connect(),
        config.timeouts().read(),
        headers -> headers.setAccept(List.of(MediaType.APPLICATION_JSON)));

    tokenClient = restClientFactory.create(
        config.tokenUrl(),
        config.timeouts().connect(),
        config.timeouts().read(),
        headers -> headers.setAccept(List.of(MediaType.APPLICATION_JSON)));

    warnIfUnsupportedIdpAlias(config);
  }

  /**
   * The assignment endpoint takes {@code azureidir} or {@code bceidbusiness} and
   * refuses anything else, the legacy {@code idir} included.
   *
   * <p>Warned about at startup rather than left to be discovered: misconfigured,
   * every grant fails with {@code invalid idp idir}, and the message names the
   * provider without hinting that a FAM setting chose it.
   */
  private static void warnIfUnsupportedIdpAlias(FamProperties.Integration.Css config) {
    if (LEGACY_IDIR_ALIAS.equalsIgnoreCase(config.idpAliases().idir())) {
      log.warn("fam.integration.css.idp-aliases.idir is set to '{}'. The CSS role assignment "
          + "endpoint rejects that provider, so every IDIR grant will fail with "
          + "'invalid idp {}'. Use 'azureidir' (CSS_IDP_ALIAS_IDIR).",
          LEGACY_IDIR_ALIAS, LEGACY_IDIR_ALIAS);
    }
  }

  private FamProperties.Integration.Css config() {
    return famProperties.integration() == null ? null : famProperties.integration().css();
  }

  /** False when credentials are absent, so callers can fail with a clear message. */
  public boolean isConfigured() {
    FamProperties.Integration.Css config = config();
    return config != null
        && notBlank(config.apiBaseUrl())
        && notBlank(config.tokenUrl())
        && notBlank(config.clientId())
        && notBlank(config.clientSecret());
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  /**
   * The integrations owned by the team this API account belongs to.
   *
   * <p>Team scoped, as noted on the class: no per-requester filtering happens
   * here.
   */
  public List<CssIntegrationDto> getIntegrations() {
    log.debug("CssApiService.getIntegrations()");
    return getList("/integrations", CssIntegrationDto.class);
  }

  /** Roles defined on one integration, in one environment. */
  public List<CssRoleDto> getRoles(int integrationId, String environment) {
    log.debug("CssApiService.getRoles({}, {})", integrationId, environment);
    return getList("/integrations/{id}/{env}/roles", CssRoleDto.class,
        integrationId, environment);
  }

  /**
   * The child roles of a composite role.
   *
   * <p>FAM reads these as scope-type markers: a chain containing
   * {@code HAS_DISTRICT_ROLE} is district scoped. Composite membership is the
   * only way to express scope type in CSS, which has no role attributes.
   */
  public List<String> getRoleComposites(int integrationId, String environment, String roleName) {
    return getList("/integrations/{id}/{env}/roles/{role}/composite-roles",
        CssRoleDto.class, integrationId, environment, roleName)
        .stream().map(CssRoleDto::name).toList();
  }

  /**
   * Create a role. CSS accepts only a name - there is nowhere to put a
   * description or an attribute.
   *
   * <p>Find-or-create: a 409 means something else created it first, which is the
   * desired end state either way.
   *
   * @return true when this call created the role, false when it already existed.
   *     Callers that clean up after a failure need the difference - deleting a
   *     role somebody else created would revoke whoever already holds it.
   */
  public boolean createRole(int integrationId, String environment, String roleName) {
    log.debug("CssApiService.createRole({})", roleName);

    ResponseEntity<byte[]> response = call(() -> apiClient.post()
        .uri("/integrations/{id}/{env}/roles", integrationId, environment)
        .header("Authorization", "Bearer " + accessToken())
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("name", roleName))
        .retrieve()
        .toEntity(byte[].class));

    if (response.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
      log.debug("CssApiService.createRole({}) - already exists", roleName);
      return false;
    }
    throwIfError(response);
    return true;
  }

  /**
   * Compose a role from other roles.
   *
   * <p>The children must already exist. Additive: this adds to whatever the role
   * is already composed of rather than replacing it.
   *
   * <p><b>Children reach the token.</b> Keycloak expands a composite, so every
   * child name appears alongside the parent in the access token of anyone holding
   * it. That is the point for a scope marker, and the reason a description is not
   * carried this way - see {@link ca.bc.gov.nrs.fam.dto.CssRoleNaming#LABEL_PREFIX}.
   */
  public void addRoleComposites(
      int integrationId, String environment, String roleName, List<String> childRoleNames) {

    log.debug("CssApiService.addRoleComposites({} <- {})", roleName, childRoleNames);

    ResponseEntity<byte[]> response = call(() -> apiClient.post()
        .uri("/integrations/{id}/{env}/roles/{role}/composite-roles",
            integrationId, environment, roleName)
        .header("Authorization", "Bearer " + accessToken())
        .contentType(MediaType.APPLICATION_JSON)
        .body(childRoleNames.stream().map(name -> Map.of("name", name)).toList())
        .retrieve()
        .toEntity(byte[].class));

    throwIfError(response);
  }

  /**
   * Delete a role.
   *
   * <p>Used to undo a partly built role, not exposed as an operation of its own:
   * deleting a role that people hold revokes them all at once, silently.
   */
  public void deleteRole(int integrationId, String environment, String roleName) {
    log.debug("CssApiService.deleteRole({})", roleName);

    ResponseEntity<byte[]> response = call(() -> apiClient.delete()
        .uri("/integrations/{id}/{env}/roles/{role}", integrationId, environment, roleName)
        .header("Authorization", "Bearer " + accessToken())
        .retrieve()
        .toEntity(byte[].class));

    throwIfError(response);
  }

  /**
   * Assign roles to a user, creating them in Keycloak if they are not there yet.
   *
   * <p>{@code username} is the CSS/Keycloak username ({@code <guid>@<idp>}), not
   * a FAM user name - see {@code CssRoleNaming.buildUsername}.
   *
   * <p>Uses {@code roles-new} rather than {@code roles}. The difference is what
   * happens for somebody who has never signed in to this environment: the older
   * endpoint answers <em>404 User not found</em>, because Keycloak only holds a
   * federated user once they have authenticated at least once. So granting access
   * to a new starter failed outright until they had logged in - which they could
   * not usefully do, having no access. {@code roles-new} verifies the username
   * against the upstream identity provider and creates the record itself.
   *
   * <p>It also refuses a username the directory does not recognise, with
   * <em>could not verify user ... with the upstream identity provider</em>. That
   * is worth having: a role assigned to a username that does not exist is a grant
   * that silently does nothing.
   *
   * <p><b>Only {@code azureidir} and {@code bceidbusiness} are accepted.</b> The
   * legacy {@code idir} alias is rejected outright ({@code invalid idp idir}) -
   * see {@code FamProperties.Integration.Css.IdpAliases}, whose default is already
   * {@code azureidir}.
   */
  public void assignUserRoles(
      int integrationId, String environment, String username, List<String> roleNames) {

    ResponseEntity<byte[]> response = call(() -> apiClient.post()
        /*
            Built through the client's own URI builder and logged from inside it,
            rather than logged from a second copy of the template alongside the
            call. The two would be free to drift, and the encoding of the
            username - a GUID, an "@" and an alias - is exactly the part worth
            seeing and exactly the part a hand-written copy would get wrong.

            At info: an assignment is a rare, deliberate act, and a refusal from
            this endpoint is answered by CSS rather than explained by it, so the
            address it was refused at is the first thing anybody asks for.
        */
        .uri(uriBuilder -> {
          URI uri = uriBuilder
              .path("/integrations/{id}/{env}/users/{username}/roles-new")
              .build(integrationId, environment, username);
          log.info("Assigning {} to {} via POST {}", roleNames, username, uri);
          return uri;
        })
        .header("Authorization", "Bearer " + accessToken())
        .contentType(MediaType.APPLICATION_JSON)
        .body(roleNames.stream().map(name -> Map.of("name", name)).toList())
        .retrieve()
        .toEntity(byte[].class));

    throwIfError(response);
  }

  /**
   * Take one role away from one user.
   *
   * <p>Removes the assignment, not the role: the role stays defined on the
   * integration, which is what lets it be granted again and is why scope-specific
   * roles accumulate.
   */
  public void removeUserRole(
      int integrationId, String environment, String username, String roleName) {

    log.debug("CssApiService.removeUserRole({} -/-> {})", username, roleName);

    ResponseEntity<byte[]> response = call(() -> apiClient.delete()
        .uri("/integrations/{id}/{env}/users/{username}/roles/{role}",
            integrationId, environment, username, roleName)
        .header("Authorization", "Bearer " + accessToken())
        .retrieve()
        .toEntity(byte[].class));

    throwIfError(response);
  }

  /**
   * The users holding one role.
   *
   * <p>The only view CSS gives of assignments <em>within</em> an integration:
   * there is no endpoint listing every user in one, so callers wanting the whole
   * picture fan out over roles and merge.
   */
  public List<CssUserDto> getUsersWithRole(
      int integrationId, String environment, String roleName) {

    return getList("/integrations/{id}/{env}/roles/{role}/users", CssUserDto.class,
        integrationId, environment, roleName);
  }

  /**
   * The roles one user holds in one integration environment.
   *
   * <p>The inverse of {@link #getUsersWithRole}, and the only way to answer "what
   * does this person hold here" without reading every role's membership.
   *
   * <p>Still per integration and environment - CSS has no cross-integration view
   * of a user - so answering "everywhere" means calling this once per pair.
   *
   * <p>Returns empty rather than failing when the user is unknown to that
   * environment, which is the common case: a person holds roles in a handful of
   * the integrations a team owns, not all of them.
   */
  public List<CssRoleDto> getUserRoles(
      int integrationId, String environment, String username) {

    log.debug("CssApiService.getUserRoles({}, {}, {})", integrationId, environment, username);

    return getList("/integrations/{id}/{env}/users/{username}/roles", CssRoleDto.class,
        integrationId, environment, username);
  }

  /**
   * A user as CSS reports them on a role membership listing.
   *
   * <p>{@code username} is the federated form, {@code <guid>@<alias>}. The
   * human-readable name lives in {@code attributes} - Keycloak returns each
   * attribute as a list, even single-valued ones.
   */
  @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CssUserDto(
      String username,
      String firstName,
      String lastName,
      String email,
      Map<String, List<String>> attributes) {

    private static final List<String> USERNAME_ATTRIBUTES =
        List.of("idir_username", "bceid_username");

    /**
     * The name a person would recognise, e.g. {@code JSMITH}.
     *
     * <p>Falls back to the federated username. Showing {@code <guid>@azureidir}
     * is poor, but inventing a name would be worse - and the fallback makes it
     * obvious the attribute mapper is missing rather than hiding it.
     */
    public String displayUsername() {
      if (attributes != null) {
        for (String key : USERNAME_ATTRIBUTES) {
          List<String> values = attributes.get(key);
          if (values != null && !values.isEmpty() && values.get(0) != null
              && !values.get(0).isBlank()) {
            return values.get(0);
          }
        }
      }
      return username;
    }
  }

  /**
   * Lists one identity provider's users matching a query, for example
   * {@code /dev/azure-idir/users?lastName=Smith}. CSS matches first name, last
   * name and email by substring and the GUID exactly, and searches the users
   * already present in that environment's Keycloak realm, not the directory
   * behind it. {@code uriTemplate} carries a single {@code {env}} variable.
   */
  public List<CssUserDto> searchIdpUsers(
      String environment, String uriTemplate, Map<String, String> query) {
    log.debug("CssApiService.searchIdpUsers({}, {}, {})", environment, uriTemplate,
        query.keySet());
    ResponseEntity<byte[]> response = call(() -> apiClient.get()
        .uri(uriBuilder -> {
          uriBuilder.path(uriTemplate);
          query.forEach(uriBuilder::queryParam);
          return uriBuilder.build(environment);
        })
        .header("Authorization", "Bearer " + accessToken())
        .retrieve()
        .toEntity(byte[].class));
    return readList(response, CssUserDto.class);
  }

  // ---------------------------------------------------------------------------
  // Plumbing
  // ---------------------------------------------------------------------------

  /**
   * A valid bearer token, minted on demand and reused until it nears expiry.
   *
   * <p>Double-checked under the lock: the first waiter refreshes and everyone
   * behind it finds the fresh token rather than each minting their own.
   */
  private String accessToken() {
    if (token != null && Instant.now().isBefore(tokenExpiresAt)) {
      return token;
    }

    tokenLock.lock();
    try {
      if (token != null && Instant.now().isBefore(tokenExpiresAt)) {
        return token;
      }

      log.debug("CssApiService: requesting a new access token");

      MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
      form.add("grant_type", "client_credentials");
      form.add("client_id", config().clientId());
      form.add("client_secret", config().clientSecret());

      ResponseEntity<byte[]> response = call(() -> tokenClient.post()
          .uri("")
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .toEntity(byte[].class));

      throwIfError(response);

      TokenResponse parsed = readValue(response.getBody(), TokenResponse.class);
      if (parsed == null || parsed.accessToken() == null) {
        throw new UpstreamException(HttpStatus.BAD_GATEWAY, ErrorCode.INVALID_OPERATION,
            "CSS token response contained no access_token.", UPSTREAM);
      }

      Duration lifetime = parsed.expiresIn() == null
          ? DEFAULT_TOKEN_LIFETIME
          : Duration.ofSeconds(parsed.expiresIn());

      token = parsed.accessToken();
      tokenExpiresAt = Instant.now().plus(lifetime).minus(TOKEN_EXPIRY_BUFFER);
      return token;

    } finally {
      tokenLock.unlock();
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record TokenResponse(
      @JsonProperty("access_token") String accessToken,
      @JsonProperty("expires_in") Long expiresIn) {}

  /**
   * GET a CSS collection endpoint.
   *
   * <p>Every list response wraps its rows in a {@code data} envelope; a missing
   * or null envelope reads as empty rather than as an error, matching how the
   * Python client treated it.
   */
  private <T> List<T> getList(String uriTemplate, Class<T> type, Object... uriVariables) {
    ResponseEntity<byte[]> response = call(() -> apiClient.get()
        .uri(uriTemplate, uriVariables)
        .header("Authorization", "Bearer " + accessToken())
        .retrieve()
        .toEntity(byte[].class));
    return readList(response, type);
  }

  /** Unwraps the CSS {@code data} envelope into a typed list; absent or null reads as empty. */
  private <T> List<T> readList(ResponseEntity<byte[]> response, Class<T> type) {
    throwIfError(response);

    byte[] body = response.getBody();
    if (body == null || body.length == 0) {
      return List.of();
    }

    try {
      JsonNode data = objectMapper.readTree(body).get("data");
      if (data == null || data.isNull()) {
        return List.of();
      }
      return objectMapper.readValue(
          objectMapper.treeAsTokens(data),
          objectMapper.getTypeFactory().constructCollectionType(List.class, type));
    } catch (Exception e) {
      throw new UpstreamException(HttpStatus.BAD_GATEWAY, ErrorCode.INVALID_OPERATION,
          "Unreadable response from the CSS API: " + new String(body, StandardCharsets.UTF_8),
          UPSTREAM, e);
    }
  }

  private <T> T readValue(byte[] body, Class<T> type) {
    try {
      return body == null || body.length == 0 ? null : objectMapper.readValue(body, type);
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Runs a call, failing clearly when unconfigured and translating a connectivity
   * failure into an upstream error.
   */
  private ResponseEntity<byte[]> call(CssCall call) {
    if (!isConfigured()) {
      throw new UpstreamException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.INVALID_OPERATION,
          "CSS API credentials are not configured (CSS_API_CLIENT_ID / CSS_API_CLIENT_SECRET).",
          UPSTREAM);
    }
    try {
      return call.run();
    } catch (ResourceAccessException e) {
      throw errorTranslator.connectivityFailure(UPSTREAM, e);
    }
  }

  private void throwIfError(ResponseEntity<byte[]> response) {
    if (response.getStatusCode().isError()) {
      throw errorTranslator.httpError(UPSTREAM, response.getStatusCode(), response.getBody(),
          HttpStatus.valueOf(response.getStatusCode().value()).getReasonPhrase());
    }
  }

  @FunctionalInterface
  private interface CssCall {
    ResponseEntity<byte[]> run();
  }
}
