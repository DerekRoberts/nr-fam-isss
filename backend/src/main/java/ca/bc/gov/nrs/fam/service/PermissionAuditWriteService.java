package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.security.AuditUser;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.PrivilegeChangeType;
import ca.bc.gov.nrs.fam.constants.PrivilegeDetailsPermissionType;
import ca.bc.gov.nrs.fam.constants.PrivilegeDetailsScopeType;
import ca.bc.gov.nrs.fam.dto.CssUserRoleAssignmentResult;
import ca.bc.gov.nrs.fam.dto.PrivilegeChangeTargetDto;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.constants.DirectoryEnv;
import ca.bc.gov.nrs.fam.integration.UserDirectory;
import ca.bc.gov.nrs.fam.integration.UserLookupClient;
import ca.bc.gov.nrs.fam.dto.PrivilegeChangePerformerDto;
import ca.bc.gov.nrs.fam.dto.PrivilegeDetailsDto;
import ca.bc.gov.nrs.fam.dto.PrivilegeDetailsRoleDto;
import ca.bc.gov.nrs.fam.dto.PrivilegeDetailsScopeDto;
import ca.bc.gov.nrs.fam.dto.RoleDefinitionDetailsDto;
import ca.bc.gov.nrs.fam.dto.RoleDeletionDetailsDto;
import ca.bc.gov.nrs.fam.entity.FamPrivilegeChangeAudit;
import ca.bc.gov.nrs.fam.entity.FamPrivilegeChangeType;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.repository.FamPrivilegeChangeAuditRepository;
import ca.bc.gov.nrs.fam.security.Requester;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the privilege change audit trail.
 *
 * <p>Roles and assignments live in CSS, but the audit stays in FAM's own tables -
 * CSS keeps no history of who granted what to whom, so if this is not recorded
 * here it is not recorded anywhere.
 *
 * <p>Records are append-only and capture a <em>snapshot</em> of the performer,
 * the target and the privilege, so the trail stays truthful after roles are
 * later renamed or users removed. Since V94 nothing here is a foreign key.
 *
 * <p>The target snapshot is resolved from the identity directory rather than
 * taken from the request. An audit row must not take the identity of the person
 * it names from the caller who named them; the GUID is authoritative either way,
 * but the name beside it is what a reader trusts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionAuditWriteService {

  private final FamPrivilegeChangeAuditRepository auditRepository;
  private final EntityManager entityManager;
  private final ObjectMapper objectMapper;
  private final UserDirectory userLookupClient;
  private final ApiInstanceEnvResolver apiInstanceEnvResolver;
  private final CssNameSnapshot cssNameSnapshot;

  /**
   * Record a grant made through CSS.
   *
   * <p>One record per target user covering every role assigned to them in that
   * request. Only roles that were actually assigned are recorded: a partly
   * successful grant must not claim the failures, or the trail overstates what
   * the user was given.
   *
   * <p>Nothing is written when no assignment succeeded.
   */
  @Transactional
  public void storeCssGranted(
      Requester requester,
      String targetUserGuid,
      UserType targetUserType,
      int cssIntegrationId,
      String cssEnvironment,
      String roleName,
      List<CssUserRoleAssignmentResult> results) {

    List<CssUserRoleAssignmentResult> assigned =
        results.stream().filter(CssUserRoleAssignmentResult::assigned).toList();

    if (assigned.isEmpty()) {
      log.debug("No successful CSS assignments; no audit record to store.");
      return;
    }

    save(requester, targetUserGuid, targetUserType, cssIntegrationId, cssEnvironment,
        PrivilegeChangeType.GRANT,
        toCssDetails(cssIntegrationId, cssEnvironment, roleName, assigned));
  }

  /**
   * Record a revocation made through CSS.
   *
   * <p>Takes the role names as they were immediately before removal, since after
   * the fact there is nothing left in CSS to describe.
   */
  @Transactional
  public void storeCssRevoked(
      Requester requester,
      String targetUserGuid,
      UserType targetUserType,
      int cssIntegrationId,
      String cssEnvironment,
      String roleName,
      List<String> revokedRoleNames) {

    if (revokedRoleNames.isEmpty()) {
      log.debug("No roles revoked; no audit record to store.");
      return;
    }

    List<CssUserRoleAssignmentResult> asResults = revokedRoleNames.stream()
        .map(name -> new CssUserRoleAssignmentResult(name, false, true, null,
            ca.bc.gov.nrs.fam.constants.EmailSendingStatus.NOT_REQUIRED))
        .toList();

    save(requester, targetUserGuid, targetUserType, cssIntegrationId, cssEnvironment,
        PrivilegeChangeType.REVOKE,
        toCssDetails(cssIntegrationId, cssEnvironment, roleName, asResults));
  }

  /**
   * Record the definition of a role.
   *
   * <p>Wider than the other two: a grant changes what one person can do, this
   * changes what the application's roles mean. CSS keeps no history of either, so
   * without this row nothing anywhere records who introduced a role.
   *
   * <p><b>No target user</b>, which is what makes this row shaped differently -
   * there is nobody it was done to. Those columns are left null rather than
   * pointed at the performer, which would read as somebody granting themselves
   * something.
   *
   * <p>Not swallowed on failure. If this write fails the role already exists in
   * CSS and the caller is told the operation failed, which is the same bargain the
   * grant path makes: an unrecorded change is worse than a confusing one, and the
   * code is left free for a retry that does get recorded.
   *
   * @param scopeType {@code DISTRICT}, {@code FOREST_CLIENT} or null
   */
  @Transactional
  public void storeRoleCreated(
      Requester requester,
      int cssIntegrationId,
      String cssEnvironment,
      String roleCode,
      String roleName,
      String description,
      List<String> scopeTypes) {

    save(requester, null, null, cssIntegrationId, cssEnvironment,
        PrivilegeChangeType.CREATE_ROLE,
        RoleDefinitionDetailsDto.of(
            roleCode, roleName, description,
            (scopeTypes == null ? List.<String>of() : scopeTypes).stream()
                .map(PermissionAuditWriteService::toRequiredScopeType)
                .filter(java.util.Objects::nonNull)
                .toList()));
  }

  /**
   * Record that a role was removed from an application.
   *
   * <p>The only audit row that stands for more than one person losing access:
   * deleting a role in Keycloak takes it from everyone at once, and afterwards
   * there is nothing left upstream to say who those people were. The count is
   * captured before the deletion for that reason - it cannot be recovered later.
   *
   * <p>Like {@link #storeRoleCreated}, carries no target user: the change is to
   * the application's roles, not to one person's access.
   */
  public void storeRoleDeleted(
      Requester requester,
      int cssIntegrationId,
      String cssEnvironment,
      String roleCode,
      String roleName,
      List<String> removedRoles,
      int membersAffected) {

    save(requester, null, null, cssIntegrationId, cssEnvironment,
        PrivilegeChangeType.DELETE_ROLE,
        RoleDeletionDetailsDto.of(roleCode, roleName, removedRoles, membersAffected));
  }

  /**
   * Null for an unscoped role, rather than the CLIENT default the grant path
   * uses: there, a role is always scoped by something and CLIENT is the older of
   * the two. Here "no scope required" is a real answer and must not read as
   * "scoped by forest client".
   */
  private static PrivilegeDetailsScopeType toRequiredScopeType(String scopeType) {
    if (scopeType == null || scopeType.isBlank()) {
      return null;
    }
    return toDetailScopeType(scopeType);
  }

  /** Persist one audit row. */
  @Transactional
  void save(
      Requester requester,
      String targetUserGuid,
      UserType targetUserType,
      Integer cssIntegrationId,
      String cssEnvironment,
      PrivilegeChangeType changeType,
      // Typed as Object because the document's shape follows the change type: a
      // grant records privileges, a role definition records a role.
      Object privilegeDetails) {

    FamPrivilegeChangeAudit audit = new FamPrivilegeChangeAudit();
    audit.setCssIntegrationId(cssIntegrationId);
    audit.setCssEnvironment(cssEnvironment);
    /*
        The application's name, written rather than resolved when the history is
        read. An integration removed from CSS - decommissioned, or moved to
        another team - would otherwise leave every row about it labelled by a
        number, which is not an answer a trail should ever give about itself.

        Best effort: null when CSS cannot be reached, and the read path falls
        back exactly as it did before. A record that failed to write because a
        label could not be fetched would be the worse outcome by far.
    */
    audit.setCssApplicationName(
        cssNameSnapshot.applicationName(cssIntegrationId, cssEnvironment).orElse(null));
    // Both identities in the <TYPE>\<GUID> form the audit columns use, so one
    // value names the person and the directory they came from. That prefix is
    // why there is no separate target_user_type_code: it would be a second copy
    // of what is already here.
    audit.setTargetUser(
        targetUserGuid == null ? null : AuditUser.of(targetUserType, targetUserGuid));
    audit.setPerformerUser(AuditUser.of(requester));
    audit.setPrivilegeChangeType(
        entityManager.getReference(FamPrivilegeChangeType.class, changeType.name()));
    audit.setCreateUser(AuditUser.of(requester));
    // change_date is set explicitly rather than defaulted: for backfilled rows it
    // is not the same as create_date, so it is never derived from one.
    audit.setChangeDate(LocalDateTime.now());
    audit.setChangePerformerUserDetails(toJson(performerDetails(requester)));
    if (targetUserGuid != null) {
      audit.setChangeTargetUserDetails(toJson(
          targetDetails(cssEnvironment, targetUserGuid, targetUserType)));
    }
    audit.setPrivilegeDetails(toJson(privilegeDetails));

    log.debug("Adding audit record for {} on integration {}/{}",
        changeType, cssIntegrationId, cssEnvironment);
    auditRepository.save(audit);
  }

  /**
   * Resolve the target's name from the identity directory.
   *
   * <p>Best effort by design. A directory that is slow, down or simply does not
   * know this GUID must not fail a grant that CSS has already applied - the
   * change happened, and refusing to record it would be strictly worse than
   * recording it without a name. The GUID is always stored either way.
   */
  private PrivilegeChangeTargetDto targetDetails(
      String cssEnvironment, String userGuid, UserType userType) {

    try {
      // The directory belonging to the application this change was made against.
      // A GUID means different people in different environments, so looking it
      // up in the wrong one would write somebody else's name into the trail.
      DirectoryEnv directory = apiInstanceEnvResolver.resolveDirectory(cssEnvironment);
      if (userType == UserType.IDIR) {
        return userLookupClient.getIdirDetailByGuid(directory, userGuid)
            .map(u -> new PrivilegeChangeTargetDto(
                userGuid, u.userId(), u.firstName(), u.lastName(), u.email()))
            .orElseGet(() -> new PrivilegeChangeTargetDto(userGuid, null, null, null, null));
      }
      return userLookupClient
          .getBusinessBceid(directory, UserLookupClient.SearchBy.USER_GUID, userGuid)
          .map(u -> new PrivilegeChangeTargetDto(
              userGuid, u.userId(), u.firstName(), u.lastName(), u.email()))
          .orElseGet(() -> new PrivilegeChangeTargetDto(userGuid, null, null, null, null));
    } catch (RuntimeException e) {
      log.warn("Could not resolve target {} for the audit trail: {}", userGuid, e.getMessage());
      return new PrivilegeChangeTargetDto(userGuid, null, null, null, null);
    }
  }

  private static PrivilegeChangePerformerDto performerDetails(Requester requester) {
    if (requester == null) {
      return new PrivilegeChangePerformerDto("system", null, null, null);
    }
    return new PrivilegeChangePerformerDto(
        requester.userName(), requester.firstName(), requester.lastName(), requester.email());
  }

  /**
   * Build the privilege document for a CSS grant or revocation.
   *
   * <p>The scope value is recovered from the generated role name rather than
   * passed in, because that name is the only place CSS records it - the same
   * reason the read path has to parse it back out.
   *
   * <p>There is no expiry: CSS has no concept of one, so the expiry fields stay
   * null rather than carrying a value the underlying system will not honour.
   */
  private PrivilegeDetailsDto toCssDetails(
      Integer cssIntegrationId, String cssEnvironment,
      String roleName, List<CssUserRoleAssignmentResult> assigned) {

    // Every scope on every role actually assigned, read back out of the names.
    // Derived rather than taken from the request: a compound role carries two
    // scopes, and the name is the only record of which pair was granted.
    List<PrivilegeDetailsScopeDto> scopes = assigned.stream()
        .map(result -> ca.bc.gov.nrs.fam.dto.CssRoleNaming.parse(result.roleName()))
        .flatMap(parsed -> parsed.scopes().stream())
        .map(scope -> new PrivilegeDetailsScopeDto(
            toDetailScopeType(scope.type()),
            scope.value(),
            // The readable name, where FAM knows one. Written into the row
            // rather than resolved when the history is read: the trail has to
            // stay truthful about what a code meant at the time, and a region
            // renamed later would otherwise silently rewrite its own past.
            readableName(scope.type(), scope.value()),
            null))
        .toList();

    /*
        What the role is called, written beside the code rather than resolved
        when the history is read.

        A role's name lives on a sidecar role in CSS, and deleting a role takes
        its sidecar with it - so a trail that resolved names on read lost them
        for exactly the roles most worth reading about. The code is what the
        change was made against and stays authoritative; this is the word beside
        it.

        Null for a role with no sidecar, which is every role added directly in
        the CSS console, and null when CSS could not be reached.
    */
    String displayName = cssNameSnapshot
        .roleDisplayName(cssIntegrationId, cssEnvironment, roleName)
        .orElse(null);

    PrivilegeDetailsRoleDto roleDetails = scopes.isEmpty()
        ? new PrivilegeDetailsRoleDto(roleName, null, null, displayName)
        : new PrivilegeDetailsRoleDto(roleName, scopes, null, displayName);

    return new PrivilegeDetailsDto(
        PrivilegeDetailsPermissionType.END_USER, List.of(roleDetails));
  }

  /**
   * The name a person would recognise for a scope value, or null.
   *
   * <p>Only regions get one. A district's name could be resolved as cheaply -
   * both are fixed reference sets held in code - but the trail shows districts
   * by code, as the permission pills do, so writing the name would record
   * something nothing reads. An organisation's name comes from the Forest Client
   * API and is not worth a call on the write path of an audit row.
   */
  private static String readableName(String scopeType, String value) {
    if (!"REGION".equalsIgnoreCase(scopeType)) {
      return null;
    }
    // Optional rather than valueOf: a region retired from the enum must not
    // throw on the write path of an audit row. The code is already in the row
    // and reads perfectly well on its own.
    return ca.bc.gov.nrs.fam.constants.Region.fromRegionCode(value)
        .map(ca.bc.gov.nrs.fam.constants.Region::getRegionName)
        .orElse(null);
  }

  /**
   * Maps a role name's scope type onto the trail's.
   *
   * <p>Still defaults to CLIENT, which is the only scope FAM had before
   * districts - but REGION is named explicitly now. It used to fall through that
   * default, so every region-scoped grant was recorded as an organisation and the
   * history read back as one.
   */
  private static PrivilegeDetailsScopeType toDetailScopeType(String scopeType) {
    if ("DISTRICT".equalsIgnoreCase(scopeType)) {
      return PrivilegeDetailsScopeType.DISTRICT;
    }
    if ("REGION".equalsIgnoreCase(scopeType)) {
      return PrivilegeDetailsScopeType.REGION;
    }
    return PrivilegeDetailsScopeType.CLIENT;
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw FamHttpException.internalError(
          ErrorCode.UNKNOWN_STATE, "Cannot serialise audit detail.");
    }
  }
}
