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
        return reports.save(new ItemReport(owner,new Api.ReportInput(kind,"Black backpack","BAGS","LIBRARY",LocalDate.now().minusDays(1),"A black backpack near the entrance")));
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
        String body=json.writeValueAsString(new Api.ReportInput("LOST","Black backpack","BAGS","LIBRARY",LocalDate.now().minusDays(1),"A black backpack near the entrance"));
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
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].email").doesNotExist());
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
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Bob (demo student)"));
        var again=mvc.perform(post("/api/auth/demo").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"account\":\"mayank\"}"))
            .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(again.getResponse().getContentAsString()).get("id").asLong()).isEqualTo(mayankId);
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
}
