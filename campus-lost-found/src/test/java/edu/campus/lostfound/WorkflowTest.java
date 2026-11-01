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
import org.springframework.security.crypto.password.PasswordEncoder;
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
    @Autowired PasswordEncoder passwords;
    Long finder, alice;
    @BeforeEach void setup() {
        claims.deleteAll(); reports.deleteAll(); users.deleteAll();
        finder=users.save(new CampusUser("Finder","finder@example.test",passwords.encode("safe-password-123"))).id;
        alice=users.save(new CampusUser("Alice","alice@example.test",passwords.encode("safe-password-123"))).id;
        users.save(new CampusUser("Bob","bob@example.test",passwords.encode("safe-password-123")));
    }
    ItemReport report(String kind, Long owner) {
        return reports.save(new ItemReport(owner,new Api.ReportInput(kind,"Black backpack","BAGS","LIBRARY",LocalDate.now().minusDays(1),"A black backpack near the entrance")));
    }
    String evidence() throws Exception { return json.writeValueAsString(Map.of("privateDetails","Hidden violet stitching and a small green notebook inside.")); }
    @Test void competingClaimsStayPendingAndPrivateAndCannotBeRepeated() throws Exception {
        Long id=report("FOUND",finder).id;
        mvc.perform(post("/api/reports/"+id+"/claims").with(user("alice@example.test")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(evidence()))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(post("/api/reports/"+id+"/claims").with(user("bob@example.test")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(evidence()))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/reports/"+id+"/claims").with(user("alice@example.test")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(evidence()))
            .andExpect(status().isConflict());
        mvc.perform(get("/api/me/claims").with(user("alice@example.test")))
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
        mvc.perform(post("/api/reports/"+lost+"/claims").with(user("alice@example.test")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(evidence()))
            .andExpect(status().isConflict());
    }
    @Test void matchesRequireReportOwnershipAndHandleNoMatch() throws Exception {
        Long id=report("LOST",alice).id;
        mvc.perform(get("/api/reports/"+id+"/matches").with(user("alice@example.test")))
            .andExpect(status().isOk()).andExpect(content().json("[]"));
        report("FOUND",finder);
        mvc.perform(get("/api/reports/"+id+"/matches").with(user("alice@example.test")))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].score").value(100));
        mvc.perform(get("/api/reports/"+id+"/matches").with(user("bob@example.test")))
            .andExpect(status().isForbidden());
    }
    @Test void duplicatesAreFlaggedAndRetained() throws Exception {
        report("LOST",alice);
        String body=json.writeValueAsString(new Api.ReportInput("LOST","Black backpack","BAGS","LIBRARY",LocalDate.now().minusDays(1),"A black backpack near the entrance"));
        mvc.perform(post("/api/reports").with(user("alice@example.test")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.possibleDuplicates.length()").value(1));
        assertThat(reports.count()).isEqualTo(2);
    }
    @Test void loginWorksAndPrivateEndpointsAndWritesAreProtected() throws Exception {
        mvc.perform(get("/api/me/claims")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").with(csrf()).param("username","alice@example.test").param("password","wrong"))
            .andExpect(status().isUnauthorized());
        var result=mvc.perform(post("/api/auth/login").with(csrf()).param("username","alice@example.test").param("password","safe-password-123"))
            .andExpect(status().isOk()).andReturn();
        mvc.perform(get("/api/me").session((MockHttpSession)result.getRequest().getSession(false)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Alice"));
    }
    @Test void registrationValidatesPasswordAndDoesNotAcceptRoleEscalation() throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name","New Student","email","new@example.test","password","strong-pass-123"))))
            .andExpect(status().isCreated());
        assertThat(passwords.matches("strong-pass-123",users.findByEmail("new@example.test").orElseThrow().passwordHash)).isTrue();
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Student\",\"email\":\"other@example.test\",\"password\":\"strong-pass-123\",\"role\":\"ADMIN\"}"))
            .andExpect(status().isBadRequest());
    }
    @Test void rejectsFutureDatesAndMissingEvidence() throws Exception {
        String body=json.writeValueAsString(new Api.ReportInput("LOST","Backpack","BAGS","LIBRARY",LocalDate.now().plusDays(2),"A general description"));
        mvc.perform(post("/api/reports").with(user("alice@example.test")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/reports/"+report("FOUND",finder).id+"/claims").with(user("alice@example.test")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"privateDetails\":\"\"}"))
            .andExpect(status().isBadRequest());
    }
}
