package ca.bc.gov.nrs.fam.integration;

import ca.bc.gov.nrs.fam.constants.DirectoryEnv;
import ca.bc.gov.nrs.fam.dto.UserLookupBceidUserDto;
import ca.bc.gov.nrs.fam.dto.UserLookupIdirSearchResult;
import ca.bc.gov.nrs.fam.dto.UserLookupIdirUserDto;
import java.util.Optional;

/**
 * The directory FAM asks about people: who is this login, who is this GUID, who
 * matches this name. One implementation is active, chosen by
 * {@code fam.integration.user-lookup.provider}:
 *
 * <ul>
 *   <li>{@code api} (default) - {@link UserLookupClient}, nr-user-lookup-api in
 *       front of the BC Gov identity directory. Full contract: IDIR by login,
 *       name or GUID; Business BCeID by login or GUID.</li>
 *   <li>{@code css} - {@link CssUserDirectory}, the CSS API's own user search
 *       through FAM's team API account. No extra service or credentials, but
 *       IDIR people are found by name, email or GUID only, Business BCeID by GUID
 *       only, and only people who have signed in to a CSS application in that
 *       environment are known to it.</li>
 * </ul>
 *
 * <p>Callers depend on this interface, never on an implementation. An
 * unconfigured or unreachable directory is an error, not an empty result (see
 * {@link UserLookupClient}); an implementation that cannot serve a particular
 * query shape answers "no match" and says so in its log.
 */
public interface UserDirectory {

  /** Whether this directory can answer about the given environment at all. */
  boolean isConfigured(DirectoryEnv environment);

  /** Partial-match IDIR search for the user picker; only non-blank criteria count. */
  UserLookupIdirSearchResult searchIdir(
      DirectoryEnv environment, String userId, String firstName, String lastName,
      Integer pageSize);

  /** Exact IDIR match by login. Empty when unknown, or when this directory cannot search by login. */
  Optional<UserLookupIdirUserDto> getIdirDetail(DirectoryEnv environment, String userId);

  /** Exact IDIR match by GUID, the key CSS itself uses. */
  Optional<UserLookupIdirUserDto> getIdirDetailByGuid(DirectoryEnv environment, String userGuid);

  /** Business BCeID lookup by login or GUID; the same-organisation rule is the caller's. */
  Optional<UserLookupBceidUserDto> getBusinessBceid(
      DirectoryEnv environment, UserLookupClient.SearchBy searchBy, String searchValue);
}
