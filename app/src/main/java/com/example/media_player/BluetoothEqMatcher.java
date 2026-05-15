package com.example.media_player;

import com.nerio.audioengine.EqProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matches Bluetooth device names against AutoEQ profile names.
 * Handles brand prefixes, BLE prefixes, and variant grouping.
 */
public class BluetoothEqMatcher {

    private static final Pattern PAREN_SUFFIX = Pattern.compile("^(.+?)\\s*\\((.+)\\)$");

    static final List<String> SOURCE_PREFERENCE = Arrays.asList(
            "oratory1990", "crinacle", "Rtings", "Super Review",
            "Innerfidelity", "Kuulokenurkka", "HypetheSonics",
            "ToneDeafMonk", "Jaytiss"
    );

    public static final String DIM_ANC = "anc";
    public static final String DIM_CONNECTION = "connection";
    public static final String DIM_MODULE = "module";
    public static final String DIM_OTHER = "other";

    public static class MatchResult {
        public final String btDeviceName;
        public final String matchedBaseName;
        public final int matchTier;
        public final List<EqProfile> allCandidates;
        public final List<EqProfile> baseProfiles;
        public final Map<String, List<EqProfile>> variantsByDimension;
        public final boolean autoApply;

        MatchResult(String btDeviceName, String matchedBaseName, int matchTier,
                    List<EqProfile> allCandidates, List<EqProfile> baseProfiles,
                    Map<String, List<EqProfile>> variantsByDimension, boolean autoApply) {
            this.btDeviceName = btDeviceName;
            this.matchedBaseName = matchedBaseName;
            this.matchTier = matchTier;
            this.allCandidates = allCandidates;
            this.baseProfiles = baseProfiles;
            this.variantsByDimension = variantsByDimension;
            this.autoApply = autoApply;
        }

        public boolean hasAncVariants() {
            return variantsByDimension.containsKey(DIM_ANC);
        }

        public boolean hasConnectionVariants() {
            return variantsByDimension.containsKey(DIM_CONNECTION);
        }
    }

    /**
     * Match a BT device name against the full list of AutoEQ profiles.
     * Returns null if no match found.
     */
    public static MatchResult match(String btDeviceName, List<EqProfile> allProfiles) {
        if (btDeviceName == null || btDeviceName.isEmpty() || allProfiles == null) return null;

        String normBt = normalizeBtName(btDeviceName);
        if (normBt.isEmpty()) return null;

        // Build base name index: baseName -> list of profiles
        Map<String, List<EqProfile>> baseIndex = new HashMap<>();
        for (EqProfile p : allProfiles) {
            String base = extractBaseName(p.name).toLowerCase(Locale.ROOT).trim();
            List<EqProfile> list = baseIndex.get(base);
            if (list == null) {
                list = new ArrayList<>();
                baseIndex.put(base, list);
            }
            list.add(p);
        }

        // Try matching tiers
        String matchedBase = null;
        int tier = 0;

        // Tier 1: Exact match
        matchedBase = tryExactMatch(normBt, baseIndex);
        if (matchedBase != null) {
            tier = 1;
        }

        // Tier 2: Brand-stripped (strip first word from profile base names)
        if (matchedBase == null) {
            matchedBase = tryBrandStripped(normBt, baseIndex);
            if (matchedBase != null) tier = 2;
        }

        // Tier 3: Contains (substring)
        if (matchedBase == null) {
            matchedBase = tryContains(normBt, baseIndex);
            if (matchedBase != null) tier = 3;
        }

        // Tier 4: Token overlap
        if (matchedBase == null) {
            matchedBase = tryTokenOverlap(normBt, baseIndex);
            if (matchedBase != null) tier = 4;
        }

        if (matchedBase == null) return null;

        List<EqProfile> candidates = baseIndex.get(matchedBase);
        if (candidates == null || candidates.isEmpty()) return null;

        // Separate base profiles from variant profiles
        List<EqProfile> baseProfiles = new ArrayList<>();
        Map<String, List<EqProfile>> variantsByDim = new HashMap<>();

        for (EqProfile p : candidates) {
            String variant = extractVariant(p.name);
            if (variant == null) {
                baseProfiles.add(p);
            } else {
                String dim = classifyVariant(variant);
                List<EqProfile> dimList = variantsByDim.get(dim);
                if (dimList == null) {
                    dimList = new ArrayList<>();
                    variantsByDim.put(dim, dimList);
                }
                dimList.add(p);
            }
        }

        // Determine if we can auto-apply
        boolean autoApply = false;
        if (tier <= 2 && variantsByDim.isEmpty()) {
            // No variants — pick preferred source from base profiles
            autoApply = true;
        }

        // Use the original-case base name for display
        String displayBase = extractBaseName(candidates.get(0).name);

        return new MatchResult(btDeviceName, displayBase, tier,
                candidates, baseProfiles, variantsByDim, autoApply);
    }

    /**
     * Pick the best profile from a list of candidates for the same variant,
     * using source preference ranking.
     */
    public static EqProfile pickPreferredSource(List<EqProfile> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);

        EqProfile best = null;
        int bestRank = Integer.MAX_VALUE;
        for (EqProfile p : candidates) {
            int rank = SOURCE_PREFERENCE.indexOf(p.source);
            if (rank < 0) rank = SOURCE_PREFERENCE.size(); // unknown source: lowest
            if (rank < bestRank) {
                bestRank = rank;
                best = p;
            }
        }
        return best;
    }

    /**
     * Get the single best profile to auto-apply from a match result.
     * Picks preferred source from base profiles (no variant).
     */
    public static EqProfile pickAutoApplyProfile(MatchResult result) {
        if (result == null) return null;
        if (!result.baseProfiles.isEmpty()) {
            return pickPreferredSource(result.baseProfiles);
        }
        // No base profiles, pick from all candidates
        return pickPreferredSource(result.allCandidates);
    }

    // -- Normalization --

    static String normalizeBtName(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        // Strip BLE prefixes
        if (s.startsWith("LE-") || s.startsWith("LE_")) {
            s = s.substring(3);
        }
        // Collapse whitespace
        s = s.replaceAll("\\s+", " ").trim();
        return s.toLowerCase(Locale.ROOT);
    }

    static String extractBaseName(String profileName) {
        if (profileName == null) return "";
        Matcher m = PAREN_SUFFIX.matcher(profileName);
        if (m.matches()) {
            return m.group(1).trim();
        }
        return profileName.trim();
    }

    static String extractVariant(String profileName) {
        if (profileName == null) return null;
        Matcher m = PAREN_SUFFIX.matcher(profileName);
        if (m.matches()) {
            return m.group(2).trim();
        }
        return null;
    }

    static String classifyVariant(String variant) {
        if (variant == null) return DIM_OTHER;
        String lower = variant.toLowerCase(Locale.ROOT);
        if (lower.contains("anc") || lower.contains("active noise")
                || lower.contains("transparency") || lower.contains("noise cancel")) {
            return DIM_ANC;
        }
        if (lower.contains("wired") || lower.contains("wireless")
                || lower.contains("bluetooth") || lower.contains("cable")
                || lower.contains("analog") || lower.contains("passive")) {
            return DIM_CONNECTION;
        }
        if (lower.contains("module") || lower.contains("pad") || lower.contains("tip")
                || lower.contains("nozzle") || lower.contains("filter")
                || lower.contains("eartip") || lower.contains("earpad")) {
            return DIM_MODULE;
        }
        return DIM_OTHER;
    }

    // -- Matching tiers --

    private static String tryExactMatch(String normBt, Map<String, List<EqProfile>> baseIndex) {
        if (baseIndex.containsKey(normBt)) return normBt;
        return null;
    }

    private static String tryBrandStripped(String normBt, Map<String, List<EqProfile>> baseIndex) {
        for (String base : baseIndex.keySet()) {
            int space = base.indexOf(' ');
            if (space > 0) {
                String stripped = base.substring(space + 1);
                if (stripped.equals(normBt)) {
                    return base;
                }
            }
        }
        return null;
    }

    private static String tryContains(String normBt, Map<String, List<EqProfile>> baseIndex) {
        if (normBt.length() < 4) return null; // too short to match reliably

        String bestBase = null;
        double bestScore = 0;

        for (String base : baseIndex.keySet()) {
            double score = containsScore(normBt, base);

            // Also try brand-stripped base (e.g., "momentum 4" in "momentum 4 wireless")
            int space = base.indexOf(' ');
            if (space > 0) {
                double strippedScore = containsScore(normBt, base.substring(space + 1));
                score = Math.max(score, strippedScore);
            }

            if (score > bestScore) {
                bestScore = score;
                bestBase = base;
            }
        }
        return bestBase;
    }

    private static double containsScore(String normBt, String base) {
        if (base.contains(normBt)) {
            // BT name fully contained in profile base — strong signal.
            // The profile is at least as specific as the BT name.
            return (double) normBt.length() / base.length();
        }
        // Don't match the reverse (base contained in BT name) — too many
        // false positives (e.g., "Momentum" matching "Momentum 4").
        return 0;
    }

    private static String tryTokenOverlap(String normBt, Map<String, List<EqProfile>> baseIndex) {
        Set<String> btTokens = tokenize(normBt);
        if (btTokens.isEmpty()) return null;

        String bestBase = null;
        double bestSim = 0;

        for (String base : baseIndex.keySet()) {
            Set<String> baseTokens = tokenize(base);
            if (baseTokens.isEmpty()) continue;

            Set<String> intersection = new HashSet<>(btTokens);
            intersection.retainAll(baseTokens);

            Set<String> union = new HashSet<>(btTokens);
            union.addAll(baseTokens);

            double jaccard = (double) intersection.size() / union.size();
            if (jaccard >= 0.35 && jaccard > bestSim) {
                bestSim = jaccard;
                bestBase = base;
            }
        }
        return bestBase;
    }

    private static Set<String> tokenize(String s) {
        Set<String> tokens = new HashSet<>();
        for (String t : s.split("[^a-z0-9]+")) {
            if (!t.isEmpty()) tokens.add(t);
        }
        // Split tokens that fuse letters and digits: "qc45" -> "qc", "45"
        Set<String> expanded = new HashSet<>(tokens);
        for (String t : tokens) {
            java.util.regex.Matcher m = Pattern.compile("([a-z]+)(\\d+)").matcher(t);
            if (m.matches()) {
                expanded.add(m.group(1));
                expanded.add(m.group(2));
            }
        }
        return expanded;
    }
}
