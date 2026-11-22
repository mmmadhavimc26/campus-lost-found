package edu.campus.lostfound;

import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
class MatchingService {

    static final List<String> CATEGORIES = List.of("ELECTRONICS", "BAGS", "BOOKS", "KEYS", "CLOTHING", "OTHER");
    static final List<String> LOCATIONS = List.of("LIBRARY", "CAFETERIA", "ACADEMIC_BLOCK", "HOSTEL", "SPORTS_GROUND", "MAIN_GATE", "OTHER");

    static final Map<String, List<String>> SUBTYPES_BY_CATEGORY = Map.of(
        "ELECTRONICS", List.of("LAPTOP", "SMARTPHONE", "TABLET", "CHARGER_CABLE", "HEADPHONES_EARBUDS", "SMARTWATCH", "CALCULATOR", "OTHER_ELECTRONICS"),
        "BAGS", List.of("BACKPACK", "HANDBAG_PURSE", "WALLET", "DUFFEL_BAG", "TOTE_BAG", "OTHER_BAG"),
        "BOOKS", List.of("TEXTBOOK", "NOTEBOOK_PLANNER", "NOVEL", "BINDER_FOLDER", "OTHER_BOOK"),
        "KEYS", List.of("SINGLE_KEY", "KEYRING_BUNDLE", "VEHICLE_KEY", "HOSTEL_ROOM_KEY", "ID_CARD_LANYARD", "OTHER_KEY"),
        "CLOTHING", List.of("JACKET_HOODIE", "SWEATER", "CAP_HAT", "SCARF_GLOVES", "WATCH_JEWELRY", "OTHER_CLOTHING"),
        "OTHER", List.of("WATER_BOTTLE", "UMBRELLA", "GLASSES_CASE", "SPORTS_EQUIPMENT", "STATIONERY", "MISC")
    );

    static final Set<String> STOPWORDS = Set.of(
        "the", "a", "an", "and", "or", "in", "on", "at", "near", "lost", "found", "was", "with", "for", "please", "item", "campus", "entrance", "room"
    );

    static final Map<String, Map<String, String>> CATEGORY_SYNONYMS = Map.of(
        "ELECTRONICS", Map.of(
            "notebook", "laptop",
            "macbook", "laptop",
            "thinkpad", "laptop",
            "phone", "mobile",
            "cellphone", "mobile",
            "smartphone", "mobile"
        ),
        "OTHER", Map.of(
            "flask", "bottle",
            "thermos", "bottle",
            "sipper", "bottle"
        ),
        "BOOKS", Map.of(
            "diary", "planner",
            "register", "planner"
        )
    );

    static final Set<Set<String>> PROTECTED_CONFUSABLE_PAIRS = Set.of(
        Set.of("bottle", "battle"),
        Set.of("white", "write"),
        Set.of("board", "broad"),
        Set.of("break", "bread"),
        Set.of("metal", "medal"),
        Set.of("glass", "grass"),
        Set.of("paper", "piper"),
        Set.of("cable", "table"),
        Set.of("mouse", "house"),
        Set.of("black", "block"),
        Set.of("track", "truck"),
        Set.of("stand", "strand"),
        Set.of("strap", "scrap"),
        Set.of("chair", "chain"),
        Set.of("coach", "couch"),
        Set.of("badge", "barge"),
        Set.of("flash", "flask"),
        Set.of("shirt", "skirt"),
        Set.of("clock", "cloak"),
        Set.of("cover", "covey"),
        Set.of("shade", "spade")
    );

    static boolean isProtectedConfusable(String t1, String t2) {
        return PROTECTED_CONFUSABLE_PAIRS.contains(Set.of(t1, t2));
    }

    static String defaultSubtype(String category) {
        return switch (category) {
            case "ELECTRONICS" -> "OTHER_ELECTRONICS";
            case "BAGS" -> "OTHER_BAG";
            case "BOOKS" -> "OTHER_BOOK";
            case "KEYS" -> "OTHER_KEY";
            case "CLOTHING" -> "OTHER_CLOTHING";
            default -> "MISC";
        };
    }

    static boolean isValidSubtype(String category, String subtype) {
        List<String> valid = SUBTYPES_BY_CATEGORY.get(category);
        return valid != null && subtype != null && valid.contains(subtype);
    }

    static boolean isGenericSubtype(String subtype) {
        return subtype == null || subtype.startsWith("OTHER_") || "MISC".equals(subtype);
    }

    static Set<String> tokenizeAndCanonicalize(String title, String description, String category) {
        String combined = ((title != null ? title : "") + " " + (description != null ? description : "")).toLowerCase(Locale.ROOT);
        String[] rawTokens = combined.split("[^a-z0-9]+");
        Map<String, String> categoryDict = CATEGORY_SYNONYMS.getOrDefault(category, Map.of());

        Set<String> tokens = new HashSet<>();
        for (String token : rawTokens) {
            if (token.length() >= 3 && !STOPWORDS.contains(token)) {
                tokens.add(categoryDict.getOrDefault(token, token));
            }
        }
        return tokens;
    }

    static int damerauLevenshtein(String s1, String s2) {
        int len1 = s1.length();
        int len2 = s2.length();
        int[][] d = new int[len1 + 1][len2 + 1];

        for (int i = 0; i <= len1; i++) d[i][0] = i;
        for (int j = 0; j <= len2; j++) d[0][j] = j;

        for (int i = 1; i <= len1; i++) {
            for (int j = 1; j <= len2; j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                int min = Math.min(d[i - 1][j] + 1, Math.min(d[i][j - 1] + 1, d[i - 1][j - 1] + cost));
                if (i > 1 && j > 1 && s1.charAt(i - 1) == s2.charAt(j - 2) && s1.charAt(i - 2) == s2.charAt(j - 1)) {
                    min = Math.min(min, d[i - 2][j - 2] + cost);
                }
                d[i][j] = min;
            }
        }
        return d[len1][len2];
    }

    static boolean containsDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) return true;
        }
        return false;
    }

    static double tokenSimilarity(String t1, String t2) {
        if (t1.equals(t2)) return 1.0;
        if (t1.length() < 4 || t2.length() < 4) return 0.0;
        if (containsDigit(t1) || containsDigit(t2)) return 0.0;
        if (isProtectedConfusable(t1, t2)) return 0.0;
        int maxLen = Math.max(t1.length(), t2.length());
        int dist = damerauLevenshtein(t1, t2);
        double sim = 1.0 - ((double) dist / maxLen);
        return sim >= 0.80 ? sim : 0.0;
    }

    // Two-stage optimization:
    // Stage 1: Maximize cardinality K via augmenting paths.
    // Stage 2: Maximize total similarity among matchings of size K via Min-Cost Flow.
    // Deterministic tie-breaking is guaranteed by lexicographical traversal.

    private static int maxCardinality(List<List<Integer>> adj, int nU, int nV) {
        int[] match = new int[nV];
        Arrays.fill(match, -1);
        int k = 0;
        for (int u = 0; u < nU; u++) {
            boolean[] visited = new boolean[nV];
            if (augmentCardinality(u, adj, visited, match)) {
                k++;
            }
        }
        return k;
    }

    private static boolean augmentCardinality(int u, List<List<Integer>> adj, boolean[] visited, int[] match) {
        for (int v : adj.get(u)) {
            if (!visited[v]) {
                visited[v] = true;
                if (match[v] < 0 || augmentCardinality(match[v], adj, visited, match)) {
                    match[v] = u;
                    return true;
                }
            }
        }
        return false;
    }

    private static class FlowEdge {
        final int index;
        final int from, to, rev;
        final int cap;
        int flow;
        final double cost;

        FlowEdge(int index, int from, int to, int rev, int cap, double cost) {
            this.index = index;
            this.from = from;
            this.to = to;
            this.rev = rev;
            this.cap = cap;
            this.flow = 0;
            this.cost = cost;
        }
    }

    static int maxCardinalityMaxWeightMatching(double[][] simMatrix, int nU, int nV) {
        if (nU == 0 || nV == 0) return 0;

        List<List<Integer>> adj = new ArrayList<>(nU);
        for (int u = 0; u < nU; u++) {
            List<Integer> edges = new ArrayList<>();
            for (int v = 0; v < nV; v++) {
                if (simMatrix[u][v] >= 0.80) {
                    edges.add(v);
                }
            }
            adj.add(edges);
        }

        int K = maxCardinality(adj, nU, nV);
        if (K == 0) return 0;

        int N = nU + nV + 2;
        int src = 0;
        int sink = N - 1;

        List<List<FlowEdge>> graph = new ArrayList<>(N);
        for (int i = 0; i < N; i++) graph.add(new ArrayList<>());
        List<FlowEdge> allEdges = new ArrayList<>();

        for (int u = 0; u < nU; u++) {
            addEdge(graph, allEdges, src, u + 1, 1, 0.0);
        }
        for (int u = 0; u < nU; u++) {
            for (int v : adj.get(u)) {
                addEdge(graph, allEdges, u + 1, nU + 1 + v, 1, -simMatrix[u][v]);
            }
        }
        for (int v = 0; v < nV; v++) {
            addEdge(graph, allEdges, nU + 1 + v, sink, 1, 0.0);
        }

        for (int step = 0; step < K; step++) {
            double[] dist = new double[N];
            Arrays.fill(dist, Double.POSITIVE_INFINITY);
            int[] parentEdge = new int[N];
            Arrays.fill(parentEdge, -1);
            boolean[] inQueue = new boolean[N];

            dist[src] = 0.0;
            Queue<Integer> q = new ArrayDeque<>();
            q.add(src);
            inQueue[src] = true;

            while (!q.isEmpty()) {
                int curr = q.poll();
                inQueue[curr] = false;
                List<FlowEdge> currEdges = graph.get(curr);
                for (int eIdx = 0; eIdx < currEdges.size(); eIdx++) {
                    FlowEdge e = currEdges.get(eIdx);
                    if (e.cap - e.flow > 0 && dist[e.to] > dist[curr] + e.cost + 1e-9) {
                        dist[e.to] = dist[curr] + e.cost;
                        parentEdge[e.to] = e.index;
                        if (!inQueue[e.to]) {
                            q.add(e.to);
                            inQueue[e.to] = true;
                        }
                    }
                }
            }

            if (Double.isInfinite(dist[sink])) {
                break;
            }

            int curr = sink;
            while (curr != src) {
                int eIdx = parentEdge[curr];
                FlowEdge e = allEdges.get(eIdx);
                e.flow += 1;
                FlowEdge rev = allEdges.get(e.rev);
                rev.flow -= 1;
                curr = e.from;
            }
        }

        int matchedCount = 0;
        for (FlowEdge e : allEdges) {
            if (e.from >= 1 && e.from <= nU && e.to >= nU + 1 && e.to <= nU + nV && e.flow == 1) {
                matchedCount++;
            }
        }
        return matchedCount;
    }

    private static void addEdge(List<List<FlowEdge>> graph, List<FlowEdge> allEdges, int from, int to, int cap, double cost) {
        int idx1 = allEdges.size();
        FlowEdge e1 = new FlowEdge(idx1, from, to, idx1 + 1, cap, cost);
        allEdges.add(e1);
        graph.get(from).add(e1);

        FlowEdge e2 = new FlowEdge(idx1 + 1, to, from, idx1, 0, -cost);
        allEdges.add(e2);
        graph.get(to).add(e2);
    }

    record MatchResult(int matchedTokens, double jaccard, int keywordPoints) {}

    static MatchResult computeTextMatch(Set<String> setA, Set<String> setB) {
        if (setA.isEmpty() || setB.isEmpty()) {
            return new MatchResult(0, 0.0, 0);
        }

        Set<String> exact = new HashSet<>(setA);
        exact.retainAll(setB);

        List<String> remA = setA.stream().filter(t -> !exact.contains(t)).sorted().toList();
        List<String> remB = setB.stream().filter(t -> !exact.contains(t)).sorted().toList();

        int fuzzyMatches = 0;
        if (!remA.isEmpty() && !remB.isEmpty()) {
            int nA = remA.size();
            int nB = remB.size();
            double[][] simMatrix = new double[nA][nB];
            for (int i = 0; i < nA; i++) {
                for (int j = 0; j < nB; j++) {
                    simMatrix[i][j] = tokenSimilarity(remA.get(i), remB.get(j));
                }
            }
            fuzzyMatches = maxCardinalityMaxWeightMatching(simMatrix, nA, nB);
        }

        int totalMatched = exact.size() + fuzzyMatches;
        if (totalMatched == 0) {
            return new MatchResult(0, 0.0, 0);
        }
        int denominator = setA.size() + setB.size() - totalMatched;
        double jaccard = denominator > 0 ? (double) totalMatched / denominator : 0.0;
        int points = (int) Math.round(jaccard * 25.0);
        return new MatchResult(totalMatched, jaccard, points);
    }

    record ScoredMatch(int score, String classification, String explanation, int matchedTokens, double jaccard) {}

    static ScoredMatch evaluate(ItemReport a, ItemReport b) {
        if (a.id != null && a.id.equals(b.id)) {
            return new ScoredMatch(0, "DISCARDED", "Same report", 0, 0.0);
        }
        if (!"OPEN".equals(a.status) || !"OPEN".equals(b.status)) {
            return new ScoredMatch(0, "DISCARDED", "Report is not open", 0, 0.0);
        }
        if (a.kind.equals(b.kind) || !a.category.equals(b.category)) {
            return new ScoredMatch(0, "DISCARDED", "Incompatible kind or category", 0, 0.0);
        }

        ItemReport lost = "LOST".equals(a.kind) ? a : b;
        ItemReport found = "FOUND".equals(a.kind) ? a : b;
        long days = ChronoUnit.DAYS.between(lost.eventDate, found.eventDate);
        if (days < 0 || days > 14) {
            return new ScoredMatch(0, "DISCARDED", "Event date outside valid chronological window", 0, 0.0);
        }

        boolean lostGeneric = isGenericSubtype(lost.subtype);
        boolean foundGeneric = isGenericSubtype(found.subtype);
        int subtypeScore;
        if (!lostGeneric && !foundGeneric) {
            if (!lost.subtype.equals(found.subtype)) {
                return new ScoredMatch(0, "DISCARDED", "Incompatible specific subtypes", 0, 0.0);
            }
            subtypeScore = 35;
        } else {
            subtypeScore = 20;
        }

        int locationScore = (a.location.equals(b.location) && !"OTHER".equals(a.location)) ? 20 : 0;
        int dateScore = (days <= 1) ? 20 : (days <= 7) ? 12 : 5;

        Set<String> setA = tokenizeAndCanonicalize(a.title, a.description, a.category);
        Set<String> setB = tokenizeAndCanonicalize(b.title, b.description, b.category);
        MatchResult textMatch = computeTextMatch(setA, setB);

        // Zero evidence rejection: no matched tokens yields an immediate discard
        if (textMatch.matchedTokens() == 0) {
            return new ScoredMatch(0, "DISCARDED", "No matching keywords found in description", 0, 0.0);
        }

        int rawScore = subtypeScore + locationScore + dateScore + textMatch.keywordPoints();

        String classification;
        int finalScore;

        if (rawScore >= 75 && textMatch.matchedTokens() >= 2 && textMatch.jaccard() >= 0.30) {
            classification = "Strong";
            finalScore = rawScore;
        } else if (rawScore >= 50 && textMatch.matchedTokens() >= 1 && textMatch.jaccard() >= 0.10) {
            classification = "Possible";
            finalScore = Math.min(rawScore, 74);
        } else if (rawScore >= 40 && textMatch.matchedTokens() >= 1) {
            classification = "Weak";
            finalScore = Math.min(rawScore, 49);
        } else {
            classification = "DISCARDED";
            finalScore = 0;
        }

        String reason = String.format("Category: %s (%s); %s campus zone; found %d %s after loss; %d matched keyword(s) (Jaccard: %.2f).",
            a.category, (!lostGeneric && !foundGeneric) ? "exact subtype" : "generic subtype fallback",
            locationScore > 0 ? "same" : "different/unspecified",
            days, days == 1 ? "day" : "days",
            textMatch.matchedTokens(), textMatch.jaccard());

        return new ScoredMatch(finalScore, classification, reason, textMatch.matchedTokens(), textMatch.jaccard());
    }

    static boolean compatible(ItemReport a, ItemReport b) {
        return evaluate(a, b).score() >= 40;
    }

    static int score(ItemReport a, ItemReport b) {
        return evaluate(a, b).score();
    }

    static boolean possibleDuplicate(ItemReport a, ItemReport b) {
        if (!a.kind.equals(b.kind) || !a.category.equals(b.category) || !a.location.equals(b.location)) {
            return false;
        }
        if (Math.abs(ChronoUnit.DAYS.between(a.eventDate, b.eventDate)) > 1) {
            return false;
        }
        boolean aGeneric = isGenericSubtype(a.subtype);
        boolean bGeneric = isGenericSubtype(b.subtype);
        if (!aGeneric && !bGeneric && !a.subtype.equals(b.subtype)) {
            return false;
        }
        if (a.title != null && a.title.equalsIgnoreCase(b.title)) {
            return true;
        }
        Set<String> setA = tokenizeAndCanonicalize(a.title, a.description, a.category);
        Set<String> setB = tokenizeAndCanonicalize(b.title, b.description, b.category);
        MatchResult textMatch = computeTextMatch(setA, setB);
        return textMatch.jaccard() >= 0.50;
    }
}
