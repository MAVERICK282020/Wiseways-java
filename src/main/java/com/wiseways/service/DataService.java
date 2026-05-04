package com.wiseways.service;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReaderBuilder;
import com.wiseways.model.CollegeEntry;
import com.wiseways.model.CollegeResult;
import com.wiseways.model.RecommendRequest;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DataService {

    @Value("${csv.primary}")
    private String csvPrimary;

    private List<CollegeEntry> dataset = new ArrayList<>();
    private Map<String, Integer> branchMapping = new HashMap<>();

    // ================= LOAD CSV =================
    @PostConstruct
    public void initData() {
        try {
            List<String[]> rows = readCsv(csvPrimary);

            if (rows.isEmpty()) {
                log.error("❌ CSV EMPTY!");
                return;
            }

            Map<String, Integer> colIdx = buildColumnIndex(rows.get(0));

            int iInstitute = requireCol(colIdx, "Institute");
            int iProgram = requireCol(colIdx, "Program", "Academic Program Name");
            int iOpenRank = requireCol(colIdx, "Opening Rank");
            int iCloseRank = requireCol(colIdx, "Closing Rank");

            int iCity = colIdx.getOrDefault(clean("City"), -1);
            int iCounselling = colIdx.getOrDefault(clean("Counselling Board"), -1);
            int iFees = colIdx.getOrDefault(clean("Fees Lakhs"), -1);
            int iAvgPkg = colIdx.getOrDefault(clean("Avg Package"), -1);

            List<CollegeEntry> raw = new ArrayList<>();

            for (int i = 1; i < rows.size(); i++) {
                String[] row = rows.get(i);

                String college = cell(row, iInstitute);
                String branch = cell(row, iProgram);
                double opening = parseRank(cell(row, iOpenRank));
                double closing = parseRank(cell(row, iCloseRank));

                if (college.isEmpty() || branch.isEmpty()
                        || Double.isNaN(opening) || Double.isNaN(closing))
                    continue;

                CollegeEntry e = new CollegeEntry();
                e.setCollege(college);
                e.setBranch(branch);
                e.setOpeningRank(opening);
                e.setClosingRank(closing);

                if (iCity >= 0) e.setCity(cell(row, iCity));
                if (iCounselling >= 0) e.setCounsellingBoard(cell(row, iCounselling));
                if (iFees >= 0) e.setFeesLakhs(parseDouble(cell(row, iFees)));
                if (iAvgPkg >= 0) e.setAvgPackage(parseDouble(cell(row, iAvgPkg)));

                raw.add(e);
            }

            // Deduplicate
            LinkedHashMap<String, CollegeEntry> map = new LinkedHashMap<>();
            for (CollegeEntry e : raw)
                map.put(e.getCollege() + "|" + e.getBranch(), e);

            dataset = new ArrayList<>(map.values());

            // Branch encoding
            List<String> branches = dataset.stream()
                    .map(CollegeEntry::getBranch)
                    .distinct().sorted()
                    .toList();

            for (int i = 0; i < branches.size(); i++)
                branchMapping.put(branches.get(i), i);

            dataset.forEach(e ->
                    e.setBranchCode(branchMapping.getOrDefault(e.getBranch(), -1)));

            log.info("✅ DATA LOADED: {}", dataset.size());

        } catch (Exception e) {
            log.error("❌ CSV ERROR", e);
        }
    }

    // ================= RECOMMEND =================
    public List<CollegeResult> recommend(RecommendRequest req) {

        int effectiveRank = (req.getCategoryRank() != null && !req.getCategoryRank().isBlank())
                ? parseCategoryRank(req.getCategoryRank())
                : req.getRank();

        String branchInput = safe(req.getBranch());
        String area = safe(req.getArea()).toLowerCase();
        String counselling = safe(req.getCounselling()).toLowerCase();
        String budget = mapBudget(safe(req.getBudget()));

        List<CollegeEntry> temp = new ArrayList<>(dataset);

        // ===== CITY FILTER (SMART) =====
        if (!area.isEmpty()) {
            temp = temp.stream()
                    .filter(e -> {
                        if (e.getCity() == null) return false;
                        String city = e.getCity().toLowerCase();

                        return city.contains(area)
                                || area.contains(city)
                                || city.contains("noida")
                                || city.contains("greater noida");
                    })
                    .collect(Collectors.toList());
        }

        log.info("After city filter: {}", temp.size());

        // ===== COUNSELLING FILTER =====
        if (!counselling.equals("any") && !counselling.isEmpty()) {
            temp = temp.stream()
                    .filter(e -> e.getCounsellingBoard() != null &&
                            e.getCounsellingBoard().toLowerCase().contains(counselling))
                    .collect(Collectors.toList());
        }

        log.info("After counselling filter: {}", temp.size());

        // ===== BUDGET FILTER =====
        if (!budget.equals("any")) {
            temp = applyBudgetFilter(temp, budget);
        }

        log.info("After budget filter: {}", temp.size());

        // ===== ELIGIBILITY =====
        double buffer = effectiveRank * 0.95;

        List<CollegeEntry> eligible = temp.stream()
                .filter(e -> e.getClosingRank() >= buffer)
                .collect(Collectors.toList());

        log.info("Eligible: {}", eligible.size());

        // ===== FALLBACK =====
        if (eligible.isEmpty()) {
            log.warn("⚠️ NO ELIGIBLE → FALLBACK");

            eligible = dataset.stream()
                    .sorted(Comparator.comparingDouble(e ->
                            Math.abs(e.getClosingRank() - effectiveRank)))
                    .limit(10)
                    .collect(Collectors.toList());
        }

        // ===== SORT =====
        Integer branchCode = branchMapping.get(branchInput);

        eligible.sort((a, b) -> {
            double diffA = Math.abs(a.getClosingRank() - effectiveRank);
            double diffB = Math.abs(b.getClosingRank() - effectiveRank);

            if (branchCode != null) {
                if (a.getBranchCode() != branchCode) diffA += 100000;
                if (b.getBranchCode() != branchCode) diffB += 100000;
            }

            return Double.compare(diffA, diffB);
        });

        return eligible.stream()
                .limit(5)
                .map(e -> buildResult(e, effectiveRank))
                .collect(Collectors.toList());
    }

    // ================= HELPERS =================

    private String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private String mapBudget(String input) {
        return switch (input) {
            case "5 Lakh - 10 Lakh" -> "5-10";
            case "10 Lakh - 20 Lakh" -> "10-20";
            case "20 Lakh - 40 Lakh" -> "20-40";
            case "Above 40 Lakh" -> "40+";
            default -> "any";
        };
    }

    private List<CollegeEntry> applyBudgetFilter(List<CollegeEntry> list, String budget) {
        return list.stream().filter(e -> {
            if (e.getFeesLakhs() == null) return false;
            double f = e.getFeesLakhs();

            return switch (budget) {
                case "5-10" -> f >= 5 && f <= 10;
                case "10-20" -> f >= 10 && f <= 20;
                case "20-40" -> f >= 20 && f <= 40;
                case "40+" -> f > 40;
                default -> true;
            };
        }).collect(Collectors.toList());
    }

    private CollegeResult buildResult(CollegeEntry e, int rank) {
        double diff = Math.abs(e.getClosingRank() - rank);

        int score = (e.getClosingRank() >= rank)
                ? Math.max(50, 98 - (int)((diff / rank) * 80))
                : Math.max(30, 85 - (int)((diff / rank) * 100));

        return new CollegeResult(
                e.getCollege(),
                e.getBranch(),
                e.getClosingRank(),
                Math.min(score, 99),
                e.getCity() != null ? e.getCity() : "",
                e.getAvgPackage() != null ? e.getAvgPackage() + " LPA" : "-",
                e.getFeesLakhs() != null ? e.getFeesLakhs() + " Lakhs" : "-"
        );
    }

    private List<String[]> readCsv(String path) throws Exception {
        var parser = new CSVParserBuilder().withSeparator(',').build();
        try (var reader = new CSVReaderBuilder(new FileReader(path))
                .withCSVParser(parser).build()) {
            return reader.readAll();
        } catch (Exception e) {
            var stream = getClass().getClassLoader().getResourceAsStream(path);
            if (stream == null) throw e;
            try (var reader = new CSVReaderBuilder(new InputStreamReader(stream))
                    .withCSVParser(parser).build()) {
                return reader.readAll();
            }
        }
    }

    private Map<String, Integer> buildColumnIndex(String[] header) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < header.length; i++)
            map.put(clean(header[i]), i);
        return map;
    }

    private String clean(String s) {
        return s.replaceAll("[^a-zA-Z0-9\\s]", "").trim();
    }

    private int requireCol(Map<String, Integer> map, String... names) {
        for (String n : names) {
            Integer i = map.get(clean(n));
            if (i != null) return i;
        }
        return -1;
    }

    private double parseRank(String raw) {
        String s = raw.replaceAll("\\D", "");
        if (s.isEmpty()) return Double.NaN;
        return Double.parseDouble(s);
    }

    private Double parseDouble(String raw) {
        try {
            return raw == null ? null : Double.parseDouble(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String cell(String[] row, int i) {
        return (i >= 0 && i < row.length) ? row[i].trim() : "";
    }

    private int parseCategoryRank(String val) {
        try {
            return Integer.parseInt(val.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}