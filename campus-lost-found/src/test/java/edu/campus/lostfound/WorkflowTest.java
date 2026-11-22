package edu.campus.lostfound;

import java.time.LocalDate;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.seed-demo=false"})
@AutoConfigureMockMvc
class WorkflowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Users users;
    @Autowired Reports reports;
    @Autowired Claims claims;
    Long finder, mayank;
    @BeforeEach void setup() {
        claims.deleteAll(); reports.deleteAll(); users.deleteAll();
        finder=users.save(new CampusUser("Finder","finder@example.test","UNUSED")).id;
        mayank=users.save(new CampusUser("Mayank","mayank@example.test","UNUSED")).id;
        users.save(new CampusUser("Bob","bob@example.test","UNUSED"));
    }
    ItemReport report(String kind, Long owner) {
        return reports.save(new ItemReport(owner,new Api.ReportInput(kind,"Black backpack","BAGS","BACKPACK","LIBRARY",LocalDate.now().minusDays(1),"A black backpack near the entrance")));
    }
    String evidence() throws Exception { return json.writeValueAsString(Map.of("privateDetails","Hidden violet stitching and a small green notebook inside.")); }
    @Test void competingClaimsStayPendingAndPrivateAndCannotBeRepeated() throws Exception {
        Long id=report("FOUND",finder).id;
        mvc.perform(post("/api/reports/"+id+"/claims").with(user("mayank@example.test")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(evidence()))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(post("/api/reports/"+id+"/claims").with(user("bob@example.test")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(evidence()))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/reports/"+id+"/claims").with(user("mayank@example.test")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(evidence()))
            .andExpect(status().isConflict());
        mvc.perform(get("/api/me/claims").with(user("mayank@example.test")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].privateDetails").exists());
        mvc.perform(get("/api/me/claims").with(user("finder@example.test")))
            .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/reports")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].privateDetails").doesNotExist()).andExpect(jsonPath("$[0].ownerId").doesNotExist())
            .andExpect(jsonPath("$[0].email").doesNotExist());
        assertThat(claims.count()).isEqualTo(2);
        assertThat(reports.findById(id).orElseThrow().status).isEqualTo("OPEN");
    }
    @Test void finderCannotClaimOwnItemAndLostReportsCannotBeClaimed() throws Exception {
        Long found=report("FOUND",finder).id;
        Long lost=report("LOST",finder).id;
        mvc.perform(post("/api/reports/"+found+"/claims").with(user("finder@example.test")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(evidence()))
            .andExpect(status().isConflict());
        mvc.perform(post("/api/reports/"+lost+"/claims").with(user("mayank@example.test")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(evidence()))
            .andExpect(status().isConflict());
    }
    @Test void matchesRequireReportOwnershipAndHandleNoMatch() throws Exception {
        Long id=report("LOST",mayank).id;
        mvc.perform(get("/api/reports/"+id+"/matches").with(user("mayank@example.test")))
            .andExpect(status().isOk()).andExpect(content().json("[]"));
        report("FOUND",finder);
        mvc.perform(get("/api/reports/"+id+"/matches").with(user("mayank@example.test")))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].score").value(100));
        mvc.perform(get("/api/reports/"+id+"/matches").with(user("bob@example.test")))
            .andExpect(status().isForbidden());
    }
    @Test void duplicatesAreFlaggedAndRetained() throws Exception {
        report("LOST",mayank);
        String body=json.writeValueAsString(new Api.ReportInput("LOST","Black backpack","BAGS","BACKPACK","LIBRARY",LocalDate.now().minusDays(1),"A black backpack near the entrance"));
        mvc.perform(post("/api/reports").with(user("mayank@example.test")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.possibleDuplicates.length()").value(1));
        assertThat(reports.count()).isEqualTo(2);
    }
    @Test void demoLoginPersistsAndLogoutClearsTheSession() throws Exception {
        mvc.perform(get("/api/me/claims")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/demo").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"mayank\"}"))
            .andExpect(status().isForbidden());
        var result=mvc.perform(post("/api/auth/demo").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"account\":\"mayank\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("STUDENT")).andReturn();
        var session=(MockHttpSession)result.getRequest().getSession(false);
        mvc.perform(get("/api/me").session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Mayank (demo student)"));
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }
    @Test void demoLoginRejectsArbitraryAccountsAndRoleInjection() throws Exception {
        mvc.perform(get("/api/auth/demo-accounts"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].key").value("mayank"))
            .andExpect(jsonPath("$[0].name").value("Mayank (demo student)"))
            .andExpect(jsonPath("$[0].email").doesNotExist())
            .andExpect(jsonPath("$[1].key").value("bob"))
            .andExpect(jsonPath("$[1].name").value("Test (demo student)"))
            .andExpect(jsonPath("$[1].email").doesNotExist());
        mvc.perform(post("/api/auth/demo").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"account\":\"finder\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/demo").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"account\":\"admin\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/demo").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"account\":\"mayank\",\"role\":\"ADMIN\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register").with(user("mayank@example.test")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").with(user("mayank@example.test")).with(csrf()))
            .andExpect(status().isForbidden());
    }
    @Test void switchingDemoStudentsUsesDistinctStableAccounts() throws Exception {
        var first=mvc.perform(post("/api/auth/demo").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"account\":\"mayank\"}"))
            .andExpect(status().isOk()).andReturn();
        Long mayankId=json.readTree(first.getResponse().getContentAsString()).get("id").asLong();
        var session=(MockHttpSession)first.getRequest().getSession(false);
        var second=mvc.perform(post("/api/auth/demo").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"account\":\"bob\"}"))
            .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(second.getResponse().getContentAsString()).get("id").asLong()).isNotEqualTo(mayankId);
        mvc.perform(get("/api/me").session((MockHttpSession)second.getRequest().getSession(false)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Test (demo student)"));
        var again=mvc.perform(post("/api/auth/demo").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"account\":\"mayank\"}"))
            .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(again.getResponse().getContentAsString()).get("id").asLong()).isEqualTo(mayankId);
    }
    @Test void existingBobRecordNameIsUpdatedToTest() throws Exception {
        CampusUser existing = users.findByEmail("bob@demo.invalid").orElseGet(() ->
            users.saveAndFlush(new CampusUser("Bob (demo student)", "bob@demo.invalid", "UNUSED"))
        );
        existing.name = "Old Bob Name";
        users.saveAndFlush(existing);

        mvc.perform(post("/api/auth/demo").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"account\":\"bob\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Test (demo student)"));

        CampusUser updated = users.findByEmail("bob@demo.invalid").orElseThrow();
        assertThat(updated.name).isEqualTo("Test (demo student)");
        assertThat(updated.id).isEqualTo(existing.id);
    }
    @Test void existingFinderReportsAndClaimsRemainAssociatedWhenFinderIsNotSelectable() throws Exception {
        CampusUser oldFinder = users.saveAndFlush(new CampusUser("Finder (demo student)", "finder@demo.invalid", "UNUSED"));
        ItemReport report = reports.saveAndFlush(new ItemReport(oldFinder.id,
            new Api.ReportInput("FOUND", "Vintage Camera", "ELECTRONICS", "LIBRARY", LocalDate.now().minusDays(1), "Found in reading room.")));
        CampusUser claimant = users.saveAndFlush(new CampusUser("Claimant", "claimant@demo.invalid", "UNUSED"));
        ItemClaim claim = claims.saveAndFlush(new ItemClaim(report.id, claimant.id, "Silver body, black strap."));

        mvc.perform(post("/api/auth/demo").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"account\":\"finder\"}"))
            .andExpect(status().isBadRequest());

        ItemReport fetchedReport = reports.findById(report.id).orElseThrow();
        assertThat(fetchedReport.ownerId).isEqualTo(oldFinder.id);

        ItemClaim fetchedClaim = claims.findById(claim.id).orElseThrow();
        assertThat(fetchedClaim.reportId).isEqualTo(report.id);
        assertThat(fetchedClaim.claimantId).isEqualTo(claimant.id);
        assertThat(claims.findByClaimantIdOrderByCreatedAtDesc(claimant.id)).hasSize(1);
        assertThat(reports.findByOwnerIdOrderByCreatedAtDesc(oldFinder.id)).hasSize(1);
    }
    @Test void demoDataSeedsUnderBobAndDoesNotFail() throws Exception {
        claims.deleteAll(); reports.deleteAll();
        DemoAccounts demoAccounts = new DemoAccounts(users);
        DemoData demoData = new DemoData(demoAccounts, reports);
        demoData.run(null);

        assertThat(reports.count()).isEqualTo(3);
        CampusUser bob = users.findByEmail("bob@demo.invalid").orElseThrow();
        for (ItemReport r : reports.findAll()) {
            assertThat(r.ownerId).isEqualTo(bob.id);
        }
    }
    @Test void rejectsFutureDatesAndMissingEvidence() throws Exception {
        String body=json.writeValueAsString(new Api.ReportInput("LOST","Backpack","BAGS","LIBRARY",LocalDate.now().plusDays(2),"A general description"));
        mvc.perform(post("/api/reports").with(user("mayank@example.test")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/reports/"+report("FOUND",finder).id+"/claims").with(user("mayank@example.test")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"privateDetails\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test void expiredSessionRejectsTheOldTokenAndAllowsClaimAfterDemoReentry() throws Exception {
        Long id=report("FOUND",finder).id;
        var initial=mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andReturn();
        var originalSession=(MockHttpSession)initial.getRequest().getSession(false);
        var initialToken=json.readTree(initial.getResponse().getContentAsString());
        mvc.perform(post("/api/auth/demo").session(originalSession)
            .header(initialToken.get("headerName").asText(),initialToken.get("token").asText())
            .contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"mayank\"}"))
            .andExpect(status().isOk());
        var signedIn=mvc.perform(get("/api/csrf").session(originalSession)).andReturn();
        var oldToken=json.readTree(signedIn.getResponse().getContentAsString());
        originalSession.invalidate();

        var fresh=mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andReturn();
        var newSession=(MockHttpSession)fresh.getRequest().getSession(false);
        var newToken=json.readTree(fresh.getResponse().getContentAsString());
        mvc.perform(post("/api/reports/"+id+"/claims").session(newSession)
            .header(oldToken.get("headerName").asText(),oldToken.get("token").asText())
            .contentType(MediaType.APPLICATION_JSON).content(evidence()))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        assertThat(claims.count()).isZero();
        mvc.perform(post("/api/reports/"+id+"/claims").session(newSession)
            .header(newToken.get("headerName").asText(),newToken.get("token").asText())
            .contentType(MediaType.APPLICATION_JSON).content(evidence()))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
        assertThat(claims.count()).isZero();

        mvc.perform(post("/api/auth/demo").session(newSession)
            .header(newToken.get("headerName").asText(),newToken.get("token").asText())
            .contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"mayank\"}"))
            .andExpect(status().isOk());
        var restored=mvc.perform(get("/api/csrf").session(newSession)).andReturn();
        var restoredToken=json.readTree(restored.getResponse().getContentAsString());
        mvc.perform(post("/api/reports/"+id+"/claims").session(newSession)
            .header(restoredToken.get("headerName").asText(),restoredToken.get("token").asText())
            .contentType(MediaType.APPLICATION_JSON).content(evidence()))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(claims.count()).isEqualTo(1);
    }

    @Test void optionsReturnsCategoriesSubtypesAndLocations() throws Exception {
        mvc.perform(get("/api/options"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.categories.length()").value(6))
            .andExpect(jsonPath("$.subtypesByCategory.ELECTRONICS.length()").value(8))
            .andExpect(jsonPath("$.subtypesByCategory.BAGS.length()").value(6))
            .andExpect(jsonPath("$.locations.length()").value(7));
    }

    @Test void rejectsInvalidSubtypeForCategory() throws Exception {
        String body = json.writeValueAsString(new Api.ReportInput(
            "LOST", "ThinkPad Laptop", "ELECTRONICS", "BACKPACK", "LIBRARY", LocalDate.now(), "A black laptop left in study room."
        ));
        mvc.perform(post("/api/reports").with(user("mayank@example.test")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
    }

    @Test void acceptsValidSubtypeOnReportCreation() throws Exception {
        String body = json.writeValueAsString(new Api.ReportInput(
            "LOST", "ThinkPad Laptop", "ELECTRONICS", "LAPTOP", "LIBRARY", LocalDate.now(), "A black laptop left in study room."
        ));
        mvc.perform(post("/api/reports").with(user("mayank@example.test")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.report.subtype").value("LAPTOP"));
    }

    @Test void claimEligibilityRulesEnforced() throws Exception {
        Long lostReportId = report("LOST", mayank).id;
        Long foundReportId = report("FOUND", finder).id;

        // 1. Direct claim on a LOST report is rejected with 409
        mvc.perform(post("/api/reports/" + lostReportId + "/claims")
                .with(user("bob@example.test")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(evidence()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Only open found reports can receive claims."));

        // 2. Student cannot claim their own FOUND report (rejected with 409)
        mvc.perform(post("/api/reports/" + foundReportId + "/claims")
                .with(user("finder@example.test")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(evidence()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("You cannot claim an item you reported as found."));

        // 3. Student who submitted a LOST report CAN claim another student's matching FOUND report (201)
        mvc.perform(post("/api/reports/" + foundReportId + "/claims")
                .with(user("mayank@example.test")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(evidence()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"));

        // 4. Cancelled FOUND report cannot receive claims (409)
        ItemReport cancelledFound = reports.save(new ItemReport(finder,
            new Api.ReportInput("FOUND", "Keys found", "KEYS", "KEYRING", "CAFETERIA", LocalDate.now(), "Keys near table")));
        cancelledFound.status = "CANCELLED";
        reports.save(cancelledFound);

        mvc.perform(post("/api/reports/" + cancelledFound.id + "/claims")
                .with(user("mayank@example.test")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(evidence()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Only open found reports can receive claims."));
    }

    @Test void editOwnReportLifecycleAndRestrictions() throws Exception {
        ItemReport original = report("LOST", mayank);
        var originalCreatedAt = original.createdAt;

        // 1. Cannot edit another student's report (403)
        String editPayload = json.writeValueAsString(new Api.ReportEditInput(
            "Updated Backpack", "BAGS", "BACKPACK", "CAFETERIA", LocalDate.now(), "Updated backpack description"
        ));
        mvc.perform(put("/api/reports/" + original.id)
                .with(user("bob@example.test")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(editPayload))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("You can edit only your own reports."));

        // 2. Cannot edit with invalid category, subtype, or future date (400)
        String invalidSubtype = json.writeValueAsString(new Api.ReportEditInput(
            "Updated Backpack", "BAGS", "LAPTOP", "CAFETERIA", LocalDate.now(), "Updated backpack description"
        ));
        mvc.perform(put("/api/reports/" + original.id)
                .with(user("mayank@example.test")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(invalidSubtype))
            .andExpect(status().isBadRequest());

        String futureDate = json.writeValueAsString(new Api.ReportEditInput(
            "Updated Backpack", "BAGS", "BACKPACK", "CAFETERIA", LocalDate.now().plusDays(2), "Updated backpack description"
        ));
        mvc.perform(put("/api/reports/" + original.id)
                .with(user("mayank@example.test")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(futureDate))
            .andExpect(status().isBadRequest());

        // 3. Owner can edit their own open report (200)
        mvc.perform(put("/api/reports/" + original.id)
                .with(user("mayank@example.test")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(editPayload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(original.id))
            .andExpect(jsonPath("$.title").value("Updated Backpack"))
            .andExpect(jsonPath("$.location").value("CAFETERIA"))
            .andExpect(jsonPath("$.kind").value("LOST"));

        ItemReport updated = reports.findById(original.id).orElseThrow();
        assertThat(updated.title).isEqualTo("Updated Backpack");
        assertThat(updated.location).isEqualTo("CAFETERIA");
        assertThat(updated.kind).isEqualTo("LOST");
        assertThat(updated.ownerId).isEqualTo(mayank);
        assertThat(updated.createdAt.toEpochMilli()).isEqualTo(originalCreatedAt.toEpochMilli());

        // 4. Report with existing claims cannot be edited (409)
        ItemReport foundItem = report("FOUND", finder);
        claims.save(new ItemClaim(foundItem.id, mayank, "Blue strap with brass buckle"));
        String editFound = json.writeValueAsString(new Api.ReportEditInput(
            "Modified Title", "BAGS", "BACKPACK", "LIBRARY", LocalDate.now(), "Modified description of the item"
        ));
        mvc.perform(put("/api/reports/" + foundItem.id)
                .with(user("finder@example.test")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(editFound))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Reports with existing claims cannot be edited."));

        // 5. Cannot edit a cancelled report (409)
        updated.status = "CANCELLED";
        reports.save(updated);
        mvc.perform(put("/api/reports/" + updated.id)
                .with(user("mayank@example.test")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(editPayload))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Only open reports can be edited."));
    }

    @Test void softDeleteReportLifecycleAndNoticeboardExclusion() throws Exception {
        ItemReport report = report("FOUND", finder);
        Long id = report.id;

        // Existing claim on the report
        ItemClaim claim = claims.save(new ItemClaim(id, mayank, "Private owner details for test item"));

        // 1. Cannot delete another student's report (403)
        mvc.perform(delete("/api/reports/" + id)
                .with(user("bob@example.test")).with(csrf()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("You can delete only your own reports."));

        // 2. Owner can soft-delete their own report (204)
        mvc.perform(delete("/api/reports/" + id)
                .with(user("finder@example.test")).with(csrf()))
            .andExpect(status().isNoContent());

        // 3. Status is CANCELLED in DB
        ItemReport softDeleted = reports.findById(id).orElseThrow();
        assertThat(softDeleted.status).isEqualTo("CANCELLED");

        // 4. Cancelled report disappears from active noticeboard (/api/reports)
        mvc.perform(get("/api/reports"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == " + id + ")]").doesNotExist());

        // 5. Cancelled report still visible in owner's My Reports with CANCELLED status
        mvc.perform(get("/api/me/reports").with(user("finder@example.test")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == " + id + ")].status").value("CANCELLED"));

        // 6. Cancelled report does not appear as candidate match for another report
        ItemReport lostItem = report("LOST", mayank);
        mvc.perform(get("/api/reports/" + lostItem.id + "/matches").with(user("mayank@example.test")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.report.id == " + id + ")]").doesNotExist());

        // 7. Cannot delete an already cancelled report (409)
        mvc.perform(delete("/api/reports/" + id)
                .with(user("finder@example.test")).with(csrf()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Report is already inactive."));

        // 8. Existing claim remains in DB intact
        assertThat(claims.findById(claim.id)).isPresent();
        assertThat(claims.findById(claim.id).get().reportId).isEqualTo(id);
    }
}
