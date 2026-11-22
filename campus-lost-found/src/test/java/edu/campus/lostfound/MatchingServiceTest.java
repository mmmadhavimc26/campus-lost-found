package edu.campus.lostfound;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MatchingServiceTest {

    ItemReport report(String kind, String category, String subtype, String location, LocalDate date, String title, String description) {
        return new ItemReport(1L, new Api.ReportInput(kind, title, category, subtype, location, date, description));
    }

    @Test
    void scenario1_genuineStrongMatch() {
        var baseDate = LocalDate.now().minusDays(10);
        var lost = report("LOST", "BAGS", "BACKPACK", "LIBRARY", baseDate,
            "Black backpack", "Black backpack violet keychain");
        var found = report("FOUND", "BAGS", "BACKPACK", "LIBRARY", baseDate.plusDays(1),
            "Found black backpack", "Found black backpack on bench");

        var match = MatchingService.evaluate(lost, found);
        assertThat(match.matchedTokens()).isEqualTo(2); // {"backpack", "black"}
        assertThat(match.jaccard()).isEqualTo(0.40);
        assertThat(match.score()).isEqualTo(85);
        assertThat(match.classification()).isEqualTo("Strong");
        assertThat(MatchingService.compatible(lost, found)).isTrue();
    }

    @Test
    void scenario2_genuinePossibleMatchWithZoneDifference() {
        var baseDate = LocalDate.now().minusDays(10);
        var lost = report("LOST", "ELECTRONICS", "HEADPHONES_EARBUDS", "CAFETERIA", baseDate,
            "Sony WH1000XM4", "Sony WH1000XM4 black headset cafeteria");
        var found = report("FOUND", "ELECTRONICS", "HEADPHONES_EARBUDS", "HOSTEL", baseDate.plusDays(3),
            "Sony device", "Found Sony black audio device hostel bench");

        var match = MatchingService.evaluate(lost, found);
        assertThat(match.matchedTokens()).isEqualTo(2); // {"black", "sony"}
        assertThat(match.score()).isEqualTo(53);
        assertThat(match.classification()).isEqualTo("Possible");
        assertThat(MatchingService.compatible(lost, found)).isTrue();
    }

    @Test
    void scenario3_zeroTextualEvidenceHardRejection() {
        var baseDate = LocalDate.now().minusDays(5);
        var lost = report("LOST", "BAGS", "BACKPACK", "LIBRARY", baseDate,
            "Black gym gear", "Black gym gear locker");
        var found = report("FOUND", "BAGS", "BACKPACK", "LIBRARY", baseDate,
            "Floral pink satchel", "Floral pink satchel fountain");

        var match = MatchingService.evaluate(lost, found);
        assertThat(match.matchedTokens()).isZero();
        assertThat(match.score()).isZero();
        assertThat(match.classification()).isEqualTo("DISCARDED");
        assertThat(MatchingService.compatible(lost, found)).isFalse();
    }

    @Test
    void scenario4_contextualSynonymMatch() {
        var baseDate = LocalDate.now().minusDays(12);
        var lost = report("LOST", "OTHER", "WATER_BOTTLE", "SPORTS_GROUND", baseDate,
            "Metal thermos", "Metal thermos bottle");
        var found = report("FOUND", "OTHER", "WATER_BOTTLE", "SPORTS_GROUND", baseDate.plusDays(8),
            "Stainless bottle", "Steel reusable bottle near turf");

        var match = MatchingService.evaluate(lost, found);
        assertThat(match.matchedTokens()).isEqualTo(1); // {"bottle"}, thermos -> bottle
        assertThat(match.score()).isEqualTo(64);
        assertThat(match.classification()).isEqualTo("Possible");
        assertThat(MatchingService.compatible(lost, found)).isTrue();
    }

    @Test
    void scenario5_bothReportsGenericSubtype() {
        var baseDate = LocalDate.now().minusDays(5);
        var lost = report("LOST", "BAGS", "OTHER_BAG", "LIBRARY", baseDate,
            "Blue canvas pouch", "Blue canvas pouch metal zipper");
        var found = report("FOUND", "BAGS", "OTHER_BAG", "LIBRARY", baseDate.plusDays(1),
            "Found blue pouch", "Found blue nylon pouch zipper library");

        var match = MatchingService.evaluate(lost, found);
        assertThat(match.matchedTokens()).isEqualTo(3); // {"blue", "pouch", "zipper"}
        assertThat(match.score()).isEqualTo(71);
        assertThat(match.classification()).isEqualTo("Possible");
        assertThat(MatchingService.compatible(lost, found)).isTrue();
    }

    @Test
    void scenario6_incompatibleSpecificSubtypesHardRejected() {
        var baseDate = LocalDate.now().minusDays(5);
        var lost = report("LOST", "ELECTRONICS", "LAPTOP", "LIBRARY", baseDate,
            "Dell Inspiron", "Dell Inspiron laptop silver");
        var found = report("FOUND", "ELECTRONICS", "SMARTPHONE", "LIBRARY", baseDate,
            "iPhone 14", "iPhone 14 mobile silver");

        var match = MatchingService.evaluate(lost, found);
        assertThat(match.score()).isZero();
        assertThat(match.classification()).isEqualTo("DISCARDED");
        assertThat(MatchingService.compatible(lost, found)).isFalse();
    }

    @Test
    void scenario7_chronologicalInvalidationOrderInvariant() {
        var nov10 = LocalDate.of(2026, 11, 10);
        var nov5 = LocalDate.of(2026, 11, 5);
        var lost = report("LOST", "BOOKS", "TEXTBOOK", "LIBRARY", nov10,
            "Calculus textbook", "Calculus textbook blue cover");
        var found = report("FOUND", "BOOKS", "TEXTBOOK", "LIBRARY", nov5,
            "Calculus textbook", "Calculus textbook blue cover");

        assertThat(MatchingService.score(lost, found)).isZero();
        assertThat(MatchingService.score(found, lost)).isZero();
        assertThat(MatchingService.compatible(lost, found)).isFalse();
        assertThat(MatchingService.compatible(found, lost)).isFalse();
    }

    @Test
    void scenario8_multipleTyposWithMaxCardinalityBipartiteMatching() {
        var baseDate = LocalDate.now().minusDays(2);
        var lost = report("LOST", "BAGS", "BACKPACK", "LIBRARY", baseDate,
            "Samsonit bakpack", "Blck samsonit bakpack purpl sliver strap");
        var found = report("FOUND", "BAGS", "BACKPACK", "LIBRARY", baseDate,
            "Samsonite backpack", "Black samsonite backpack purple silver strap");

        var match = MatchingService.evaluate(lost, found);
        assertThat(match.matchedTokens()).isEqualTo(6);
        assertThat(match.jaccard()).isEqualTo(1.0);
        assertThat(match.score()).isEqualTo(100);
        assertThat(match.classification()).isEqualTo("Strong");
    }

    @Test
    void scenario9_protectedConfusableWordsBlock() {
        var baseDate = LocalDate.now().minusDays(2);
        var lost = report("LOST", "OTHER", "MISC", "CAFETERIA", baseDate,
            "Glass bottle", "Glass bottle with green sleeve");
        var found = report("FOUND", "OTHER", "MISC", "CAFETERIA", baseDate,
            "Card game", "Card game battle deck");

        var match = MatchingService.evaluate(lost, found);
        assertThat(match.matchedTokens()).isZero(); // bottle <-> battle is blocked
        assertThat(match.score()).isZero();
        assertThat(match.classification()).isEqualTo("DISCARDED");
    }

    @Test
    void scenario10_fourLetterWordTypoBoundaries() {
        var baseDate = LocalDate.now().minusDays(2);
        var lost = report("LOST", "ELECTRONICS", "LAPTOP", "LIBRARY", baseDate,
            "Dall laptop", "Dall laptop sone speaker");
        var found = report("FOUND", "ELECTRONICS", "LAPTOP", "LIBRARY", baseDate,
            "Dell laptop", "Dell laptop sony speaker");

        var match = MatchingService.evaluate(lost, found);
        assertThat(match.matchedTokens()).isEqualTo(2); // laptop, speaker exact matches; dall and sone rejected
        assertThat(match.score()).isEqualTo(83);
        assertThat(match.classification()).isEqualTo("Strong");
    }

    @Test
    void scenario11_insufficientEvidencePreventsInflatedClassification() {
        var baseDate = LocalDate.now().minusDays(2);
        var lost = report("LOST", "BAGS", "BACKPACK", "LIBRARY", baseDate,
            "Heavy trekking rucksack", "Heavy black trekking rucksack straps waterproof cover");
        var found = report("FOUND", "BAGS", "BACKPACK", "LIBRARY", baseDate,
            "School bag", "Found black school bag front pocket pencil pouch");

        var match = MatchingService.evaluate(lost, found);
        assertThat(match.matchedTokens()).isEqualTo(1); // {"black"}
        assertThat(match.jaccard()).isLessThan(0.10);
        assertThat(match.score()).isEqualTo(49); // Capped to Weak band maximum
        assertThat(match.classification()).isEqualTo("Weak");
    }

    @Test
    void damerauLevenshteinExactDistanceAndTransposition() {
        // pro vs pod: substitution 'r'->'o', 'o'->'d' => distance 2, normalized sim 0.3333
        assertThat(MatchingService.damerauLevenshtein("pro", "pod")).isEqualTo(2);
        double simProPod = 1.0 - (2.0 / 3.0);
        assertThat(Math.abs(simProPod - 0.3333)).isLessThan(0.001);

        // Adjacent transposition: earphones vs earphonse => distance 1
        assertThat(MatchingService.damerauLevenshtein("earphones", "earphonse")).isEqualTo(1);
    }

    @Test
    void fourLetterWordBoundaryFilters() {
        // Substitutions and transpositions in 4-letter words have sim = 0.75 < 0.80 => rejected
        assertThat(MatchingService.tokenSimilarity("dell", "dall")).isZero();
        assertThat(MatchingService.tokenSimilarity("sony", "sone")).isZero();
        assertThat(MatchingService.tokenSimilarity("keys", "kyes")).isZero();

        // Omission to length 3 is rejected by min length constraint
        assertThat(MatchingService.tokenSimilarity("blue", "blu")).isZero();

        // 1-character insertion expands length to 5 with dist 1 => sim 0.80 => matched
        assertThat(MatchingService.tokenSimilarity("sony", "soony")).isEqualTo(0.80);
    }

    @Test
    void contextualSynonymsAreCategoryAware() {
        // In ELECTRONICS: notebook -> laptop
        var elecTokens = MatchingService.tokenizeAndCanonicalize("notebook", "lost notebook", "ELECTRONICS");
        assertThat(elecTokens).contains("laptop");
        assertThat(elecTokens).doesNotContain("notebook");

        // In BOOKS: notebook remains notebook
        var bookTokens = MatchingService.tokenizeAndCanonicalize("notebook", "lost notebook", "BOOKS");
        assertThat(bookTokens).contains("notebook");
        assertThat(bookTokens).doesNotContain("laptop");
    }

    @Test
    void orderInvarianceAndOpenStatusEnforcement() {
        var baseDate = LocalDate.now().minusDays(5);
        var lost = report("LOST", "BAGS", "BACKPACK", "LIBRARY", baseDate, "Backpack", "Black backpack near door");
        var found = report("FOUND", "BAGS", "BACKPACK", "LIBRARY", baseDate.plusDays(1), "Backpack", "Black backpack found near entrance");

        assertThat(MatchingService.score(lost, found)).isEqualTo(MatchingService.score(found, lost));
        assertThat(MatchingService.compatible(lost, found)).isEqualTo(MatchingService.compatible(found, lost));

        // When one report is CLOSED, matching is rejected
        lost.status = "CLOSED";
        assertThat(MatchingService.score(lost, found)).isZero();
        assertThat(MatchingService.compatible(lost, found)).isFalse();
    }

    @Test
    void allTaxonomyCombinationsAndValidation() {
        // Check all 6 categories and their valid subtypes
        for (String cat : MatchingService.CATEGORIES) {
            List<String> subtypes = MatchingService.SUBTYPES_BY_CATEGORY.get(cat);
            assertThat(subtypes).isNotEmpty();
            for (String sub : subtypes) {
                assertThat(MatchingService.isValidSubtype(cat, sub)).isTrue();
            }
        }

        // Cross-category invalid combinations
        assertThat(MatchingService.isValidSubtype("ELECTRONICS", "BACKPACK")).isFalse();
        assertThat(MatchingService.isValidSubtype("BAGS", "LAPTOP")).isFalse();
        assertThat(MatchingService.isValidSubtype("BOOKS", "WATER_BOTTLE")).isFalse();
        assertThat(MatchingService.isValidSubtype("KEYS", "SWEATER")).isFalse();
        assertThat(MatchingService.isValidSubtype("OTHER", "LAPTOP")).isFalse();
        assertThat(MatchingService.isValidSubtype("BAGS", "UNKNOWN_SUBTYPE")).isFalse();
    }

    @Test
    void bipartiteMatchingSolvesGreedyTrappingCase() {
        // Graph where greedy would take 1 edge and strand the other,
        // but max-cardinality two-stage matching takes 2 edges!
        double[][] simMatrix = new double[][] {
            {0.95, 0.85},
            {0.85, 0.0}
        };
        int matched = MatchingService.maxCardinalityMaxWeightMatching(simMatrix, 2, 2);
        assertThat(matched).isEqualTo(2);
    }

    @Test
    void numericAndModelCodesMustMatchExactly() {
        assertThat(MatchingService.tokenSimilarity("1000xm4", "1000xm5")).isZero();
        assertThat(MatchingService.tokenSimilarity("iphone14", "iphone15")).isZero();
        assertThat(MatchingService.tokenSimilarity("1000xm4", "1000xm4")).isEqualTo(1.0);
    }

    @Test
    void emptyKeywordSetsHandling() {
        Set<String> emptyA = MatchingService.tokenizeAndCanonicalize("the near in", "at was on with", "OTHER");
        Set<String> emptyB = MatchingService.tokenizeAndCanonicalize("for please", "item campus room", "OTHER");
        assertThat(emptyA).isEmpty();
        assertThat(emptyB).isEmpty();

        var res = MatchingService.computeTextMatch(emptyA, emptyB);
        assertThat(res.matchedTokens()).isZero();
        assertThat(res.jaccard()).isZero();
        assertThat(res.keywordPoints()).isZero();
    }

    @Test
    void dateBoundariesScoring() {
        var base = LocalDate.now().minusDays(10);
        // 0 days => 20 pts
        var l0 = report("LOST", "BAGS", "BACKPACK", "LIBRARY", base, "Bag", "Black backpack");
        var f0 = report("FOUND", "BAGS", "BACKPACK", "LIBRARY", base, "Bag", "Black backpack");
        assertThat(MatchingService.score(l0, f0)).isEqualTo(100);

        // 7 days => 12 date pts (35 + 20 + 12 + 25 = 92)
        var f7 = report("FOUND", "BAGS", "BACKPACK", "LIBRARY", base.plusDays(7), "Bag", "Black backpack");
        assertThat(MatchingService.score(l0, f7)).isEqualTo(92);

        // 14 days => 5 date pts (35 + 20 + 5 + 25 = 85)
        var f14 = report("FOUND", "BAGS", "BACKPACK", "LIBRARY", base.plusDays(14), "Bag", "Black backpack");
        assertThat(MatchingService.score(l0, f14)).isEqualTo(85);

        // 15 days => 0 (disqualified)
        var f15 = report("FOUND", "BAGS", "BACKPACK", "LIBRARY", base.plusDays(15), "Bag", "Black backpack");
        assertThat(MatchingService.score(l0, f15)).isZero();
    }
}
