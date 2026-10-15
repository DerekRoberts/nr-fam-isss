package ca.bc.gov.nrs.fam.integration;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.constants.DirectoryEnv;
import java.util.EnumMap;
import java.util.Map;
import ca.bc.gov.nrs.fam.dto.UserLookupBceidUserDto;
import ca.bc.gov.nrs.fam.dto.UserLookupIdirSearchResult;
import ca.bc.gov.nrs.fam.dto.UserLookupIdirUserDto;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Client for <b>nr-user-lookup-api</b>, the shared BC Gov identity directory.
 *
 * <p>Ported from {@code nr-fsp-new}'s {@code UserLookupClient}. Replaces the IDIM
 * proxy, which FAM previously called for the same three lookups.
 *
 * <h2>Two consequences of the swap</h2>
 *
 * <p><b>Lookups are no longer attributed to the person making them.</b> IDIM
 * required {@code requesterUserGuid} and {@code requesterAccountTypeCode} on
 * every call and audited against them. This service authenticates as FAM's own
 * service account, so the directory sees FAM rather than the administrator. If
 * per-user attribution is required, it has to be recorded on FAM's side.
 *
 * <p><b>The same-organisation rule is not upstream's job any more.</b> IDIM
 * received the requester and could reason about them; this API cannot. The rule -
 * a Business BCeID administrator may not read a user outside their own
 * organisation - is enforced by the caller instead. See
 * {@code IdentityLookupController}.
 *
 * <h2>Failure handling</h2>
 *
 * <p>Unlike FSP, an upstream failure is <em>not</em> swallowed into an empty
 * result. FSP's callers are best-effort enrichment; FAM's are an administrator
 * searching for a person to grant access to, and "no results" is a materially
 * different answer from "the directory is unreachable" - the first invites them
 * to give up, the second to retry.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "fam.integration.user-lookup.provider", havingValue = "api",
    matchIfMissing = true)
public class UserLookupClient implements UserDirectory {

  private static final String UPSTREAM = "user-lookup-api";

  private static final String BASE_PATH = "/api/v1/user-lookup";
  private static final String IDIR_DETAIL_PATH = BASE_PATH + "/idir-account-detail";
  private static final String IDIR_SEARCH_PATH = BASE_PATH + "/idir-users/search";
  private static final String BUSINESS_BCEID_PATH = BASE_PATH + "/businessBceid";

  private final FamProperties famProperties;
  private final RestClientFactory restClientFactory;
  private final UpstreamErrorTranslator errorTranslator;

  /** One client per environment, holding whichever instances are configured. */
  private final Map<DirectoryEnv, Endpoint> endpoints = new EnumMap<>(DirectoryEnv.class);

  /** One instance of the directory, ready to call. */
  private record Endpoint(
      RestClient http, ClientCredentialsTokenSource tokenSource, String baseUrl) {}

  @PostConstruct
  void init() {
    FamProperties.Integration.UserLookup config = config();
    if (config == null) {
      log.info("nr-user-lookup-api is not configured; identity lookups will fail at call time.");
      return;
    }

    build(DirectoryEnv.DEV, config.dev(), config);
    build(DirectoryEnv.TEST, config.test(), config);
    build(DirectoryEnv.PROD, config.prod(), config);

    if (endpoints.isEmpty()) {
      log.info("nr-user-lookup-api is not configured; identity lookups will fail at call time.");
    }
  }

  /**
   * Prepares one instance, if it has a host.
   *
   * <p>A missing instance is not a failure. A lower deployment holds no
   * production account, and refusing to start over an environment it will never
   * be asked about would make the directory harder to roll out one environment at
   * a time, not safer.
   */
  private void build(
      DirectoryEnv environment,
      FamProperties.Integration.UserLookup.Instance instance,
      FamProperties.Integration.UserLookup config) {

    if (instance == null || !instance.isConfigured()) {
      log.info("nr-user-lookup-api {} instance is not configured; "
          + "lookups for {} applications will fail at call time.", environment, environment);
      return;
    }

    RestClient http = restClientFactory.create(instance.baseUrl(),
        config.timeouts().connect(), config.timeouts().read(),
        headers -> headers.setAccept(List.of(MediaType.APPLICATION_JSON)));

    // Its own token source, not a shared one: each host is behind its own
    // Keycloak, and a token minted against one realm is refused by another.
    ClientCredentialsTokenSource tokenSource = ClientCredentialsTokenSource.fromProperties(
        instance.tokenUrl(), instance.clientId(), instance.clientSecret(), instance.scope(),
        config.timeouts().connect(), config.timeouts().read(), restClientFactory);

    endpoints.put(environment, new Endpoint(http, tokenSource, instance.baseUrl()));

    if (tokenSource == null) {
      log.warn("nr-user-lookup-api {} instance configured without credentials; calls will be "
          + "unauthenticated. Set USER_LOOKUP_TOKEN_URL_{} / CLIENT_ID_{} / CLIENT_SECRET_{}.",
          environment, environment, environment, environment);
    } else {
      log.info("nr-user-lookup-api {} client active (base-url={}, client-id={})",
          environment, instance.baseUrl(), tokenSource.clientId());
    }
  }

  private FamProperties.Integration.UserLookup config() {
    return famProperties.integration() == null ? null : famProperties.integration().userLookup();
  }

  /** Whether the instance for this environment can be called at all. */
  public boolean isConfigured(DirectoryEnv environment) {
    return endpoints.containsKey(environment);
  }

  /**
   * Partial-match IDIR search. Only non-blank criteria are sent.
   *
   * <p>An empty result means nobody matched; an unreachable directory throws.
   *
   * <p>The criteria are sent as flat query parameters. The directory's published
   * spec renders them as a single object parameter named {@code query}, which is
   * how springdoc describes a bean bound from the query string when it is not
   * annotated {@code @ParameterObject} - Spring still binds the individual
   * fields. This matches what nr-fsp-new sends.
   *
   * <p>Not sent: {@code firstNameMatchMode}, {@code lastNameMatchMode} and
   * {@code userIdMatchMode}, which the directory also accepts. Their defaults
   * decide whether this search is partial or exact, and neither FAM nor FSP sets
   * them.
   */
  public UserLookupIdirSearchResult searchIdir(
      DirectoryEnv environment, String userId, String firstName, String lastName,
      Integer pageSize) {

    Endpoint endpoint = endpointFor(environment);
    String user = normalize(userId);
    String first = normalize(firstName);
    String last = normalize(lastName);

    UserLookupIdirSearchResult result = exchange(environment, () -> endpoint.http().post()
        .uri(builder -> {
          builder.path(IDIR_SEARCH_PATH);
          if (user != null) {
            builder.queryParam("userId", user);
          }
          if (first != null) {
            builder.queryParam("firstName", first);
          }
          if (last != null) {
            builder.queryParam("lastName", last);
          }
          // The caller's page size is forwarded rather than dropped: the picker
          // asks for a wide result set, and the directory's own default is much
          // smaller.
          if (pageSize != null && pageSize > 0) {
            builder.queryParam("pageSize", pageSize);
          }
          return builder.build();
        })
        .headers(headers -> applyAuth(endpoint, headers))
        .retrieve()
        .body(UserLookupIdirSearchResult.class));

    return result == null ? UserLookupIdirSearchResult.empty() : result;
  }

  /** Exact IDIR match by user id. Empty when the directory reports no match. */
  public Optional<UserLookupIdirUserDto> getIdirDetail(DirectoryEnv environment, String userId) {
    return getIdirDetail(environment, "userId", userId);
  }

  /**
   * Exact IDIR match by GUID.
   *
   * <p>Needed because CSS identifies a user only as {@code <guid>@azureidir}, and
   * carries no name or email for anyone who has not yet signed in. Without this
   * there is no way to turn such a row back into a person.
   *
   * <p>Same endpoint and the same {@code idir:read} scope as the userId form -
   * the directory takes either key.
   */
  public Optional<UserLookupIdirUserDto> getIdirDetailByGuid(
      DirectoryEnv environment, String userGuid) {
    return getIdirDetail(environment, "userGuid", userGuid);
  }

  private Optional<UserLookupIdirUserDto> getIdirDetail(
      DirectoryEnv environment, String parameter, String value) {

    String normalized = normalize(value);
    if (normalized == null) {
      return Optional.empty();
    }

    Endpoint endpoint = endpointFor(environment);
    UserLookupIdirUserDto result = exchange(environment, () -> endpoint.http().get()
        .uri(builder -> builder.path(IDIR_DETAIL_PATH)
            .queryParam(parameter, normalized).build())
        .headers(headers -> applyAuth(endpoint, headers))
        .retrieve()
        .body(UserLookupIdirUserDto.class));

    return result == null || !result.found() ? Optional.empty() : Optional.of(result);
  }

  /**
   * Business BCeID lookup, by user id or user GUID.
   *
   * <p>Returns whatever the directory holds. It does <em>not</em> apply the
   * same-organisation rule: this client has no requester to compare against, so
   * that check belongs to the caller.
   */
  public Optional<UserLookupBceidUserDto> getBusinessBceid(
      DirectoryEnv environment, SearchBy searchBy, String searchValue) {

    String value = normalize(searchValue);
    if (searchBy == null || value == null) {
      return Optional.empty();
    }

    Endpoint endpoint = endpointFor(environment);
    UserLookupBceidUserDto result = exchange(environment, () -> endpoint.http().get()
        .uri(builder -> builder.path(BUSINESS_BCEID_PATH)
            .queryParam("searchUserBy", searchBy.wireValue())
            .queryParam("searchValue", value)
            .build())
        .headers(headers -> applyAuth(endpoint, headers))
        .retrieve()
        .body(UserLookupBceidUserDto.class));

    return result == null || !result.found() ? Optional.empty() : Optional.of(result);
  }

  /** How {@code searchValue} should be interpreted on a BCeID lookup. */
  public enum SearchBy {
    USER_ID("userId"),
    USER_GUID("userGuid");

    private final String wireValue;

    SearchBy(String wireValue) {
      this.wireValue = wireValue;
    }

    public String wireValue() {
      return wireValue;
    }
  }

  // ---------------------------------------------------------------------------

  /**
   * The instance for one environment, or a failure naming the one that is missing.
   *
   * <p>Never a fallback to another environment. A directory answering about the
   * wrong environment returns a real person's record under a GUID that does not
   * exist where the role is about to be written, and CSS refuses that assignment
   * without saying why - the silent failure this whole arrangement exists to
   * avoid. An unconfigured instance says so instead.
   */
  private Endpoint endpointFor(DirectoryEnv environment) {
    Endpoint endpoint = endpoints.get(environment);
    if (endpoint == null) {
      throw new ca.bc.gov.nrs.fam.exception.UpstreamException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          ca.bc.gov.nrs.fam.constants.ErrorCode.INVALID_OPERATION,
          ("The %s instance of nr-user-lookup-api is not configured, so users of %s "
              + "applications cannot be looked up. Set USER_LOOKUP_BASE_URL_%s and its "
              + "credentials.").formatted(environment, environment, environment),
          UPSTREAM);
    }
    return endpoint;
  }

  private void applyAuth(Endpoint endpoint, HttpHeaders headers) {
    if (endpoint.tokenSource() == null) {
      return;
    }
    String token = endpoint.tokenSource().fetchCached();
    if (!token.isBlank()) {
      headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
  }

  private <T> T exchange(DirectoryEnv environment, java.util.function.Supplier<T> call) {
    try {
      return call.get();
    } catch (ResourceAccessException e) {
      log.warn("nr-user-lookup-api {} instance unreachable.", environment);
      throw errorTranslator.connectivityFailure(UPSTREAM, e);
    }
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
