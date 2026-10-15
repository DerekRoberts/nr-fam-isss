package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.dto.CssAdministratorRowDto;
import ca.bc.gov.nrs.fam.dto.CssRoleNaming;
import ca.bc.gov.nrs.fam.dto.CssUserRoleRowDto;
import ca.bc.gov.nrs.fam.dto.UserLookupIdirUserDto;
import ca.bc.gov.nrs.fam.constants.DirectoryEnv;
import ca.bc.gov.nrs.fam.integration.UserDirectory;
import ca.bc.gov.nrs.fam.integration.UserLookupClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Puts names on the assignment rows CSS could only identify by GUID.
 *
 * <p>CSS returns a username, first name, last name and email for anyone who has
 * signed in - those attributes are populated by the sign-in itself. A user
 * created by a grant has never signed in, so CSS holds nothing but
 * {@code <guid>@azureidir}, and the permissions table would show a raw GUID with
 * empty name and email columns.
 *
 * <p>The directory can answer that, given a GUID. So only the rows CSS could not
 * name are looked up, deduplicated by GUID. That was a handful until bulk upload,
 * which grants dozens of people at once who have never signed in.
 *
 * <p><b>Best effort, deliberately.</b> {@link UserLookupClient} otherwise raises
 * an upstream failure rather than returning an empty result, because its usual
 * caller is an administrator searching for somebody and "nobody matched" is a
 * materially different answer from "the directory is unreachable". Here the
 * opposite holds: the assignments are already known and correct, and a directory
 * outage should cost the display of a few names rather than the whole table. So
 * failures are logged and the row is left as CSS returned it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssignmentRowEnrichmentService {

  /**
   * Most distinct users to resolve for one listing.
   *
   * <p>Each is a separate call to a SOAP-backed directory, so an application with
   * a large backlog of never-signed-in users could otherwise turn one page load
   * into thousands of upstream requests. What is skipped is logged rather than
   * dropped silently - a bounded listing that looks complete is worse than one
   * that says it is not.
   *
   * <p><b>It was 25, and a bulk upload walked straight past it.</b> Every person
   * a file names is somebody who has not signed in yet, so a 52-person upload
   * left 27 rows showing {@code <guid>@azureidir} with no name - on every page
   * load, not just the first. The bound is now sized for an upload rather than
   * for the odd hand-granted new starter, and {@link #LOOKUP_CONCURRENCY} and
   * {@link #resolvedByGuid} are what keep that affordable.
   */
  static final int MAX_LOOKUPS = 500;

  /**
   * Directory calls in flight at once for one listing.
   *
   * <p>In turn, fifty users is fifty round trips while somebody waits for the
   * table. All at once, it is fifty simultaneous requests from one page load
   * against a shared directory. Eight keeps the wait to a handful of trips
   * without FAM being the reason the directory is slow for everyone else.
   */
  private static final int LOOKUP_CONCURRENCY = 8;

  /**
   * How long a resolved name is reused.
   *
   * <p>A GUID's owner does not change, and a name rarely does - so this is
   * about how stale a renamed person may look, not whether the row is right.
   * The access shown is always read fresh from CSS; only the label beside it is
   * remembered.
   */
  private static final Duration RESOLVED_TTL = Duration.ofMinutes(15);

  /** Past this many held names, expired ones are swept before adding more. */
  private static final int RESOLVED_SWEEP_THRESHOLD = 5_000;

  private static final String IDIR_DOMAIN = "IDIR";

  private final UserDirectory userLookupClient;

  /**
   * Names already resolved, by directory and GUID.
   *
   * <p>Without it, lifting the cap would make every visit to a large
   * application's users tab fan out to the directory again for the same people.
   * Only answers are held: a GUID the directory did not recognise, or a lookup
   * that failed, is asked again next time, so an outage does not stick.
   */
  private final Map<String, Cached> resolvedByGuid = new ConcurrentHashMap<>();

  private record Cached(UserLookupIdirUserDto user, Instant readAt) {
    boolean isFresh(Instant now) {
      return readAt.plus(RESOLVED_TTL).isAfter(now);
    }
  }

  /**
   * Fill in the names CSS did not supply.
   *
   * <p>Returns the rows unchanged when nothing needs resolving, when the
   * directory is not configured, or when it cannot be reached.
   */
  public List<CssUserRoleRowDto> withResolvedNames(
      DirectoryEnv directory, List<CssUserRoleRowDto> rows) {

    if (!userLookupClient.isConfigured(directory)) {
      return rows;
    }

    Set<String> unresolved = new LinkedHashSet<>();
    for (CssUserRoleRowDto row : rows) {
      guidNeedingLookup(row).ifPresent(unresolved::add);
    }

    if (unresolved.isEmpty()) {
      return rows;
    }

    Map<String, UserLookupIdirUserDto> resolved = lookUp(directory, unresolved);
    if (resolved.isEmpty()) {
      return rows;
    }

    return rows.stream().map(row -> apply(row, resolved)).toList();
  }

  /**
   * The same names, on the administrator listings.
   *
   * <p>Those tables had no enrichment at all, and an administrator appointed by
   * somebody else - rather than by signing in - showed as
   * {@code <guid>@azureidir} with empty name and email columns. A bulk upload
   * makes that the normal case rather than the rare one: it appoints people who
   * have no reason to have signed into the application they now administer.
   *
   * <p>Separate method rather than a shared row interface: the two DTOs are
   * records with different shapes and no common ancestor, and inventing one to
   * share six lines of mapping would be the more expensive change.
   */
  public List<CssAdministratorRowDto> withResolvedAdminNames(
      DirectoryEnv directory, List<CssAdministratorRowDto> rows) {

    if (!userLookupClient.isConfigured(directory)) {
      return rows;
    }

    Set<String> unresolved = new LinkedHashSet<>();
    for (CssAdministratorRowDto row : rows) {
      adminGuidNeedingLookup(row).ifPresent(unresolved::add);
    }

    if (unresolved.isEmpty()) {
      return rows;
    }

    Map<String, UserLookupIdirUserDto> resolved = lookUp(directory, unresolved);
    if (resolved.isEmpty()) {
      return rows;
    }

    return rows.stream().map(row -> applyToAdmin(row, resolved)).toList();
  }

  /**
   * Resolve a set of GUIDs against the directory, capped and best-effort.
   *
   * <p>Shared by both listings so the cap, the cache, the logging and the
   * give-up-on-first-failure rule cannot drift between them.
   *
   * <p><b>The first lookup runs alone.</b> One failure is enough to know the
   * rest will fail the same way, and asking eight at once first would spend
   * eight timeouts learning what one already said. Once the directory has
   * answered, the rest go out {@link #LOOKUP_CONCURRENCY} at a time, and a
   * failure among them stops any that have not started.
   */
  private Map<String, UserLookupIdirUserDto> lookUp(
      DirectoryEnv directory, Set<String> unresolved) {

    Map<String, UserLookupIdirUserDto> resolved = new ConcurrentHashMap<>();
    Instant now = Instant.now();

    List<String> toLookUp = new ArrayList<>();
    for (String guid : unresolved) {
      Cached cached = resolvedByGuid.get(cacheKey(directory, guid));
      if (cached != null && cached.isFresh(now)) {
        resolved.put(guid, cached.user());
      } else {
        toLookUp.add(guid);
      }
    }

    if (toLookUp.size() > MAX_LOOKUPS) {
      log.warn("{} users in this listing have no name in CSS; resolving the first {} against "
          + "the directory and leaving the rest showing their GUID.",
          toLookUp.size(), MAX_LOOKUPS);
      toLookUp = toLookUp.subList(0, MAX_LOOKUPS);
    }
    if (toLookUp.isEmpty()) {
      return resolved;
    }

    if (!lookUpOne(directory, toLookUp.get(0), resolved)) {
      return resolved;
    }

    AtomicBoolean failed = new AtomicBoolean(false);
    Semaphore permits = new Semaphore(LOOKUP_CONCURRENCY);

    // Virtual threads: each lookup spends its life waiting on the directory, and
    // the semaphore rather than the pool is what bounds the load. Closing the
    // executor waits for every lookup that started.
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (String guid : toLookUp.subList(1, toLookUp.size())) {
        executor.submit(() -> {
          permits.acquireUninterruptibly();
          try {
            if (!failed.get() && !lookUpOne(directory, guid, resolved)) {
              failed.set(true);
            }
          } finally {
            permits.release();
          }
        });
      }
    }

    log.debug("Resolved {} of {} unnamed user(s) against the directory.",
        resolved.size(), unresolved.size());
    return resolved;
  }

  /**
   * One directory lookup, remembered when it answers.
   *
   * @return false when the directory could not be reached, so the caller stops
   */
  private boolean lookUpOne(
      DirectoryEnv directory, String guid, Map<String, UserLookupIdirUserDto> resolved) {

    try {
      userLookupClient.getIdirDetailByGuid(directory, guid).ifPresent(user -> {
        resolved.put(guid, user);
        remember(directory, guid, user);
      });
      return true;
    } catch (RuntimeException e) {
      // This runs while somebody waits for a table to render; the names are
      // cosmetic and the rows are already right.
      log.warn("Could not resolve names from the directory; the listing will show GUIDs "
          + "for users who have not signed in yet. Reason: {}", e.getMessage());
      return false;
    }
  }

  private void remember(DirectoryEnv directory, String guid, UserLookupIdirUserDto user) {
    if (resolvedByGuid.size() >= RESOLVED_SWEEP_THRESHOLD) {
      Instant now = Instant.now();
      resolvedByGuid.values().removeIf(cached -> !cached.isFresh(now));
    }
    resolvedByGuid.put(cacheKey(directory, guid), new Cached(user, Instant.now()));
  }

  /** Per directory: the same GUID means nothing across environments. */
  private static String cacheKey(DirectoryEnv directory, String guid) {
    return directory + "|" + guid.toUpperCase(Locale.ROOT);
  }

  /** As {@link #guidNeedingLookup}, for an administrator row. */
  private static Optional<String> adminGuidNeedingLookup(CssAdministratorRowDto row) {
    if (!IDIR_DOMAIN.equals(row.domain())
        || notBlank(row.firstName()) || notBlank(row.lastName())) {
      return Optional.empty();
    }
    return Optional.ofNullable(row.userGuid()).filter(guid -> !guid.isBlank());
  }

  private static CssAdministratorRowDto applyToAdmin(
      CssAdministratorRowDto row, Map<String, UserLookupIdirUserDto> resolved) {

    UserLookupIdirUserDto user = adminGuidNeedingLookup(row)
        .map(resolved::get)
        .orElse(null);

    if (user == null) {
      return row;
    }

    return new CssAdministratorRowDto(
        notBlank(user.userId()) ? user.userId() : row.username(),
        row.userGuid(),
        row.domain(),
        user.firstName(),
        user.lastName(),
        user.email(),
        // Everything about the appointment itself is carried through untouched -
        // this rebuilds a row to correct the name on it, nothing more.
        row.tier(),
        row.roleName(),
        row.delegatedRoleName(),
        row.delegatedRoleDisplayName(),
        row.scopes());
  }

  /**
   * The GUID to look up, when this row is an IDIR user CSS could not name.
   *
   * <p>A row with a name is left alone: CSS is the more current source for
   * somebody who has signed in, and resolving them again would cost a call per
   * user to change nothing.
   *
   * <p>BCeID rows are not resolved here even though the directory could - the
   * organisation rule that governs reading a Business BCeID user has no obvious
   * reading on a row the administrator is already entitled to see, and guessing
   * at it is worse than leaving it.
   */
  private static Optional<String> guidNeedingLookup(CssUserRoleRowDto row) {
    if (!IDIR_DOMAIN.equals(row.domain()) || hasName(row)) {
      return Optional.empty();
    }
    // The row's own GUID. Parsing it back out of `username` worked only while
    // CSS had no name for the person - that is the very condition this method
    // screens for above - but it read a display name for a federated one, which
    // is the same confusion that emptied the BCeID listing.
    return Optional.ofNullable(row.userGuid()).filter(guid -> !guid.isBlank());
  }

  private static boolean hasName(CssUserRoleRowDto row) {
    return notBlank(row.firstName()) || notBlank(row.lastName());
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static CssUserRoleRowDto apply(
      CssUserRoleRowDto row, Map<String, UserLookupIdirUserDto> resolved) {

    UserLookupIdirUserDto user = guidNeedingLookup(row)
        .map(resolved::get)
        .orElse(null);

    if (user == null) {
      return row;
    }

    // The user id is what an administrator recognises - JSMITH rather than a
    // GUID - and is what CSS itself reports once the person has signed in.
    return new CssUserRoleRowDto(
        notBlank(user.userId()) ? user.userId() : row.username(),
        row.userGuid(),
        row.domain(),
        user.firstName(),
        user.lastName(),
        user.email(),
        row.roleName(),
        row.roleDisplayName(),
        row.scopes(),
        // Carried through: these two rebuild a row to correct the name on it,
        // and dropping the expiry would quietly turn a temporary grant into a
        // permanent-looking one on the very rows that needed a lookup.
        row.expiresOn());
  }
}
