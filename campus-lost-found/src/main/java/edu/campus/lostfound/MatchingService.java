package edu.campus.lostfound;

import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
class MatchingService {

    static final List<String> CATEGORIES = List.of("ELECTRONICS", "BAGS", "BOOKS", "KEYS", "CLOTHING", "OTHER");
    static final List<String> LOCATIONS = List.of("LIBRARY", "CAFETERIA", "ACADEMIC_BLOCK", "HOSTEL", "SPORTS_GROUND", "MAIN_GATE", "OTHER");
    static boolean compatible(ItemReport a, ItemReport b) {
        if (a.id != null && a.id.equals(b.id)) return false;
        if (a.kind.equals(b.kind) || !a.category.equals(b.category) || !"OPEN".equals(b.status)) return false;
        ItemReport lost = "LOST".equals(a.kind) ? a : b;
        ItemReport found = "FOUND".equals(a.kind) ? a : b;
        long days = ChronoUnit.DAYS.between(lost.eventDate, found.eventDate);
        return days >= 0 && days <= 14;
    }
    static int score(ItemReport a, ItemReport b) {
        if (!compatible(a,b)) return 0;
        long days = Math.abs(ChronoUnit.DAYS.between(a.eventDate,b.eventDate));
        return 50 + (a.location.equals(b.location) && !"OTHER".equals(a.location) ? 25 : 0)
            + (days <= 1 ? 25 : days <= 7 ? 15 : 5);
    }
    static boolean possibleDuplicate(ItemReport a, ItemReport b) {
        return a.kind.equals(b.kind) && a.category.equals(b.category) && a.location.equals(b.location)
            && a.title.equalsIgnoreCase(b.title)
            && Math.abs(ChronoUnit.DAYS.between(a.eventDate,b.eventDate)) <= 1;
    }
}
