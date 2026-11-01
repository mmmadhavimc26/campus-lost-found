package edu.campus.lostfound;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MatchingServiceTest {
    ItemReport item(String kind, String category, String location, LocalDate date) {
        return new ItemReport(1L, new Api.ReportInput(kind,"Black backpack",category,location,date,"General public description"));
    }
    @Test void ranksSameZoneAndCloseDatesHighest() {
        var date=LocalDate.now().minusDays(15);
        var lost=item("LOST","BAGS","LIBRARY",date);
        assertThat(MatchingService.score(lost,item("FOUND","BAGS","LIBRARY",date.plusDays(1)))).isEqualTo(100);
        assertThat(MatchingService.score(lost,item("FOUND","BAGS","CAFETERIA",date.plusDays(3)))).isEqualTo(65);
        assertThat(MatchingService.score(lost,item("FOUND","BAGS","LIBRARY",date.plusDays(14)))).isEqualTo(80);
    }
    @Test void excludesIncompatibleCategoriesDatesAndTypes() {
        var date=LocalDate.now().minusDays(20);
        var lost=item("LOST","BAGS","LIBRARY",date);
        assertThat(MatchingService.score(lost,item("FOUND","KEYS","LIBRARY",date))).isZero();
        assertThat(MatchingService.score(lost,item("FOUND","BAGS","LIBRARY",date.minusDays(1)))).isZero();
        assertThat(MatchingService.score(lost,item("FOUND","BAGS","LIBRARY",date.plusDays(15)))).isZero();
        assertThat(MatchingService.score(lost,item("LOST","BAGS","LIBRARY",date))).isZero();
    }
    @Test void flagsDuplicatesWithoutConflatingOppositeReports() {
        var date=LocalDate.now();
        var lost=item("LOST","BAGS","LIBRARY",date);
        assertThat(MatchingService.possibleDuplicate(lost,item("LOST","BAGS","LIBRARY",date.minusDays(1)))).isTrue();
        assertThat(MatchingService.possibleDuplicate(lost,item("FOUND","BAGS","LIBRARY",date))).isFalse();
    }
    @Test void doesNotTreatUnspecifiedZoneAsKnownSameLocation() {
        var date=LocalDate.now();
        assertThat(MatchingService.score(item("LOST","BAGS","OTHER",date),item("FOUND","BAGS","OTHER",date))).isEqualTo(75);
    }
}
