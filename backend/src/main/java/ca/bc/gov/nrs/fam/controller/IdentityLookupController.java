package ca.bc.gov.nrs.fam.controller;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.dto.IdimIdirUsersSearchParams;
import ca.bc.gov.nrs.fam.dto.UserLookupBceidUserDto;
import ca.bc.gov.nrs.fam.dto.UserLookupIdirSearchResult;
import ca.bc.gov.nrs.fam.dto.UserLookupIdirUserDto;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.integration.UserDirectory;
import ca.bc.gov.nrs.fam.integration.UserLookupClient;
import ca.bc.gov.nrs.fam.service.ApiInstanceEnvResolver;
import ca.bc.gov.nrs.fam.security.AuthorizationService;
import ca.bc.gov.nrs.fam.security.Requester;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identity lookups, served by nr-user-lookup-api.
 *
 * <p>Was {@code router_idim_proxy.py}, against the IDIM proxy. Two things
 * changed with the directory:
 *
 * <ul>
 *   <li>There is no {@code environment} parameter. IDIM had TEST and PROD
 *       instances; the directory does not, so nothing here consults
 *       {@code ApiInstanceEnvResolver}.
 *   <li>The same-organisation rule is applied here rather than upstream. IDIM
 *       received the requester and could reason about them; the directory
 *       authenticates as FAM's service account and cannot.
 * </ul>
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/identity-lookup")
@Tag(name = "IDIR/BCeID Proxy")
@RequiredArgsConstructor
public class IdentityLookupController {

  private final UserDirectory userLookupClient;

  /*
      Which of the directory's three instances to ask. The environment comes from
      the caller because the answer belongs to an application: a person is a
      different account with a different GUID in each, and the GUID returned here
      is the one CSS will be asked to assign a role to. Looking somebody up in
      one environment and granting in another is refused by CSS with nothing said
      about why.
  */
  private final ApiInstanceEnvResolver apiInstanceEnvResolver;
  private final AuthorizationService authorizationService;

  /**
   * Look up an IDIR user.
   *
   * <p>IDIR-only: an external (BCeID) administrator has no business enumerating
   * government staff accounts.
   *
   * <p>Returns a not-found result rather than a 404, matching the shape the
   * frontend's verify-username flow already reads.
   */
  @GetMapping("/idir")
  @Operation(operationId = "idir_lookup", summary = "Lookup IDIR user",
      description = "Lookup an IDIR user by user ID.")
  public UserLookupIdirUserDto lookupIdir(
      @RequestParam @Size(max = 20) String userId,
      @RequestParam(required = false) String environment,
      Requester requester) {

    authorizationService.authorize(requester);
    authorizationService.internalOnlyAction(requester);

    return userLookupClient
        .getIdirDetail(apiInstanceEnvResolver.resolveDirectory(environment), userId)
        .orElseGet(() -> new UserLookupIdirUserDto(false, userId, null, null, null, null));
  }

  /**
   * Look up a Business BCeID user.
   *
   * <p>Open to IDIR and BCeID administrators alike, but a BCeID caller only ever
   * sees their own organisation - enforced here, after the lookup, because the
   * target's organisation is not known until the directory answers.
   *
   * <p>A user outside the caller's organisation is a 403 rather than a
   * not-found: reporting "no such user" would leak whether the account exists.
   */
  @GetMapping("/bceid")
  @Operation(operationId = "bceid_lookup", summary = "Lookup BCEID user",
      description = "Lookup a BCeID Business user by user ID.")
  public UserLookupBceidUserDto lookupBceid(
      @RequestParam @Size(max = 20) String userId,
      @RequestParam(required = false) String environment,
      Requester requester) {

    authorizationService.authorize(requester);

    UserLookupBceidUserDto result = userLookupClient
        .getBusinessBceid(apiInstanceEnvResolver.resolveDirectory(environment),
            UserLookupClient.SearchBy.USER_ID, userId)
        .orElse(null);

    if (result == null) {
      return new UserLookupBceidUserDto(false, userId, null, null, null, null, null, null);
    }

    authorizationService.enforceSameOrganization(requester, result.businessGuid());
    return result;
  }

  /** Partial-match IDIR search, for the user picker. */
  @GetMapping("/users/idir/search")
  @Operation(operationId = "search_idir_users", summary = "Search IDIR users",
      description = "Search for IDIR users.")
  public UserLookupIdirSearchResult searchIdirUsers(
      @ParameterObject @Valid IdimIdirUsersSearchParams searchParams,
      @RequestParam(required = false) String environment,
      Requester requester) {

    authorizationService.authorize(requester);

    // Cross-field rules bean validation cannot express: at least one term, and
    // each term at least two characters. Both exist to stop a search so broad
    // that the directory returns an arbitrary slice of it.
    try {
      searchParams.validate();
    } catch (IllegalArgumentException e) {
      throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER, e.getMessage());
    }

    log.info("Searching IDIR users on behalf of requester {} ({})",
        requester.userName(), requester.userGuid());

    return userLookupClient.searchIdir(
        apiInstanceEnvResolver.resolveDirectory(environment),
        searchParams.getUserId(), searchParams.getFirstName(), searchParams.getLastName(),
        searchParams.getPageSize());
  }
}
