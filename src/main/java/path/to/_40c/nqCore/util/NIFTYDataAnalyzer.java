package path.to._40c.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NIFTYDataAnalyzer {
    
    private static final String TICKER = "NIFTY-I.NFO";
    private static final String BASE_PATH = "C:\\Users\\Vinoth M\\Downloads\\2024\\2024";
    private static final Pattern FILENAME_PATTERN = Pattern.compile("GFDLNFO_BACKADJUSTED_(\\d{2})(\\d{2})(\\d{4})(?:\\.csv)?", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    
    private Map<String, Map<String, Integer>> monthlyData = new LinkedHashMap<>();
    private List<String> fileIssues = new ArrayList<>();
    private Map<String, DailyStats> dailyStats = new TreeMap<>();
    
    private static class DailyStats {
        String date;
        String month;
        int count;
        boolean isWeekend;
        String filename;
        
        DailyStats(String date, String month, int count, boolean isWeekend, String filename) {
            this.date = date;
            this.month = month;
            this.count = count;
            this.isWeekend = isWeekend;
            this.filename = filename;
        }
    }
    
    private static class MonthSummary {
        String monthName;
        int totalRecords;
        int tradingDays;
        double avgRecordsPerDay;
        double stdDev;
        int minRecords;
        int maxRecords;
        List<String> anomalies;
        
        MonthSummary(String monthName) {
            this.monthName = monthName;
            this.anomalies = new ArrayList<>();
        }
    }
    
    public static void main(String[] args) {
        NIFTYDataAnalyzer analyzer = new NIFTYDataAnalyzer();
        analyzer.analyze();
    }
    
    public void analyze() {
        System.out.println("=".repeat(100));
        System.out.println("NIFTY-I.NFO DATA CONSISTENCY ANALYSIS");
        System.out.println("Base Path: " + BASE_PATH);
        System.out.println("Analysis Date: " + LocalDate.now());
        System.out.println("=".repeat(100));
        System.out.println();
        
        long startTime = System.currentTimeMillis();
        
        String[] months = {
            "1_JAN", "2_FEB", "3_MAR", "4_APR", "5_MAY", "6_JUN",
            "7_JUL", "8_AUG", "9_SEP", "10_OCT", "11_NOV", "12_DEC"
        };
        
        for (String month : months) {
            processMonth(month);
        }
        
        long endTime = System.currentTimeMillis();
        
        // Print detailed daily logs
        printDetailedDailyLogs();
        
        // Print monthly summaries
        printMonthlySummaries();
        
        // Print file issues if any
        if (!fileIssues.isEmpty()) {
            System.out.println("\n" + "=".repeat(100));
            System.out.println("FILE ISSUES DETECTED:");
            System.out.println("=".repeat(100));
            for (String issue : fileIssues) {
                System.out.println("  ⚠ " + issue);
            }
        }
        
        // Print overall summary
        printOverallSummary(endTime - startTime);
    }
    
    private void processMonth(String monthFolder) {
        File monthDir = new File(BASE_PATH, monthFolder);
        
        if (!monthDir.exists() || !monthDir.isDirectory()) {
            fileIssues.add("Month folder not found: " + monthFolder);
            return;
        }
        
        System.out.println("Processing: " + monthFolder + "...");
        
        Map<String, Integer> dailyCounts = new TreeMap<>();
        File[] files = monthDir.listFiles();
        
        if (files == null || files.length == 0) {
            fileIssues.add("No files found in: " + monthFolder);
            return;
        }
        
        for (File file : files) {
            if (file.isFile()) {
                processFile(file, monthFolder, dailyCounts);
            }
        }
        
        monthlyData.put(monthFolder, dailyCounts);
    }
    
    private void processFile(File file, String monthFolder, Map<String, Integer> dailyCounts) {
        String filename = file.getName();
        
        Matcher matcher = FILENAME_PATTERN.matcher(filename);
        if (!matcher.matches()) {
            fileIssues.add("Invalid filename format: " + filename + " in " + monthFolder);
            return;
        }
        
        String day = matcher.group(1);
        String month = matcher.group(2);
        String year = matcher.group(3);
        String dateKey = day + "-" + month + "-" + year;
        
        try {
            int count = countNiftyRecords(file);
            dailyCounts.put(dateKey, count);
            
            LocalDate date = LocalDate.parse(dateKey, DATE_FORMATTER);
            boolean isWeekend = (date.getDayOfWeek() == DayOfWeek.SATURDAY || 
                                date.getDayOfWeek() == DayOfWeek.SUNDAY);
            
            dailyStats.put(dateKey, new DailyStats(dateKey, monthFolder, count, isWeekend, filename));
            
        } catch (IOException e) {
            fileIssues.add("Error reading file: " + filename + " - " + e.getMessage());
        } catch (Exception e) {
            fileIssues.add("Error parsing date from: " + filename + " - " + e.getMessage());
        }
    }
    
    private int countNiftyRecords(File file) throws IOException {
        int count = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(file), 65536)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith(TICKER)) {
                    count++;
                }
            }
        }
        return count;
    }
    
    private void printDetailedDailyLogs() {
        System.out.println("\n" + "=".repeat(100));
        System.out.println("DETAILED DAILY BREAKDOWN");
        System.out.println("=".repeat(100));
        
        String currentMonth = "";
        for (Map.Entry<String, DailyStats> entry : dailyStats.entrySet()) {
            DailyStats stats = entry.getValue();
            
            if (!stats.month.equals(currentMonth)) {
                currentMonth = stats.month;
                System.out.println("\n>>> " + currentMonth);
                System.out.println("-".repeat(100));
                System.out.printf("%-15s %-30s %-15s %-15s%n", 
                    "Date", "Filename", "Records", "Day Type");
                System.out.println("-".repeat(100));
            }
            
            String dayType = stats.isWeekend ? "WEEKEND" : "Trading Day";
            String flag = "";
            
            if (stats.isWeekend && stats.count > 0) {
                flag = " ⚠ UNEXPECTED DATA ON WEEKEND";
            } else if (!stats.isWeekend && stats.count == 0) {
                flag = " ⚠ NO DATA ON TRADING DAY";
            }
            
            System.out.printf("%-15s %-30s %,15d %-15s%s%n", 
                stats.date, stats.filename, stats.count, dayType, flag);
        }
    }
    
    private void printMonthlySummaries() {
        System.out.println("\n" + "=".repeat(100));
        System.out.println("MONTHLY SUMMARY & CONSISTENCY ANALYSIS");
        System.out.println("=".repeat(100));
        
        for (Map.Entry<String, Map<String, Integer>> monthEntry : monthlyData.entrySet()) {
            String monthName = monthEntry.getKey();
            Map<String, Integer> dailyCounts = monthEntry.getValue();
            
            if (dailyCounts.isEmpty()) {
                continue;
            }
            
            MonthSummary summary = calculateMonthSummary(monthName, dailyCounts);
            printMonthSummary(summary);
        }
    }
    
    private MonthSummary calculateMonthSummary(String monthName, Map<String, Integer> dailyCounts) {
        MonthSummary summary = new MonthSummary(monthName);
        
        List<Integer> tradingDayCounts = new ArrayList<>();
        
        for (Map.Entry<String, Integer> entry : dailyCounts.entrySet()) {
            String dateKey = entry.getKey();
            int count = entry.getValue();
            summary.totalRecords += count;
            
            DailyStats stats = dailyStats.get(dateKey);
            if (stats != null && !stats.isWeekend && count > 0) {
                tradingDayCounts.add(count);
            }
        }
        
        summary.tradingDays = tradingDayCounts.size();
        
        if (!tradingDayCounts.isEmpty()) {
            summary.avgRecordsPerDay = tradingDayCounts.stream()
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0.0);
            
            summary.minRecords = tradingDayCounts.stream()
                .mapToInt(Integer::intValue)
                .min()
                .orElse(0);
            
            summary.maxRecords = tradingDayCounts.stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
            
            summary.stdDev = calculateStdDev(tradingDayCounts, summary.avgRecordsPerDay);
            
            // Detect anomalies (counts beyond 2 standard deviations)
            double lowerThreshold = summary.avgRecordsPerDay - (2 * summary.stdDev);
            double upperThreshold = summary.avgRecordsPerDay + (2 * summary.stdDev);
            
            for (Map.Entry<String, Integer> entry : dailyCounts.entrySet()) {
                String dateKey = entry.getKey();
                int count = entry.getValue();
                DailyStats stats = dailyStats.get(dateKey);
                
                if (stats != null && !stats.isWeekend) {
                    if (count < lowerThreshold) {
                        summary.anomalies.add(String.format("%s: LOW count (%,d records, %.1f%% of avg)", 
                            dateKey, count, (count * 100.0 / summary.avgRecordsPerDay)));
                    } else if (count > upperThreshold) {
                        summary.anomalies.add(String.format("%s: HIGH count (%,d records, %.1f%% of avg)", 
                            dateKey, count, (count * 100.0 / summary.avgRecordsPerDay)));
                    }
                }
            }
        }
        
        return summary;
    }
    
    private double calculateStdDev(List<Integer> values, double mean) {
        double sumSquaredDiff = 0.0;
        for (int value : values) {
            double diff = value - mean;
            sumSquaredDiff += diff * diff;
        }
        return Math.sqrt(sumSquaredDiff / values.size());
    }
    
    private void printMonthSummary(MonthSummary summary) {
        System.out.println("\n>>> " + summary.monthName);
        System.out.println("  Total Records      : " + String.format("%,d", summary.totalRecords));
        System.out.println("  Trading Days       : " + summary.tradingDays);
        System.out.println("  Avg Records/Day    : " + String.format("%,.2f", summary.avgRecordsPerDay));
        System.out.println("  Std Deviation      : " + String.format("%,.2f", summary.stdDev));
        System.out.println("  Min Records        : " + String.format("%,d", summary.minRecords));
        System.out.println("  Max Records        : " + String.format("%,d", summary.maxRecords));
        
        if (!summary.anomalies.isEmpty()) {
            System.out.println("  ⚠ ANOMALIES DETECTED:");
            for (String anomaly : summary.anomalies) {
                System.out.println("     • " + anomaly);
            }
        } else {
            System.out.println("  ✓ No significant anomalies detected");
        }
    }
    
    private void printOverallSummary(long executionTime) {
        System.out.println("\n" + "=".repeat(100));
        System.out.println("OVERALL SUMMARY");
        System.out.println("=".repeat(100));
        
        int totalDays = dailyStats.size();
        int totalRecords = dailyStats.values().stream()
            .mapToInt(s -> s.count)
            .sum();
        
        long tradingDays = dailyStats.values().stream()
            .filter(s -> !s.isWeekend && s.count > 0)
            .count();
        
        long weekendsWithData = dailyStats.values().stream()
            .filter(s -> s.isWeekend && s.count > 0)
            .count();
        
        System.out.println("  Total Days Analyzed        : " + totalDays);
        System.out.println("  Total NIFTY-I.NFO Records  : " + String.format("%,d", totalRecords));
        System.out.println("  Trading Days with Data     : " + tradingDays);
        System.out.println("  Weekends with Data         : " + weekendsWithData + 
            (weekendsWithData > 0 ? " ⚠" : " ✓"));
        System.out.println("  File Issues Found          : " + fileIssues.size() + 
            (fileIssues.size() > 0 ? " ⚠" : " ✓"));
        System.out.println("  Execution Time             : " + String.format("%.2f", executionTime/1000.0) + " seconds");
        
        System.out.println("\n" + "=".repeat(100));
        System.out.println("Analysis Complete!");
        System.out.println("=".repeat(100));
    }
}