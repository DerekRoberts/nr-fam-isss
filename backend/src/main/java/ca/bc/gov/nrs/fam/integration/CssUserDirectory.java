package ca.bc.gov.nrs.fam.integration;

import ca.bc.gov.nrs.fam.constants.DirectoryEnv;
import ca.bc.gov.nrs.fam.dto.UserLookupBceidUserDto;
import ca.bc.gov.nrs.fam.dto.UserLookupIdirSearchResult;
import ca.bc.gov.nrs.fam.dto.UserLookupIdirUserDto;
import ca.bc.gov.nrs.fam.integration.CssApiService.CssUserDto;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link UserDirectory} answered by the CSS API's own user search, through the
 * team API account FAM already holds. Nothing extra to deploy, onboard or keep
 * secret.
 *
 * <h2>What it can and cannot do</h2>
 *
 * <p>CSS searches the users already present in the environment's Keycloak realm
 * (anyone who has signed in to a CSS application there), by first name, last
 * name or email as a substring, or by GUID exactly. It has no filter on the IDIR
 * login, so a login-only search and a login lookup answer "no match" here and say
 * so in the log; a login supplied together with a name narrows the name search.
 * Business BCeID accounts are reachable by GUID only.
 *
 * <p>A person CSS has never seen is therefore not findable until they sign in
 * once - FAM itself is such an application, so "open FAM once" is the remedy.
 * Role assignment is unaffected: CSS verifies a GUID with the identity provider
 * at grant time regardless of what this directory knows.
 *
 * <p>Attribute names ({@code idir_username}, {@code idir_user_guid},
 * {@code bceid_username}, {@code bceid_user_guid}, {@code bceid_business_guid},
 * {@code bceid_business_name}) are the standard realm's; the GUID falls back to
 * the federated username's prefix when the attribute is absent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "fam.integration.user-lookup.provider", havingValue = "css")
public class CssUserDirectory implements UserDirectory {

  static final String AZURE_IDIR_PATH = "/{env}/azure-idir/users";
  static final String BUSINESS_BCEID_PATH = "/{env}/business-bceid/users";
  private static final int DEFAULT_PAGE_SIZE = 50;

  private final CssApiService cssApiService;

  @PostConstruct
  void init() {
    log.info("User directory provider: CSS API. IDIR people are found by name, email or GUID "
        + "(not by login), Business BCeID by GUID only, and only once they have signed in to a "
        + "CSS application in that environment.");
  }

  @Override
  public boolean isConfigured(DirectoryEnv environment) {
    return cssApiService.isConfigured();
  }

  @Override
  public UserLookupIdirSearchResult searchIdir(
      DirectoryEnv environment, String userId, String firstName, String lastName,
      Integer pageSize) {
    String login = normalize(userId);
    String first = normalize(firstName);
    String last = normalize(lastName);
    int size = pageSize == null || pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize;

    if (first == null && last == null) {
      if (login != null) {
        log.info("CSS directory cannot search IDIR users by login; answering no match. "
            + "Search by first or last name instead.");
      }
      return UserLookupIdirSearchResult.empty();
    }

    Map<String, String> query = new LinkedHashMap<>();
    if (first != null) {
      query.put("firstName", first);
    }
    if (last != null) {
      query.put("lastName", last);
    }
    List<UserLookupIdirUserDto> matches = cssApiService
        .searchIdpUsers(envOf(environment), AZURE_IDIR_PATH, query)
        .stream()
        .map(CssUserDirectory::toIdir)
        .filter(person -> login == null || login.equalsIgnoreCase(person.userId()))
        .toList();
    return new UserLookupIdirSearchResult(
        matches.size(), size, matches.stream().limit(size).toList());
  }

  @Override
  public Optional<UserLookupIdirUserDto> getIdirDetail(DirectoryEnv environment, String userId) {
    if (normalize(userId) != null) {
      log.info("CSS directory cannot look up an IDIR user by login; answering no match.");
    }
    return Optional.empty();
  }

  @Override
  public Optional<UserLookupIdirUserDto> getIdirDetailByGuid(
      DirectoryEnv environment, String userGuid) {
    String guid = normalize(userGuid);
    if (guid == null) {
      return Optional.empty();
    }
    return cssApiService
        .searchIdpUsers(envOf(environment), AZURE_IDIR_PATH, Map.of("guid", guid.toLowerCase(Locale.ROOT)))
        .stream()
        .map(CssUserDirectory::toIdir)
        .filter(person -> guid.equalsIgnoreCase(person.guid()))
        .findFirst();
  }

  @Override
  public Optional<UserLookupBceidUserDto> getBusinessBceid(
      DirectoryEnv environment, UserLookupClient.SearchBy searchBy, String searchValue) {
    String value = normalize(searchValue);
    if (value == null) {
      return Optional.empty();
    }
    if (searchBy != UserLookupClient.SearchBy.USER_GUID) {
      log.info("CSS directory cannot look up a Business BCeID user by login; answering no match.");
      return Optional.empty();
    }
    return cssApiService
        .searchIdpUsers(envOf(environment), BUSINESS_BCEID_PATH, Map.of("guid", value.toLowerCase(Locale.ROOT)))
        .stream()
        .map(CssUserDirectory::toBceid)
        .filter(person -> value.equalsIgnoreCase(person.guid()))
        .findFirst();
  }

  // ---------------------------------------------------------------------------

  private static String envOf(DirectoryEnv environment) {
    return environment.name().toLowerCase(Locale.ROOT);
  }

  private static UserLookupIdirUserDto toIdir(CssUserDto user) {
    return new UserLookupIdirUserDto(
        true,
        attribute(user, "idir_username"),
        guidOf(user, "idir_user_guid"),
        user.firstName(),
        user.lastName(),
        user.email());
  }

  private static UserLookupBceidUserDto toBceid(CssUserDto user) {
    return new UserLookupBceidUserDto(
        true,
        attribute(user, "bceid_username"),
        guidOf(user, "bceid_user_guid"),
        attribute(user, "bceid_business_guid"),
        attribute(user, "bceid_business_name"),
        user.firstName(),
        user.lastName(),
        user.email());
  }

  /** The GUID attribute, or the federated username's prefix ({@code <guid>@idp}) without it. */
  private static String guidOf(CssUserDto user, String attributeName) {
    String fromAttribute = attribute(user, attributeName);
    if (fromAttribute != null) {
      return fromAttribute;
    }
    String username = user.username();
    if (username == null) {
      return null;
    }
    int at = username.indexOf('@');
    return at > 0 ? username.substring(0, at) : username;
  }

  private static String attribute(CssUserDto user, String name) {
    if (user.attributes() == null) {
      return null;
    }
    List<String> values = user.attributes().get(name);
    if (values == null) {
      return null;
    }
    return values.stream().map(CssUserDirectory::normalize).filter(v -> v != null).findFirst()
        .orElse(null);
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
