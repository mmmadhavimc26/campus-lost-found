package edu.campus.lostfound;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api")
class Api {
    private final Users users; private final Reports reports; private final Claims claims;
    Api(Users users, Reports reports, Claims claims) { this.users=users; this.reports=reports; this.claims=claims; }
    record ReportInput(@NotBlank @Pattern(regexp="LOST|FOUND") String kind,
                       @NotBlank @Size(min=3,max=100) String title, @NotBlank String category,
                       @NotBlank String location, @NotNull @PastOrPresent LocalDate eventDate,
                       @NotBlank @Size(min=10,max=700) String description) {}
    record ClaimInput(@NotBlank @Size(min=15,max=1500) String privateDetails) {}
    record UserView(Long id, String name, String role) {}
    record ReportView(Long id, String kind, String title, String category, String location, LocalDate eventDate, String description, String status) {}
    record MatchView(ReportView report, int score, String reason) {}
    record ClaimView(Long id, ReportView report, String status, String privateDetails, Instant createdAt) {}
    static ReportView view(ItemReport r) { return new ReportView(r.id,r.kind,r.title,r.category,r.location,r.eventDate,r.description,r.status); }
    private CampusUser current(Principal p) { return users.findByEmail(p.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)); }
    private ItemReport report(Long id) { return reports.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Report not found.")); }

    @GetMapping("/health") Map<String,String> health() { return Map.of("status","UP","milestone","1"); }
    @GetMapping("/csrf") Map<String,String> csrf(CsrfToken token) { return Map.of("token",token.getToken(),"headerName",token.getHeaderName()); }
    @GetMapping("/options") Map<String,Object> options() { return Map.of("categories",MatchingService.CATEGORIES,"locations",MatchingService.LOCATIONS); }
    @GetMapping("/me") UserView me(Principal p) { CampusUser u=current(p); return new UserView(u.id,u.name,"STUDENT"); }
    @GetMapping("/reports") List<ReportView> browse() { return reports.findTop200ByOrderByCreatedAtDesc().stream().map(Api::view).toList(); }
    @GetMapping("/me/reports") List<ReportView> mine(Principal p) { return reports.findByOwnerIdOrderByCreatedAtDesc(current(p).id).stream().map(Api::view).toList(); }
    @PostMapping("/reports") @ResponseStatus(HttpStatus.CREATED)
    Map<String,Object> create(@Valid @RequestBody ReportInput input, Principal p) {
        if (!MatchingService.CATEGORIES.contains(input.category()) || !MatchingService.LOCATIONS.contains(input.location()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a listed category and campus location.");
        ItemReport r=new ItemReport(current(p).id,input);
        List<ReportView> duplicates=reports.findByCategoryAndStatus(r.category,"OPEN").stream()
            .filter(x -> MatchingService.possibleDuplicate(r,x)).map(Api::view).toList();
        reports.save(r);
        return Map.of("report",view(r),"possibleDuplicates",duplicates);
    }
    @GetMapping("/reports/{id}/matches") List<MatchView> matches(@PathVariable Long id, Principal p) {
        ItemReport source=report(id);
        if (!source.ownerId.equals(current(p).id)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"You can request matches for your own reports.");
        return reports.findByCategoryAndStatus(source.category,"OPEN").stream()
            .filter(x -> MatchingService.compatible(source,x))
            .map(x -> new MatchView(view(x),MatchingService.score(source,x),
                "Same category; found within 14 days of loss; " + (source.location.equals(x.location) && !"OTHER".equals(source.location) ? "same campus zone." : "different or unspecified campus zone.")))
            .sorted(Comparator.comparingInt(MatchView::score).reversed().thenComparing(m -> m.report().id())).toList();
    }
    @PostMapping("/reports/{id}/claims") @ResponseStatus(HttpStatus.CREATED) @Transactional
    Map<String,Object> claim(@PathVariable Long id, @Valid @RequestBody ClaimInput input, Principal p) {
        ItemReport r=report(id); CampusUser u=current(p);
        if (!"FOUND".equals(r.kind) || !"OPEN".equals(r.status)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Only open found reports can receive claims.");
        if (r.ownerId.equals(u.id)) throw new ResponseStatusException(HttpStatus.CONFLICT,"You cannot claim an item you reported as found.");
        if (claims.existsByReportIdAndClaimantId(id,u.id)) throw new ResponseStatusException(HttpStatus.CONFLICT,"You already have a pending claim for this item.");
        ItemClaim c=claims.saveAndFlush(new ItemClaim(id,u.id,input.privateDetails()));
        return Map.of("id",c.id,"status",c.status,"message","Claim saved as pending. Staff review is not available yet. This is not approval to collect the item.");
    }
    @GetMapping("/me/claims") List<ClaimView> myClaims(Principal p) {
        return claims.findByClaimantIdOrderByCreatedAtDesc(current(p).id).stream()
            .map(c -> new ClaimView(c.id,view(report(c.reportId)),c.status,c.privateDetails,c.createdAt)).toList();
    }
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<Map<String,String>> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",Objects.requireNonNullElse(e.getReason(),"Request failed.")));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<Map<String,String>> invalid(MethodArgumentNotValidException e) {
        var error=e.getBindingResult().getFieldErrors().stream().findFirst();
        return ResponseEntity.badRequest().body(Map.of("message",error.map(x -> x.getField()+": "+x.getDefaultMessage()).orElse("Invalid input.")));
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<Map<String,String>> unreadable() { return ResponseEntity.badRequest().body(Map.of("message","Check the request fields and date format.")); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String,String>> duplicate() { return ResponseEntity.status(409).body(Map.of("message","This account or claim already exists.")); }
}
