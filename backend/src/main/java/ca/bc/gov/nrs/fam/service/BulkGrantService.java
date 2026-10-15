package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.constants.AdminRoleAuthGroup;
import ca.bc.gov.nrs.fam.constants.BulkUploadKind;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.FamAdminRole;
import ca.bc.gov.nrs.fam.integration.ForestClientIntegrationService;
import ca.bc.gov.nrs.fam.dto.CssScopeSelection;
import ca.bc.gov.nrs.fam.constants.FamConstants;
import ca.bc.gov.nrs.fam.constants.District;
import ca.bc.gov.nrs.fam.constants.Region;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.CssAdministratorAppointRequest;
import ca.bc.gov.nrs.fam.dto.CssBulkGrantPreviewDto;
import ca.bc.gov.nrs.fam.dto.CssBulkGrantRowDto;
import ca.bc.gov.nrs.fam.dto.CssDelegatedAdminRequest;
import ca.bc.gov.nrs.fam.dto.CssRoleNaming;
import ca.bc.gov.nrs.fam.dto.CssRoleOptionDto;
import ca.bc.gov.nrs.fam.dto.CssUserRoleAssignmentRequest;
import ca.bc.gov.nrs.fam.dto.CssUserRoleAssignmentResult;
import ca.bc.gov.nrs.fam.dto.UserLookupBceidUserDto;
import ca.bc.gov.nrs.fam.dto.UserLookupIdirUserDto;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.constants.DirectoryEnv;
import ca.bc.gov.nrs.fam.integration.UserDirectory;
import ca.bc.gov.nrs.fam.integration.UserLookupClient;
import ca.bc.gov.nrs.fam.security.AuthorizationService;
import ca.bc.gov.nrs.fam.security.Requester;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Granting many users a role at once, from a two-column CSV.
 *
 * <pre>
 * username,role
 * JSMITH,FSPTS_VIEW_ALL
 * </pre>
 *
 * <p>Deliberately the smallest file a person can be asked to produce. Everything
 * else the confirmation shows - names, organisations, the role's display name -
 * is resolved here, because those are what an uploader can actually check, and a
 * table of codes confirms nothing.
 *
 * <p><b>The file names people by username, not by GUID.</b> A username is what
 * an administrator has to hand and can check by eye; a GUID is transcribed from
 * somewhere else and is wrong silently. CSS, however, provisions a user by GUID
 * - so the directory lookup that every row already made to name the person is
 * now also what supplies the GUID the grant is made with. A username nobody
 * answers to is a refused row, where a mistyped GUID used to be one too, but
 * only after somebody had already got it wrong.
 *
 * <p><b>Two passes, and the second does not trust the first.</b> The preview
 * writes nothing; the apply re-reads and re-validates the same file. A preview
 * travels out through the browser, and granting what comes back would let an
 * edited payload grant something the checks never saw.
 */
@Slf4j
@Service
public class BulkGrantService {

  /**
   * Most rows accepted in one upload.
   *
   * <p>A refusal, not a truncation: silently granting the first N rows of a
   * longer file is the worst outcome available.
   *
   * <p><b>The cap is about the apply, not the preview.</b> It was 200 because
   * every row cost its own directory lookup; those are now made once per
   * distinct person in the file, so a hundred people across five districts each
   * is a hundred lookups rather than five hundred. What does not fold up is the
   * granting: CSS takes one call per assignment and they run in turn, so a file
   * of a thousand is a thousand sequential upstream calls inside one request.
   * That is the number to weigh when raising this.
   *
   * <p>Configurable so an application with more users than this can be moved
   * without a release - {@code FAM_BULK_MAX_ROWS}.
   */
  private final int maxRows;

  /**
   * The UTF-8 byte-order mark a spreadsheet writes at the start of a CSV.
   *
   * <p>Named rather than written as a literal: it is invisible in a source file,
   * so an editor or a merge could drop it without anyone noticing, and the
   * failure would be a first row that mysteriously will not parse.
   */
  private static final String BOM = "\uFEFF";

  private final CssIntegrationService cssIntegrationService;
  private final UserDirectory userLookupClient;
  private final AuthorizationService authorizationService;
  private final ca.bc.gov.nrs.fam.security.TargetOrganizationGuard targetOrganizationGuard;
  private final ForestClientIntegrationService forestClientIntegrationService;
  private final ApiInstanceEnvResolver apiInstanceEnvResolver;

  public BulkGrantService(
      CssIntegrationService cssIntegrationService,
      UserDirectory userLookupClient,
      AuthorizationService authorizationService,
      ca.bc.gov.nrs.fam.security.TargetOrganizationGuard targetOrganizationGuard,
      ForestClientIntegrationService forestClientIntegrationService,
      ApiInstanceEnvResolver apiInstanceEnvResolver,
      @org.springframework.beans.factory.annotation.Value("${fam.bulk-grant.max-rows:1000}")
      int maxRows) {
    this.cssIntegrationService = cssIntegrationService;
    this.userLookupClient = userLookupClient;
    this.authorizationService = authorizationService;
    this.targetOrganizationGuard = targetOrganizationGuard;
    this.forestClientIntegrationService = forestClientIntegrationService;
    this.apiInstanceEnvResolver = apiInstanceEnvResolver;
    this.maxRows = maxRows;
  }

  /** What the upload would do. Writes nothing. */
  public CssBulkGrantPreviewDto preview(
      int integrationId, String environment, String csv, Requester requester) {
    return preview(BulkUploadKind.USERS, integrationId, environment, csv, requester);
  }

  /** As {@link #preview}, for a file of administrators rather than of users. */
  public CssBulkGrantPreviewDto preview(
      BulkUploadKind kind, int integrationId, String environment, String csv,
      Requester requester) {

    return CssBulkGrantPreviewDto.of(
        validate(kind, integrationId, environment, csv, requester));
  }

  /**
   * Grant every row that validates.
   *
   * <p>Rows are applied one at a time and reported one at a time: a file of fifty
   * grants where the twentieth fails should still deliver the other forty-nine,
   * and say which one did not. That is the opposite of the all-or-nothing rule
   * the single grant path uses for one person's several scope values, because
   * here the rows are unrelated people.
   *
   * <p>Invalid rows are not attempted. They are returned with their reason, so a
   * caller that skipped the preview still learns what was left out.
   */
  public List<CssBulkGrantRowDto> apply(
      int integrationId, String environment, String csv, Requester requester) {
    return apply(BulkUploadKind.USERS, integrationId, environment, csv, requester);
  }

  /** As {@link #apply}, for a file of administrators rather than of users. */
  public List<CssBulkGrantRowDto> apply(
      BulkUploadKind kind, int integrationId, String environment, String csv,
      Requester requester) {

    List<CssBulkGrantRowDto> rows = validate(kind, integrationId, environment, csv, requester);
    List<CssBulkGrantRowDto> outcomes = new ArrayList<>();

    for (CssBulkGrantRowDto row : rows) {
      if (!row.valid()) {
        outcomes.add(row);
        continue;
      }
      outcomes.add(grantOne(kind, integrationId, environment, row, requester));
    }

    long granted = outcomes.stream().filter(CssBulkGrantRowDto::valid).count();
    log.info("Bulk {} on integration {} ({}): {} of {} row(s) applied by {}.",
        kind, integrationId, environment, granted, outcomes.size(), requester.userName());

    return outcomes;
  }

  private CssBulkGrantRowDto grantOne(
      BulkUploadKind kind, int integrationId, String environment, CssBulkGrantRowDto row,
      Requester requester) {

    if (kind == BulkUploadKind.APP_ADMINS) {
      return appointAppAdmin(integrationId, environment, row, requester);
    }
    if (kind == BulkUploadKind.DELEGATED_ADMINS) {
      return appointDelegate(integrationId, environment, row, requester);
    }

    try {
      List<CssUserRoleAssignmentResult> results = cssIntegrationService.assignUserRoles(
          integrationId, environment,
          new CssUserRoleAssignmentRequest(
              row.userGuid(), row.userType(), row.roleCode(), row.email(), scopesOf(row),
              // Bulk upload grants no expiry. The CSV is positional, so carrying
              // one means an appended column here, in the parser, in the preview
              // and in the e2e fixture - a change of its own rather than a
              // by-product of adding expiry to the single-grant screen.
              null,
              // And it notifies, as it always has. The per-grant opt-out belongs
              // to the screen where somebody is granting to one person they may
              // have already spoken to; a CSV of two hundred has no such context.
              null),
          requester);

      Optional<CssUserRoleAssignmentResult> failure =
          results.stream().filter(result -> !result.assigned()).findFirst();

      if (failure.isPresent()) {
        return withError(row, failure.get().errorMessage() == null
            ? "The role could not be assigned."
            : failure.get().errorMessage());
      }
      return row;

    } catch (FamHttpException e) {
      // A refusal that applies to this row alone - a self-grant, another
      // organisation, a role this requester may not grant.
      return withError(row, e.getDescription());
    } catch (RuntimeException e) {
      log.warn("Bulk grant row {} failed: {}", row.lineNumber(), e.getMessage());
      return withError(row, e.getMessage());
    }
  }

  /**
   * Appoint one row as an application administrator.
   *
   * <p>Delegates to the single-appointment path rather than assigning the role
   * here. That path carries the tier rules, the self-appointment guard and the
   * audit write, and a second implementation of it would be a second place for
   * those to drift.
   */
  private CssBulkGrantRowDto appointAppAdmin(
      int integrationId, String environment, CssBulkGrantRowDto row, Requester requester) {

    try {
      CssUserRoleAssignmentResult result = cssIntegrationService.appointApplicationAdmin(
          integrationId, environment,
          new CssAdministratorAppointRequest(row.userGuid(), row.userType()),
          requester);

      return result.assigned() ? row : withError(row, result.errorMessage() == null
          ? "The appointment could not be made." : result.errorMessage());

    } catch (FamHttpException e) {
      return withError(row, e.getDescription());
    } catch (RuntimeException e) {
      log.warn("Bulk application-admin row {} failed: {}", row.lineNumber(), e.getMessage());
      return withError(row, e.getMessage());
    }
  }

  /**
   * Appoint one row as a delegated administrator of the role it names.
   *
   * <p>One row is one delegation, so the scope columns carry a single value each
   * - the same shape the users file uses, and for the same reason: a delegation
   * names exactly one concrete role, and a row that named several would be
   * unreadable line by line.
   */
  private CssBulkGrantRowDto appointDelegate(
      int integrationId, String environment, CssBulkGrantRowDto row, Requester requester) {

    try {
      List<CssUserRoleAssignmentResult> results = cssIntegrationService.appointDelegatedAdmin(
          integrationId, environment,
          new CssDelegatedAdminRequest(
              row.userGuid(), row.userType(), row.roleCode(), scopesOf(row)),
          requester);

      Optional<CssUserRoleAssignmentResult> failure =
          results.stream().filter(result -> !result.assigned()).findFirst();

      if (failure.isPresent()) {
        return withError(row, failure.get().errorMessage() == null
            ? "The delegation could not be made." : failure.get().errorMessage());
      }
      return row;

    } catch (FamHttpException e) {
      return withError(row, e.getDescription());
    } catch (RuntimeException e) {
      log.warn("Bulk delegated-admin row {} failed: {}", row.lineNumber(), e.getMessage());
      return withError(row, e.getMessage());
    }
  }

  /**
   * The scopes one validated row grants for.
   *
   * <p>One value per dimension, because one row is one grant. The grant path
   * still expands a cross-product, but with a single value each that product is
   * the row itself - which is what keeps the file readable line by line.
   */
  private static List<CssScopeSelection> scopesOf(CssBulkGrantRowDto row) {
    List<CssScopeSelection> scopes = new ArrayList<>();
    if (row.district() != null) {
      scopes.add(new CssScopeSelection(CssRoleNaming.SCOPE_DISTRICT, List.of(row.district())));
    }
    if (row.region() != null) {
      scopes.add(new CssScopeSelection(CssRoleNaming.SCOPE_REGION, List.of(row.region())));
    }
    if (row.forestClientNumber() != null) {
      scopes.add(new CssScopeSelection(
          CssRoleNaming.SCOPE_FOREST_CLIENT, List.of(row.forestClientNumber())));
    }
    return scopes;
  }

  // ---------------------------------------------------------------------------
  // Validation
  // ---------------------------------------------------------------------------

  private List<CssBulkGrantRowDto> validate(
      int integrationId, String environment, String csv, Requester requester) {
    return validate(BulkUploadKind.USERS, integrationId, environment, csv, requester);
  }

  private List<CssBulkGrantRowDto> validate(
      BulkUploadKind kind, int integrationId, String environment, String csv,
      Requester requester) {

    List<ParsedRow> parsed = parse(csv);

    if (parsed.isEmpty()) {
      throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER,
          kind.needsRole()
              ? "The file has no rows. Expected two columns: a username and a role."
              : "The file has no rows. Expected two columns: a username and a user type.");
    }
    if (parsed.size() > maxRows) {
      throw FamHttpException.badRequest(ErrorCode.INVALID_REQUEST_PARAMETER,
          "The file has %d rows; the most that can be uploaded at once is %d."
              .formatted(parsed.size(), maxRows));
    }

    // One read of the application's roles for the whole file, rather than per row.
    // Skipped for an application-admin file, which names no role at all.
    Map<String, CssRoleOptionDto> roles = !kind.needsRole() ? Map.of() : cssIntegrationService
        .getRoles(integrationId, environment).stream()
        .collect(Collectors.toMap(
            role -> role.name().toUpperCase(Locale.ROOT),
            Function.identity(),
            (first, second) -> first));

    // One upstream call for every organisation the file mentions, not one per row.
    Map<String, ForestClient> clients = resolveClients(parsed, environment);

    Set<String> seen = new LinkedHashSet<>();
    List<CssBulkGrantRowDto> rows = new ArrayList<>();

    /*
        One directory lookup per person, not per row.

        A person granted a role for six districts is six rows and one human
        being, and the directory is SOAP-backed and slow enough that the
        difference is the difference between a file that uploads and one that
        times out. Held for this file only - a map that outlived the request
        would be a cache of who exists, which is not this service's to keep.

        Keyed by the stated directory as well as the name: a row saying IDIR and
        a row leaving the column empty are different questions, and the second
        may resolve to a Business BCeID user the first must never reach.
    */
    Map<String, Resolved> resolvedUsers = new HashMap<>();

    /*
        What each person already holds here, for the same reason and on the same
        terms: once per person rather than once per row.

        Keyed by GUID, because that is what the roles are held against - two rows
        naming the same person in different letter case are one lookup.
    */
    Map<String, Set<String>> heldRoles = new HashMap<>();

    /*
        Who already holds the appointment, for an administrator file.

        One listing for the whole file rather than a per-person read of FAM's own
        integration: the appointments live there rather than on the application
        being administered, so `heldRoles` above - which reads the application -
        would answer a different question and answer it wrongly.

        Keyed GUID first, then the concrete role for a delegation. An application
        administrator has no role to name, so the key is the GUID alone.
    */
    Set<String> appointed = appointmentsAlreadyHeld(kind, integrationId, environment);

    for (ParsedRow row : parsed) {
      rows.add(validateRow(kind, integrationId, environment, row, roles, clients, seen,
          resolvedUsers, heldRoles, appointed, requester));
    }

    log.debug("Validated {} row(s) with {} directory lookup(s).",
        parsed.size(), resolvedUsers.size());
    return rows;
  }

  /**
   * One row of an application-admin file.
   *
   * <p>Short by nature: the tier is the whole appointment, so there is no role to
   * resolve, no scope to check and no per-role grantability question. What is
   * left is who they are, whether FAM will have them, and whether they are
   * already appointed.
   */
  private CssBulkGrantRowDto validateAppAdminRow(
      int integrationId, String environment, ParsedRow row,
      Map<String, Resolved> resolvedUsers, Set<String> appointed, Requester requester) {

    /*
        Looked up in the IDIR directory and nowhere else.

        The tier is IDIR-only, so searching both and rejecting afterwards would
        spend a slow BCeID lookup to reach a refusal already known - and would
        let a BCeID username that happens to match resolve to a person the file
        cannot be asking for. Asking the one directory that can answer makes
        "no IDIR account named that" the natural result rather than a check
        bolted on after.
    */
    Resolved resolved;
    try {
      resolved = resolvedUsers.computeIfAbsent(
          row.userName().toUpperCase(Locale.ROOT) + "|IDIR",
          key -> resolve(apiInstanceEnvResolver.resolveDirectory(environment),
              row.userName(), UserType.IDIR));
    } catch (FamHttpException e) {
      return invalid(row, "", "", "", e.getDescription());
    }
    if (resolved == null) {
      return invalid(row, "", "", "",
          "No IDIR account named %s exists. Application administrators must be "
              .formatted(row.userName())
              + "IDIR accounts.");
    }

    /*
        Belt and braces on the tier rule, which the single-appointment path also
        enforces. The lookup above should make this unreachable; it stays because
        the guarantee is the tier's, not the directory call's, and a later change
        to how resolution works must not quietly take it away.
    */
    if (resolved.userType() != UserType.IDIR) {
      return invalid(row, "", "", "",
          "Application administrators must be IDIR accounts.");
    }

    CssBulkGrantRowDto candidate = new CssBulkGrantRowDto(
        row.lineNumber(), resolved.userGuid(), null, resolved.userType(),
        resolved.userName(), resolved.firstName(), resolved.lastName(),
        resolved.email(), resolved.organization(), null,
        null, null, null, null, null, null,
        true, false, null);

    if (appointed.contains(appointmentKey(resolved.userGuid(), null))) {
      return candidate.asAlreadyGranted();
    }

    try {
      authorizationService.forbidSelfGrant(requester, resolved.userGuid(), environment);
      // FAM administrators only, as the single-appointment path requires - a
      // file of a hundred rows must not be a way around it.
      authorizationService.requireApplicationAdminManagement(requester);
      targetOrganizationGuard.requireSameOrganization(
          requester, apiInstanceEnvResolver.resolveDirectory(environment),
          resolved.userType(), resolved.userGuid());
    } catch (FamHttpException e) {
      return withError(candidate, e.getDescription());
    }

    return candidate;
  }

  /**
   * The appointments this application already has, as comparison keys.
   *
   * <p>Empty for a users file, which asks a different question and answers it
   * per person against the application's own roles.
   */
  private Set<String> appointmentsAlreadyHeld(
      BulkUploadKind kind, int integrationId, String environment) {

    if (kind == BulkUploadKind.USERS) {
      return Set.of();
    }
    AdminRoleAuthGroup tier = kind == BulkUploadKind.APP_ADMINS
        ? AdminRoleAuthGroup.APP_ADMIN
        : AdminRoleAuthGroup.DELEGATED_ADMIN;

    return cssIntegrationService.getAdministrators(integrationId, environment, tier).stream()
        .filter(existing -> existing.userGuid() != null)
        .map(existing -> appointmentKey(
            existing.userGuid(),
            kind == BulkUploadKind.APP_ADMINS ? null : existing.delegatedRoleName()))
        .collect(Collectors.toSet());
  }

  /** GUID, plus the concrete role for a delegation. Upper-cased on both sides. */
  private static String appointmentKey(String userGuid, String roleName) {
    return userGuid.toUpperCase(Locale.ROOT)
        + (roleName == null ? "" : "|" + roleName.toUpperCase(Locale.ROOT));
  }

  private CssBulkGrantRowDto validateRow(
      BulkUploadKind kind, int integrationId, String environment, ParsedRow row,
      Map<String, CssRoleOptionDto> roles, Map<String, ForestClient> clients,
      Set<String> seen, Map<String, Resolved> resolvedUsers,
      Map<String, Set<String>> heldRoles, Set<String> appointed, Requester requester) {

    String district = row.district().toUpperCase(Locale.ROOT);
    String region = row.region().toUpperCase(Locale.ROOT);
    String clientNumber = padClientNumber(row.forestClient());

    if (row.userName().isEmpty()) {
      return invalid(row, district, region, clientNumber, "A username is required.");
    }
    if (kind.needsRole() && row.roleCode().isEmpty()) {
      return invalid(row, district, region, clientNumber,
          "Both a username and a role are required.");
    }
    /*
        An application administrator is appointed for the application, not for a
        role in it, and the tier is IDIR-only - so there is nothing for any of
        the other columns to mean. Refused rather than ignored: a file carrying
        them was written against a different template, and a file whose second
        column says BCEID is asking for something FAM does not do.
    */
    if (!kind.needsRole()
        && !(row.userType().isEmpty() && row.roleCode().isEmpty() && district.isEmpty()
             && region.isEmpty() && clientNumber.isEmpty())) {
      return invalid(row, district, region, clientNumber,
          "An application admin file has one column: a username. "
              + "Application admins are always IDIR accounts.");
    }

    // The key includes the scope: the same person may legitimately get the same
    // role for two districts, which is two rows, but not the same district
    // twice - that would grant once and report twice, reading as a silent
    // failure on the second.
    if (!seen.add(String.join("|", row.userName().toUpperCase(Locale.ROOT),
        row.roleCode().toUpperCase(Locale.ROOT), district, region, clientNumber))) {
      return invalid(row, district, region, clientNumber,
          "Duplicate of an earlier row in this file.");
    }

    // Administrative roles are appointments, not access. They are granted from
    // the administrator screens, which apply the tier rules; letting a CSV do it
    // would route around them entirely.
    if (FamAdminRole.isAdminRole(row.roleCode())) {
      return invalid(row, district, region, clientNumber,
          "%s is an administrative role. Appoint administrators from the "
              .formatted(row.roleCode())
              + "Delegated admins or Application admins tab, not by upload.");
    }

    if (!kind.needsRole()) {
      return validateAppAdminRow(
          integrationId, environment, row, resolvedUsers, appointed, requester);
    }

    CssRoleOptionDto role = roles.get(row.roleCode().toUpperCase(Locale.ROOT));
    if (role == null) {
      return invalid(row, district, region, clientNumber,
          "No role named %s exists in this application.".formatted(row.roleCode()));
    }

    // ---- scope, in both directions -----------------------------------------
    //
    // A scope on a role that does not take one is the more dangerous mistake of
    // the two: the value would simply be ignored, and the row would grant wider
    // access than the file appears to ask for. It is refused rather than
    // dropped.
    if (!district.isEmpty() && !role.roleTypeDistrict()) {
      return invalid(row, district, region, clientNumber,
          "%s is not granted per district, so the district column must be empty."
              .formatted(row.roleCode()));
    }
    if (!region.isEmpty() && !role.roleTypeRegion()) {
      return invalid(row, district, region, clientNumber,
          "%s is not granted per region, so the region column must be empty."
              .formatted(row.roleCode()));
    }
    if (!clientNumber.isEmpty() && !role.roleTypeClient()) {
      return invalid(row, district, region, clientNumber,
          "%s is not granted per organization, so the organization column must be empty."
              .formatted(row.roleCode()));
    }
    // The other direction: without the scope the grant would name a role no
    // application authorises on, so it is refused rather than granted unscoped.
    if (role.roleTypeDistrict() && district.isEmpty()) {
      return invalid(row, district, region, clientNumber,
          "%s is granted per district. Put a district code in the third column."
              .formatted(row.roleCode()));
    }
    if (role.roleTypeRegion() && region.isEmpty()) {
      return invalid(row, district, region, clientNumber,
          "%s is granted per region. Put a region code in the region column."
              .formatted(row.roleCode()));
    }
    if (role.roleTypeClient() && clientNumber.isEmpty()) {
      return invalid(row, district, region, clientNumber,
          "%s is granted per organization. Put a client number in the fourth column."
              .formatted(row.roleCode()));
    }

    String districtName = null;
    if (!district.isEmpty()) {
      District known = District.fromOrgUnitCode(district).orElse(null);
      if (known == null) {
        return invalid(row, district, region, clientNumber,
            "%s is not a natural resource district code.".formatted(district));
      }
      // Expired districts are kept out of the picker so none can be granted;
      // the file must not be a way around that.
      if (known.isExpired()) {
        return invalid(row, district, region, clientNumber,
            "District %s (%s) has expired and cannot be granted."
                .formatted(district, known.getOrgUnitName()));
      }
      districtName = known.getOrgUnitName();
    }

    String regionName = null;
    if (!region.isEmpty()) {
      Region known = Region.fromRegionCode(region).orElse(null);
      if (known == null) {
        return invalid(row, district, region, clientNumber,
            "%s is not a natural resource region code.".formatted(region));
      }
      // Expired regions are kept out of the picker so none can be granted; the
      // file must not be a way around that.
      if (known.isExpired()) {
        return invalid(row, district, region, clientNumber,
            "Region %s (%s) has expired and cannot be granted."
                .formatted(region, known.getRegionName()));
      }
      regionName = known.getRegionName();
    }

    String clientName = null;
    if (!clientNumber.isEmpty()) {
      ForestClient client = clients.get(clientNumber);
      if (client == null) {
        return invalid(row, district, region, clientNumber,
            "No organization has client number %s.".formatted(clientNumber));
      }
      // Same rule the picker applies: an inactive organisation is findable but
      // refused on selection, so granting one by upload would be a dead end.
      if (!client.active()) {
        return invalid(row, district, region, clientNumber,
            "Organization %s (%s) is not active and cannot be granted."
                .formatted(clientNumber, client.name()));
      }
      clientName = client.name();
    }

    // Which directory the file says this user is in. Optional - blank means
    // "look in both" - but stating it is worth a column: the two directories are
    // searched in turn, and a Business BCeID user therefore costs a failed IDIR
    // lookup on every row before being found. The same username may also exist
    // in both, and naming the directory is the only way to say which is meant.
    UserType stated;
    try {
      stated = parseUserType(row.userType());
    } catch (IllegalArgumentException e) {
      return invalid(row, district, region, clientNumber,
          "%s is not a user type. Use IDIR or BCEID, or leave the column empty."
              .formatted(row.userType()));
    }

    // The directory is the only thing that can say whether a username is a real
    // person, which of the two directories they are in, and - the part CSS needs
    // - what their GUID is.
    /*
        computeIfAbsent would not do: a name nobody answers to resolves to null,
        and the map would then look the lookup up again for every row that
        repeats it - which is exactly the file this is here to make possible.
    */
    String lookupKey = row.userName().toUpperCase(Locale.ROOT)
        + "|" + (stated == null ? "" : stated.name());
    Resolved resolved;
    if (resolvedUsers.containsKey(lookupKey)) {
      resolved = resolvedUsers.get(lookupKey);
    } else {
      resolved = resolve(
          apiInstanceEnvResolver.resolveDirectory(environment), row.userName(), stated);
      resolvedUsers.put(lookupKey, resolved);
    }

    if (resolved == null) {
      return invalid(row, district, region, clientNumber, stated == null
          ? "No IDIR or Business BCeID user is named %s.".formatted(row.userName())
          // Named rather than falling back to the other directory: the file said
          // which one, and quietly granting a different person who happens to
          // share the username is worse than refusing the row.
          : "No %s user is named %s.".formatted(stated.getCode(), row.userName()));
    }

    // The directory answered but had no GUID for them. CSS provisions by GUID
    // and nothing else, so there is nothing to grant against - and a row that
    // went ahead would be granting to nobody while reporting success.
    if (resolved.userGuid() == null || resolved.userGuid().isBlank()) {
      return invalid(row, district, region, clientNumber,
          "%s has no GUID in the directory and cannot be granted access."
              .formatted(row.userName()));
    }

    CssBulkGrantRowDto candidate = new CssBulkGrantRowDto(
        row.lineNumber(), resolved.userGuid(), row.roleCode(),
        resolved.userType(), resolved.userName(), resolved.firstName(), resolved.lastName(),
        resolved.email(), resolved.organization(),
        role.displayName(),
        district.isEmpty() ? null : district, districtName,
        region.isEmpty() ? null : region, regionName,
        clientNumber.isEmpty() ? null : clientNumber, clientName,
        true, false, null);

    /*
        Already held?

        Re-granting is harmless in CSS - the assignment is set membership - but
        it emails the person again to tell them they have been given something
        they already had, and writes an audit row for a change that did not
        happen. A file re-uploaded after a partial run is mostly rows like this,
        so they are shown and skipped rather than applied.

        Compared on the concrete role name, which is what the grant would create:
        the same base role for a different district is a different grant and must
        still go ahead.
    */
    String wouldAssign = CssRoleNaming.buildScopedRoleName(role.name(), scopesFor(
        district, region, clientNumber));

    if (kind == BulkUploadKind.DELEGATED_ADMINS) {
      // The delegation lives on FAM's own integration, against the concrete role
      // it authorises - not on the application, which is what heldRoles reads.
      if (appointed.contains(appointmentKey(resolved.userGuid(), wouldAssign))) {
        return candidate.asAlreadyGranted();
      }
    } else {
      Set<String> held = heldRoles.computeIfAbsent(
          resolved.userGuid().toUpperCase(Locale.ROOT),
          guid -> cssIntegrationService.rolesHeldBy(
              integrationId, environment, resolved.userGuid(), resolved.userType()));

      if (held.contains(wouldAssign)) {
        return candidate.asAlreadyGranted();
      }
    }

    // The same per-row rules the single grant path applies, checked now so the
    // confirmation is honest rather than discovered halfway through applying.
    try {
      authorizationService.forbidSelfGrant(requester, resolved.userGuid(), environment);
      if (kind == BulkUploadKind.APP_ADMINS) {
        authorizationService.requireApplicationAdminManagement(requester);
      } else if (kind == BulkUploadKind.DELEGATED_ADMINS) {
        authorizationService.requireDelegatedAdminManagement(
            requester, integrationId, environment);
      } else {
        authorizationService.requireGrantableRoles(
            requester, integrationId, environment, List.of(role.name()));
      }
      // A Business BCeID uploader may only grant within their own organisation.
      // The apply step enforces this anyway, inside the grant path - checking it
      // here too is what keeps the confirmation honest, rather than showing a row
      // as grantable and refusing it on submit.
      targetOrganizationGuard.requireSameOrganization(
          requester, apiInstanceEnvResolver.resolveDirectory(environment),
          resolved.userType(), resolved.userGuid());
    } catch (FamHttpException e) {
      return withError(candidate, e.getDescription());
    }

    return candidate;
  }

  /** The row's scopes, for building the concrete role name it would assign. */
  private static List<CssRoleNaming.Scope> scopesFor(
      String district, String region, String clientNumber) {

    List<CssRoleNaming.Scope> scopes = new ArrayList<>();
    if (!district.isEmpty()) {
      scopes.add(new CssRoleNaming.Scope(CssRoleNaming.SCOPE_DISTRICT, district));
    }
    if (!region.isEmpty()) {
      scopes.add(new CssRoleNaming.Scope(CssRoleNaming.SCOPE_REGION, region));
    }
    if (!clientNumber.isEmpty()) {
      scopes.add(new CssRoleNaming.Scope(CssRoleNaming.SCOPE_FOREST_CLIENT, clientNumber));
    }
    return scopes;
  }

  private static CssBulkGrantRowDto invalid(
      ParsedRow row, String district, String region, String clientNumber, String error) {
    return CssBulkGrantRowDto.invalid(
        row.lineNumber(), row.userName(), row.roleCode(), district, region, clientNumber, error);
  }

  /**
   * Client numbers are eight characters, zero-padded.
   *
   * <p>Spreadsheets drop leading zeros from anything that looks like a number,
   * so "00001012" reaches us as "1012" whenever the file has been through Excel.
   * Padding here is not a convenience: without it every client-scoped row in
   * such a file would be rejected as unknown.
   */
  private static String padClientNumber(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty() || !trimmed.chars().allMatch(Character::isDigit)) {
      return trimmed;
    }
    return trimmed.length() >= 8 ? trimmed : "0".repeat(8 - trimmed.length()) + trimmed;
  }

  /** One organisation, as the file needs to know it. */
  private record ForestClient(String name, boolean active) {}

  /**
   * Resolves every client number the file mentions, in one upstream call.
   *
   * <p>Per row it would be one Forest Client request each; a two-hundred row
   * file would spend two hundred round trips confirming names nobody disputed.
   * The API takes a list, so the whole file costs one.
   */
  private Map<String, ForestClient> resolveClients(
      List<ParsedRow> parsed, String environment) {

    List<String> numbers = parsed.stream()
        .map(row -> padClientNumber(row.forestClient()))
        .filter(number -> !number.isEmpty())
        .distinct()
        .toList();

    if (numbers.isEmpty()) {
      return Map.of();
    }

    Map<String, ForestClient> byNumber = new LinkedHashMap<>();
    try {
      for (Map<String, Object> found : forestClientIntegrationService.search(
          numbers, numbers.size(), apiInstanceEnvResolver.resolve(environment), true)) {

        String number = String.valueOf(found.get("clientNumber"));
        Object name = found.get("clientName");
        byNumber.put(number, new ForestClient(
            name == null ? number : String.valueOf(name),
            FamConstants.FOREST_CLIENT_STATUS_CODE_ACTIVE
                .equals(String.valueOf(found.get("clientStatusCode")))));
      }
    } catch (RuntimeException e) {
      // Every client-scoped row then reads "no organization has this number",
      // which is wrong but visible - and far better than applying a file whose
      // organisations were never checked.
      log.warn("Could not resolve forest clients for the upload: {}", e.getMessage());
    }
    return byNumber;
  }

  /**
   * Finds the person behind a username, and with them the GUID CSS needs.
   *
   * <p>The GUID is the point as much as the name is. CSS provisions a user by
   * GUID and nothing else, so a file that names people the way people are named
   * has to turn each one into a GUID before anything can be granted - and this
   * lookup, which the row already made in order to show a name on the
   * confirmation, is where that happens. A row whose GUID comes back blank is
   * refused by the caller rather than sent on, because a grant made against no
   * GUID would be a grant made against nobody.
   *
   * <p>When the file named a directory only that one is searched, so a row
   * saying IDIR can never resolve to a Business BCeID user of the same name.
   * When it did not, both are tried in turn - which costs a Business BCeID user
   * a failed IDIR lookup on every row.
   */
  private Resolved resolve(DirectoryEnv directory, String userName, UserType stated) {
    if (stated == null || stated == UserType.IDIR) {
      try {
        Optional<UserLookupIdirUserDto> idir =
            userLookupClient.getIdirDetail(directory, userName);
        if (idir.isPresent()) {
          UserLookupIdirUserDto user = idir.get();
          return new Resolved(UserType.IDIR, user.guid(), user.userId(),
              user.firstName(), user.lastName(), user.email(), null);
        }
      } catch (RuntimeException e) {
        log.warn("IDIR lookup failed for {}: {}", userName, e.getMessage());
      }
    }

    if (stated == null || stated == UserType.BCEID) {
      try {
        Optional<UserLookupBceidUserDto> bceid = userLookupClient.getBusinessBceid(
            directory, UserLookupClient.SearchBy.USER_ID, userName);
        if (bceid.isPresent()) {
          UserLookupBceidUserDto user = bceid.get();
          return new Resolved(UserType.BCEID, user.guid(), user.userId(),
              user.firstName(), user.lastName(), user.email(), user.businessLegalName());
        }
      } catch (RuntimeException e) {
        log.warn("Business BCeID lookup failed for {}: {}", userName, e.getMessage());
      }
    }

    return null;
  }

  /**
   * The user type a row named, or null when the column was left empty.
   *
   * <p>Accepts {@code BCEID} as well as the stored {@code BCEID_BUS}: the column
   * is written by hand, and FAM admits only the business flavour anyway, so
   * demanding the longer form would reject files for no reason a person could
   * see.
   *
   * @throws IllegalArgumentException when the column holds something that is
   *     neither, which the caller turns into a message naming the value
   */
  private static UserType parseUserType(String value) {
    String wanted = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    if (wanted.isEmpty()) {
      return null;
    }
    if (wanted.equals("IDIR")) {
      return UserType.IDIR;
    }
    if (wanted.equals("BCEID") || wanted.equals("BCEID_BUS")
        || wanted.equals("BCEIDBUSINESS")) {
      return UserType.BCEID;
    }
    throw new IllegalArgumentException(value);
  }

  private static CssBulkGrantRowDto withError(CssBulkGrantRowDto row, String error) {
    return new CssBulkGrantRowDto(
        row.lineNumber(), row.userGuid(), row.roleCode(), row.userType(), row.userName(),
        row.firstName(), row.lastName(), row.email(), row.organization(),
        row.roleDisplayName(), row.district(), row.districtName(),
        row.region(), row.regionName(),
        row.forestClientNumber(), row.forestClientName(), false, false, error);
  }

  private record Resolved(
      UserType userType, String userGuid, String userName, String firstName, String lastName,
      String email, String organization) {}

  // ---------------------------------------------------------------------------
  // Parsing
  // ---------------------------------------------------------------------------

  private record ParsedRow(
      int lineNumber, String userName, String userType, String roleCode,
      String district, String forestClient, String region) {}

  /**
   * Splits the file into rows.
   *
   * <p>Six columns: username, user type, role, district, organization, region.
   * The scope columns are blank for a role that is not scoped that way, and a
   * role scoped several ways carries each - one row is one grant, so a person
   * getting a role for three districts is three rows. That keeps the file
   * readable in a spreadsheet, which is where these are actually written.
   *
   * <p><b>Region is last rather than beside district, which is where it belongs
   * to read.</b> The parser is positional, so inserting a column would silently
   * reinterpret every file written before it - organisation numbers would arrive
   * as regions. Appending keeps every existing file valid, which is the same
   * reason trailing columns are optional: a two-column file written before
   * scopes were supported still uploads unchanged.
   */
  private static List<ParsedRow> parse(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }

    List<ParsedRow> rows = new ArrayList<>();
    String[] lines = csv.replace(BOM, "").split("\\R");
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.isEmpty()) {
        continue;
      }
      String[] fields = line.split(",", -1);
      String first = field(fields, 0);

      // A header only counts as one on the first non-blank line, so a role
      // legitimately called something like "ROLE" further down is not dropped.
      if (rows.isEmpty() && isHeader(first, field(fields, 1), field(fields, 2))) {
        continue;
      }
      rows.add(new ParsedRow(i + 1, first, field(fields, 1), field(fields, 2),
          field(fields, 3), field(fields, 4), field(fields, 5)));
    }
    return rows;
  }

  /** Missing trailing columns read as blank rather than as a malformed row. */
  private static String field(String[] fields, int index) {
    return index < fields.length ? fields[index].trim() : "";
  }

  /**
   * Whether the first row names its columns rather than carrying data.
   *
   * <p>Checks the role column in its new position <em>and</em> its old one, so a
   * header written before the user-type column was added is still recognised as
   * one rather than uploaded as a row.
   *
   * <p>The old {@code user_guid} headings are still recognised. The column holds
   * a username now, so such a file will not grant anything - but its header is
   * better skipped than uploaded as a row, which would report a mysterious
   * failure on line 1 on top of the real problem.
   */
  private static boolean isHeader(String first, String second, String third) {
    String a = normalise(first);
    return a.equals("username") || a.equals("userid") || a.equals("user")
        || a.equals("userguid") || a.equals("guid")
        || isRoleHeading(normalise(second)) || isRoleHeading(normalise(third));
  }

  private static boolean isRoleHeading(String value) {
    return value.equals("role") || value.equals("rolecode") || value.equals("rolename");
  }

  private static String normalise(String value) {
    return value.toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "");
  }
}
