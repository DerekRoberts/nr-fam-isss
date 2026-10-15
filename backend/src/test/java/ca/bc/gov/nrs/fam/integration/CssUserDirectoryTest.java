package ca.bc.gov.nrs.fam.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.constants.DirectoryEnv;
import ca.bc.gov.nrs.fam.dto.UserLookupBceidUserDto;
import ca.bc.gov.nrs.fam.dto.UserLookupIdirSearchResult;
import ca.bc.gov.nrs.fam.dto.UserLookupIdirUserDto;
import ca.bc.gov.nrs.fam.integration.CssApiService.CssUserDto;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("CssUserDirectory (people search through the CSS API)")
class CssUserDirectoryTest {

  private static final String GUID_A = "76a4033a7fe24371b2ee1ecb7f41a217";
  private static final String GUID_B = "0123456789abcdef0123456789abcdef";

  @Mock private CssApiService css;
  @InjectMocks private CssUserDirectory directory;

  private static CssUserDto idir(String guid, String login, String first, String last) {
    return new CssUserDto(guid + "@azureidir", first, last, first.toLowerCase() + "@gov.bc.ca",
        Map.of("idir_username", List.of(login), "idir_user_guid", List.of(guid),
            "display_name", List.of(last + ", " + first)));
  }

  @Test
  @DisplayName("a last-name search goes to CSS with that filter and maps each row")
  void lastNameSearchMapsRows() {
    when(css.searchIdpUsers("dev", CssUserDirectory.AZURE_IDIR_PATH, Map.of("lastName", "Gagan")))
        .thenReturn(List.of(idir(GUID_A, "JGAGAN", "James", "Gagan")));

    UserLookupIdirSearchResult result =
        directory.searchIdir(DirectoryEnv.DEV, null, null, "Gagan", 20);

    assertThat(result.totalItems()).isEqualTo(1);
    assertThat(result.pageSize()).isEqualTo(20);
    assertThat(result.items()).singleElement().satisfies(p -> {
      assertThat(p.found()).isTrue();
      assertThat(p.userId()).isEqualTo("JGAGAN");
      assertThat(p.guid()).isEqualTo(GUID_A);
      assertThat(p.firstName()).isEqualTo("James");
      assertThat(p.lastName()).isEqualTo("Gagan");
      assertThat(p.email()).isEqualTo("james@gov.bc.ca");
    });
  }

  @Test
  @DisplayName("first and last name are both sent; the environment name is lower-cased")
  void bothNamesAndEnvironment() {
    when(css.searchIdpUsers("test", CssUserDirectory.AZURE_IDIR_PATH,
        Map.of("firstName", "James", "lastName", "Gagan"))).thenReturn(List.of());

    directory.searchIdir(DirectoryEnv.TEST, null, " James ", "Gagan ", null);

    verify(css).searchIdpUsers("test", CssUserDirectory.AZURE_IDIR_PATH,
        Map.of("firstName", "James", "lastName", "Gagan"));
  }

  @Test
  @DisplayName("a login-only search answers no match without calling CSS")
  void loginOnlySearchIsEmpty() {
    UserLookupIdirSearchResult result =
        directory.searchIdir(DirectoryEnv.DEV, "JGAGAN", null, null, 20);

    assertThat(result.totalItems()).isZero();
    assertThat(result.items()).isEmpty();
    verifyNoInteractions(css);
  }

  @Test
  @DisplayName("a login given with a name narrows the name search, case-insensitively")
  void loginNarrowsNameSearch() {
    when(css.searchIdpUsers(anyString(), anyString(), anyMap())).thenReturn(List.of(
        idir(GUID_A, "JGAGAN", "James", "Gagan"), idir(GUID_B, "AGAGAN", "Ann", "Gagan")));

    UserLookupIdirSearchResult result =
        directory.searchIdir(DirectoryEnv.DEV, "jgagan", null, "Gagan", 20);

    assertThat(result.totalItems()).isEqualTo(1);
    assertThat(result.items()).extracting(UserLookupIdirUserDto::userId).containsExactly("JGAGAN");
  }

  @Test
  @DisplayName("the page size caps the rows returned but the total counts every match")
  void pageSizeCapsRows() {
    when(css.searchIdpUsers(anyString(), anyString(), anyMap())).thenReturn(List.of(
        idir(GUID_A, "JGAGAN", "James", "Gagan"), idir(GUID_B, "AGAGAN", "Ann", "Gagan")));

    UserLookupIdirSearchResult result =
        directory.searchIdir(DirectoryEnv.DEV, null, null, "Gagan", 1);

    assertThat(result.totalItems()).isEqualTo(2);
    assertThat(result.items()).hasSize(1);
  }

  @Test
  @DisplayName("without a GUID attribute the federated username's prefix is the GUID")
  void guidFallsBackToUsernamePrefix() {
    when(css.searchIdpUsers(anyString(), anyString(), anyMap())).thenReturn(List.of(
        new CssUserDto(GUID_A + "@azureidir", "James", "Gagan", null, Map.of())));

    UserLookupIdirSearchResult result =
        directory.searchIdir(DirectoryEnv.DEV, null, null, "Gagan", 20);

    assertThat(result.items()).singleElement().satisfies(p -> {
      assertThat(p.guid()).isEqualTo(GUID_A);
      assertThat(p.userId()).isNull();
    });
  }

  @Test
  @DisplayName("detail by GUID uses the guid filter and accepts only an exact match")
  void detailByGuidIsExact() {
    when(css.searchIdpUsers("prod", CssUserDirectory.AZURE_IDIR_PATH, Map.of("guid", GUID_A)))
        .thenReturn(List.of(idir(GUID_B, "AGAGAN", "Ann", "Gagan"),
            idir(GUID_A, "JGAGAN", "James", "Gagan")));

    Optional<UserLookupIdirUserDto> person =
        directory.getIdirDetailByGuid(DirectoryEnv.PROD, GUID_A.toUpperCase());

    assertThat(person).isPresent();
    assertThat(person.get().userId()).isEqualTo("JGAGAN");
    assertThat(directory.getIdirDetailByGuid(DirectoryEnv.PROD, "  ")).isEmpty();
  }

  @Test
  @DisplayName("detail by login answers no match without calling CSS")
  void detailByLoginIsEmpty() {
    assertThat(directory.getIdirDetail(DirectoryEnv.DEV, "JGAGAN")).isEmpty();
    verify(css, never()).searchIdpUsers(anyString(), anyString(), anyMap());
  }

  @Test
  @DisplayName("Business BCeID is reachable by GUID only, mapped from the bceid_* attributes")
  void businessBceidByGuidOnly() {
    when(css.searchIdpUsers("dev", CssUserDirectory.BUSINESS_BCEID_PATH, Map.of("guid", GUID_B)))
        .thenReturn(List.of(new CssUserDto(GUID_B + "@bceidbusiness", "Pat", "Lee",
            "pat@example.com", Map.of("bceid_username", List.of("PLEE"),
                "bceid_business_guid", List.of("BIZ1"),
                "bceid_business_name", List.of("Lee Forestry Ltd")))));

    Optional<UserLookupBceidUserDto> byGuid = directory.getBusinessBceid(
        DirectoryEnv.DEV, UserLookupClient.SearchBy.USER_GUID, GUID_B);
    Optional<UserLookupBceidUserDto> byLogin = directory.getBusinessBceid(
        DirectoryEnv.DEV, UserLookupClient.SearchBy.USER_ID, "PLEE");

    assertThat(byGuid).isPresent().get().satisfies(p -> {
      assertThat(p.userId()).isEqualTo("PLEE");
      assertThat(p.guid()).isEqualTo(GUID_B);
      assertThat(p.businessGuid()).isEqualTo("BIZ1");
      assertThat(p.businessLegalName()).isEqualTo("Lee Forestry Ltd");
    });
    assertThat(byLogin).isEmpty();
    verify(css, never()).searchIdpUsers(anyString(), anyString(), eq(Map.of("guid", "PLEE")));
  }

  @Test
  @DisplayName("configured whenever the CSS API itself is, for every environment")
  void configuredFollowsCss() {
    when(css.isConfigured()).thenReturn(true);
    assertThat(directory.isConfigured(DirectoryEnv.DEV)).isTrue();
    assertThat(directory.isConfigured(DirectoryEnv.PROD)).isTrue();
  }
}
