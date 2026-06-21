import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Standalone dataset generator (Phase 11).
 *
 * <p>Produces a CSV of {@code query,count} with >= 100,000 UNIQUE realistic
 * search queries. Counts follow a Zipf-like distribution (a few very popular
 * queries, a long tail of rare ones) so that ranking, trending and the cache
 * hit-rate behave like a real workload.
 *
 * <p>No build tooling needed - it only uses the JDK:
 * <pre>
 *   javac DatasetGenerator.java
 *   java   DatasetGenerator                 # writes queries.csv (default 120000 rows)
 *   java   DatasetGenerator 250000 out.csv  # custom size + path
 * </pre>
 *
 * Copy the result to {@code backend/src/main/resources/data/queries.csv} and set
 * {@code dataset.csv-path=classpath:data/queries.csv} (or point it at an absolute
 * {@code file:} path) to load it on startup.
 */
public class DatasetGenerator {

    // Seed vocabulary - base entities the queries are built from.
    static final String[] BRANDS = {
            "iphone", "samsung galaxy", "google pixel", "oneplus", "xiaomi", "macbook", "dell xps",
            "hp laptop", "lenovo thinkpad", "asus rog", "sony", "bose", "jbl", "nike", "adidas",
            "puma", "canon", "nikon", "gopro", "logitech", "razer", "corsair", "amazon echo",
            "apple watch", "fitbit", "garmin", "kindle", "playstation", "xbox", "nintendo switch"
    };

    static final String[] CATEGORIES = {
            "laptop", "phone", "headphones", "earbuds", "smartwatch", "tablet", "monitor", "keyboard",
            "mouse", "camera", "tv", "speaker", "charger", "cable", "case", "screen protector",
            "power bank", "router", "ssd", "graphics card", "processor", "ram", "motherboard",
            "shoes", "backpack", "jacket", "watch", "sunglasses", "coffee maker", "air fryer"
    };

    static final String[] TECH_TOPICS = {
            "java", "python", "javascript", "typescript", "react", "spring boot", "node js", "golang",
            "rust", "kubernetes", "docker", "kafka", "redis", "postgresql", "mongodb", "system design",
            "data structures", "algorithms", "machine learning", "deep learning", "sql", "aws", "azure",
            "consistent hashing", "load balancing", "caching", "microservices", "rest api", "graphql", "git"
    };

    static final String[] TECH_SUFFIX = {
            "tutorial", "for beginners", "interview questions", "examples", "cheat sheet", "vs", "course",
            "documentation", "best practices", "project ideas", "roadmap", "certification", "online course",
            "pdf", "github", "explained", "tutorial for beginners", "advanced tutorial", "crash course", "notes"
    };

    static final String[] COMMERCE_SUFFIX = {
            "price", "deals", "review", "near me", "online", "best", "cheap", "2024", "2025", "pro",
            "pro max", "ultra", "lite", "plus", "case", "cover", "accessories", "charger", "stand", "warranty"
    };

    public static void main(String[] args) throws IOException {
        int target = args.length > 0 ? Integer.parseInt(args[0]) : 120_000;
        String out = args.length > 1 ? args[1] : "queries.csv";

        // Deterministic seed so the dataset is reproducible across runs/machines.
        Random rnd = new Random(42);

        // Use a set to guarantee uniqueness; LinkedHashSet keeps insertion order
        // so the most "popular-looking" combos appear first.
        Set<String> queries = new LinkedHashSet<>();

        // 1) Brand + commerce suffix combinations
        for (String b : BRANDS) {
            queries.add(b);
            for (String s : COMMERCE_SUFFIX) {
                queries.add(b + " " + s);
            }
        }
        // 2) Category + commerce suffix
        for (String c : CATEGORIES) {
            queries.add(c);
            for (String s : COMMERCE_SUFFIX) {
                queries.add(c + " " + s);
                queries.add("best " + c + " " + s);
            }
        }
        // 3) Tech topic + suffix
        for (String t : TECH_TOPICS) {
            queries.add(t);
            for (String s : TECH_SUFFIX) {
                queries.add(t + " " + s);
            }
        }
        // 4) Brand + category cross product (e.g. "samsung galaxy laptop case")
        for (String b : BRANDS) {
            for (String c : CATEGORIES) {
                queries.add(b + " " + c);
            }
        }
        // 5) Tech topic pairs ("java vs python", "react vs vue"-style)
        for (String t1 : TECH_TOPICS) {
            for (String t2 : TECH_TOPICS) {
                if (!t1.equals(t2)) {
                    queries.add(t1 + " vs " + t2);
                }
            }
        }

        // 6) Pad with numbered variants until we reach the target unique count.
        List<String> base = new ArrayList<>(queries);
        int n = 0;
        while (queries.size() < target) {
            String b = base.get(rnd.nextInt(base.size()));
            queries.add(b + " " + (rnd.nextInt(9000) + 1000)); // append a model number
            n++;
            if (n > target * 5) {
                break; // safety
            }
        }

        // 7) Assign Zipf-like counts: rank r -> count ~ floor(BASE / r^0.85) + noise.
        List<String> all = new ArrayList<>(queries);
        final double base0 = 100_000.0;
        int written = 0;
        try (BufferedWriter w = new BufferedWriter(new FileWriter(out))) {
            w.write("query,count");
            w.newLine();
            for (int i = 0; i < all.size(); i++) {
                int rank = i + 1;
                long count = (long) (base0 / Math.pow(rank, 0.85)) + rnd.nextInt(50) + 1;
                String q = all.get(i);
                // CSV-quote any query containing a comma (defensive; current seeds have none).
                if (q.contains(",") || q.contains("\"")) {
                    q = "\"" + q.replace("\"", "\"\"") + "\"";
                }
                w.write(q + "," + count);
                w.newLine();
                written++;
            }
        }
        System.out.println("Wrote " + written + " unique queries to " + out);
    }
}
