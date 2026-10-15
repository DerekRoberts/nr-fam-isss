package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.CssRoleNaming;
import ca.bc.gov.nrs.fam.dto.CssUserRoleRowDto;
import ca.bc.gov.nrs.fam.dto.UserLookupBceidUserDto;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.constants.DirectoryEnv;
import ca.bc.gov.nrs.fam.integration.UserDirectory;
import ca.bc.gov.nrs.fam.integration.UserLookupClient;
import ca.bc.gov.nrs.fam.security.Requester;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * What of an application's assignment listing a requester may see.
 *
 * <p>An IDIR administrator sees all of it. A Business BCeID administrator is
 * external and sees only <b>BCeID users from their own organisation</b> - the
 * same rule upstream applied in the query behind this listing, restored here
 * because CSS is the source now and cannot be filtered by a join.
 *
 * <p>The grant path already refuses a target at another organisation. This closes
 * the reading half: without it a BCeID administrator cannot act on another
 * organisation's users but can still read them off the table.
 *
 * <h2>Why this costs a lookup per user</h2>
 *
 * <p>CSS identifies a user as {@code <guid>@bceidbusiness} and carries no
 * organisation, so the only way to know whose employee somebody is, is to ask the
 * directory. Two things keep that bounded: IDIR rows are dropped first and cost
 * nothing, and each distinct user is resolved once however many roles they hold.
 *
 * <p>There is deliberately <b>no cap</b> on those lookups, unlike
 * {@link AssignmentRowEnrichmentService}. A cap on a filter would have to either
 * drop rows it could not check - a listing that silently understates who has
 * access - or admit them unchecked, which is the hole this exists to close.
 *
 * <h2>Failure is an error, not an empty table</h2>
 *
 * <p>Enrichment swallows a directory failure because names are cosmetic. Here the
 * outcome decides which rows exist, so a failure is raised: a BCeID administrator
 * shown a short list would reasonably conclude those users have no access, and
 * act on it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssignmentVisibilityService {

  private static final String BCEID_DOMAIN = "BCEID";

  private final UserDirectory userLookupClient;
  private final AssignmentRowEnrichmentService enrichmentService;

  /**
   * The rows this requester may see, named as far as possible.
   *
   * @param requester null for an internal caller, which is not somebody's view
   */
  public List<CssUserRoleRowDto> visibleTo(
      Requester requester, DirectoryEnv directory, List<CssUserRoleRowDto> rows) {
    if (requester == null || requester.userType() != UserType.BCEID) {
      return enrichmentService.withResolvedNames(directory, rows);
    }

    String ownOrganization = requester.businessGuid();
    if (ownOrganization == null || ownOrganization.isBlank()) {
      // Their own organisation is unknown, so no row can be shown to match it.
      // Refused rather than answered empty: an empty table reads as "nobody has
      // access", which is a different and misleading statement.
      throw FamHttpException.forbidden(ErrorCode.PERMISSION_REQUIRED,
          "Your organization could not be determined, so application users cannot be listed.");
    }

    // Free: an IDIR user belongs to no business, so none of them can match, and
    // dropping them first is what keeps the lookups below proportionate.
    List<CssUserRoleRowDto> candidates = rows.stream()
        .filter(row -> BCEID_DOMAIN.equals(row.domain()))
        .toList();

    if (candidates.isEmpty()) {
      return List.of();
    }

    Map<String, UserLookupBceidUserDto> bceidUsers = resolve(directory, candidates);

    List<CssUserRoleRowDto> visible = new ArrayList<>();
    for (CssUserRoleRowDto row : candidates) {
      /*
          The row's own GUID, not one re-derived from its username.

          `username` here is the display form - MVilleneuve3 - which
          guidFromUsername cannot read a GUID out of, because there is no "@" in
          it. So this resolved to nothing and every row was dropped as
          unrecognised, which is a BCeID administrator being shown an empty
          users table.

          It only ever worked for somebody CSS could not name, where
          displayUsername falls back to <guid>@bceidbusiness - so it worked until
          the person signed in for the first time, and broke afterwards.
      */
      UserLookupBceidUserDto user = guidOf(row).map(bceidUsers::get).orElse(null);

      if (user == null) {
        // Unverifiable, so not shown. Distinct from the directory being down,
        // which is raised above rather than reaching here.
        log.info("Omitting an assignment row for a BCeID user the directory does not recognise.");
        continue;
      }

      if (sameOrganization(ownOrganization, user.businessGuid())) {
        visible.add(named(row, user));
      }
    }

    log.debug("Showing {} of {} assignment row(s) to a BCeID administrator.",
        visible.size(), rows.size());
    return visible;
  }

  /**
   * Look each distinct user up once.
   *
   * <p>The answer carries the organisation this filters on <em>and</em> the name
   * CSS may not have, so the same call serves both - which is why BCeID rows are
   * named here rather than by the enrichment service.
   */
  private Map<String, UserLookupBceidUserDto> resolve(
      DirectoryEnv directory, List<CssUserRoleRowDto> rows) {
    Set<String> guids = new LinkedHashSet<>();
    for (CssUserRoleRowDto row : rows) {
      guidOf(row).ifPresent(guids::add);
    }

    Map<String, UserLookupBceidUserDto> resolved = new HashMap<>();
    for (String guid : guids) {
      // Deliberately unguarded: a failure here must reach the caller as an
      // error, not become a row quietly missing from the listing.
      userLookupClient.getBusinessBceid(directory, UserLookupClient.SearchBy.USER_GUID, guid)
          .ifPresent(user -> resolved.put(guid, user));
    }
    return resolved;
  }

  /**
   * The GUID CSS reported for this row.
   *
   * <p>Read from the field built for it rather than parsed back out of a
   * display name. Blank is treated as absent: a row FAM cannot identify cannot
   * be checked against an organisation either.
   */
  private static Optional<String> guidOf(CssUserRoleRowDto row) {
    return Optional.ofNullable(row.userGuid()).filter(guid -> !guid.isBlank());
  }

  /** An unknown organisation is not a matching one. */
  private static boolean sameOrganization(String ownOrganization, String targetOrganization) {
    return targetOrganization != null
        && ownOrganization.trim().equalsIgnoreCase(targetOrganization.trim());
  }

  private static CssUserRoleRowDto named(CssUserRoleRowDto row, UserLookupBceidUserDto user) {
    return new CssUserRoleRowDto(
        notBlank(user.userId()) ? user.userId() : row.username(),
        row.userGuid(),
        row.domain(),
        first(user.firstName(), row.firstName()),
        first(user.lastName(), row.lastName()),
        first(user.email(), row.email()),
        row.roleName(),
        row.roleDisplayName(),
        row.scopes(),
        // Carried through: these two rebuild a row to correct the name on it,
        // and dropping the expiry would quietly turn a temporary grant into a
        // permanent-looking one on the very rows that needed a lookup.
        row.expiresOn());
  }

  private static String first(String preferred, String fallback) {
    return notBlank(preferred) ? preferred : fallback;
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }
}
