package ca.bc.gov.nrs.fam.security;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.UserLookupBceidUserDto;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.constants.DirectoryEnv;
import ca.bc.gov.nrs.fam.integration.UserDirectory;
import ca.bc.gov.nrs.fam.integration.UserLookupClient;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * A Business BCeID administrator may only grant to their own organisation.
 *
 * <p>Restores the guard upstream applied to the assignment endpoint
 * ({@code enforce_bceid_by_same_org_guard}), which was lost when grants moved to
 * CSS. The rule survived in {@link AuthorizationService#enforceSameOrganization}
 * but was left wired only to the BCeID lookup, so an administrator could not
 * <em>find</em> a user at another organisation through FAM while still being able
 * to grant to one by supplying the GUID directly.
 *
 * <p><b>The organisation is read from the directory, never from the request.</b>
 * The caller supplies a GUID and a user type; both are claims about somebody
 * else. Upstream took the same position - it looked each target up before
 * comparing - and it is the only version of this check that means anything,
 * since a caller who could assert their target's organisation could assert
 * their way past the rule.
 *
 * <p>This guard is separate from {@link AuthorizationService} because it needs an
 * upstream call to answer. Everything in that class is decided from the
 * requester's token alone, and that is a property worth keeping.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TargetOrganizationGuard {

  /**
   * Deliberately identical for "different organisation" and "no such user".
   *
   * <p>Distinguishing them would tell a BCeID administrator whether an account
   * exists at another organisation, which is exactly what the rule exists to
   * prevent. The BCeID lookup endpoint takes the same position for the same
   * reason.
   */
  private static final String REFUSED =
      "Operation requires business bceid users to be within the same organization";

  private final UserDirectory userLookupClient;
  private final AuthorizationService authorizationService;

  /**
   * Refuse a grant whose target is outside the requester's organisation.
   *
   * <p>An IDIR requester is unrestricted, matching upstream: they administer
   * across organisations by definition.
   *
   * <p>A BCeID requester may only manage <em>BCeID</em> users. An IDIR target is
   * refused without a lookup - upstream reached the same outcome by comparing an
   * absent business GUID, and its user listing excluded IDIR users from a BCeID
   * administrator's view entirely.
   *
   * <p><b>Fails closed.</b> If the directory cannot be reached the exception
   * propagates and the grant does not happen: an unverifiable organisation is not
   * a matching one.
   *
   * @param requester null for a system grant, which is not somebody's request
   */
  public void requireSameOrganization(
      Requester requester, DirectoryEnv directory, UserType targetUserType,
      String targetUserGuid) {

    if (requester == null || requester.userType() != UserType.BCEID) {
      return;
    }

    if (targetUserType != UserType.BCEID) {
      log.info("Refusing a BCeID administrator's grant to a {} user.", targetUserType);
      throw FamHttpException.forbidden(ErrorCode.PERMISSION_REQUIRED, REFUSED);
    }

    Optional<UserLookupBceidUserDto> target =
        userLookupClient.getBusinessBceid(
            directory, UserLookupClient.SearchBy.USER_GUID, targetUserGuid);

    if (target.isEmpty()) {
      // Not "no such user": see REFUSED.
      log.info("Refusing a grant to a BCeID user the directory does not recognise.");
      throw FamHttpException.forbidden(ErrorCode.PERMISSION_REQUIRED, REFUSED);
    }

    authorizationService.enforceSameOrganization(requester, target.get().businessGuid());
  }
}
