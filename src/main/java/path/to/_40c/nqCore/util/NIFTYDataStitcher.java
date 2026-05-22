package path.to._40c.nqCore.util;
import java.io.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NIFTYDataStitcher {
    
    private static final String TICKER = "NIFTY-I.NFO";
    private static final String BASE_PATH = "C:\\Users\\Vinoth M\\Downloads\\2024\\2024";
    private static final String OUTPUT_PATH = "C:\\Users\\Vinoth M\\Downloads\\2024\\NIFTY_CONTINUOUS_2024.csv";
    private static final Pattern FILENAME_PATTERN = Pattern.compile("GFDLNFO_BACKADJUSTED_(\\d{2})(\\d{2})(\\d{4})(?:\\.csv)?", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    
    private List<String> validationIssues = new ArrayList<>();
    private int totalRecordsWritten = 0;
    private LocalDateTime lastDateTime = null;
    
    private static class DailyRecord implements Comparable<DailyRecord> {
        String fullLine;
        LocalDateTime dateTime;
        
        DailyRecord(String line, String date, String time) throws Exception {
            this.fullLine = line;
            this.dateTime = LocalDateTime.parse(date + " " + time, 
                DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
        }
        
        @Override
        public int compareTo(DailyRecord other) {
            return this.dateTime.compareTo(other.dateTime);
        }
    }
    
    private static class FileInfo implements Comparable<FileInfo> {
        File file;
        LocalDate date;
        String monthFolder;
        
        FileInfo(File file, LocalDate date, String monthFolder) {
            this.file = file;
            this.date = date;
            this.monthFolder = monthFolder;
        }
        
        @Override
        public int compareTo(FileInfo other) {
            return this.date.compareTo(other.date);
        }
    }
    
    public static void main(String[] args) {
        NIFTYDataStitcher stitcher = new NIFTYDataStitcher();
        stitcher.stitchData();
    }
    
    /** Collects all daily files in chronological order, stitches their NIFTY-I rows into one CSV. */
    public void stitchData() {
        System.out.println("=".repeat(100));
        System.out.println("NIFTY-I.NFO CONTINUOUS DATA STITCHER");
        System.out.println("Base Path: " + BASE_PATH);
        System.out.println("Output File: " + OUTPUT_PATH);
        System.out.println("=".repeat(100));
        System.out.println();

        long startTime = System.currentTimeMillis();

        try {
            List<FileInfo> allFiles = collectAllFiles();

            if (allFiles.isEmpty()) {
                System.out.println("ERROR: No files found to process!");
                return;
            }

            System.out.println("Found " + allFiles.size() + " files to process");
            System.out.println("Date range: " + allFiles.get(0).date + " to " + allFiles.get(allFiles.size() - 1).date);
            System.out.println();

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(OUTPUT_PATH), 65536)) {
                for (FileInfo fileInfo : allFiles) {
                    processFile(fileInfo, writer);
                }
            }

            long endTime = System.currentTimeMillis();

            printResults(endTime - startTime);

        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private List<FileInfo> collectAllFiles() {
        List<FileInfo> allFiles = new ArrayList<>();
        
        String[] months = {
            "1_JAN", "2_FEB", "3_MAR", "4_APR", "5_MAY", "6_JUN",
            "7_JUL", "8_AUG", "9_SEP", "10_OCT", "11_NOV", "12_DEC"
        };
        
        for (String month : months) {
            File monthDir = new File(BASE_PATH, month);
            
            if (!monthDir.exists() || !monthDir.isDirectory()) {
                validationIssues.add("Month folder not found: " + month);
                continue;
            }
            
            File[] files = monthDir.listFiles();
            if (files == null) continue;
            
            for (File file : files) {
                if (!file.isFile()) continue;
                
                Matcher matcher = FILENAME_PATTERN.matcher(file.getName());
                if (!matcher.matches()) continue;
                
                try {
                    String day = matcher.group(1);
                    String monthNum = matcher.group(2);
                    String year = matcher.group(3);
                    
                    LocalDate date = LocalDate.parse(day + "-" + monthNum + "-" + year, 
                        DateTimeFormatter.ofPattern("dd-MM-yyyy"));
                    
                    allFiles.add(new FileInfo(file, date, month));

                } catch (Exception e) {
                    validationIssues.add("Error parsing date from file: " + file.getName() + " - " + e.getMessage());
                }
            }
        }

        Collections.sort(allFiles);

        return allFiles;
    }

    /**
     * Reads NIFTY-I.NFO rows from one daily file, sorts by intra-day time, validates against
     * the previous file's last timestamp (overlap / >4-day gap warnings), rewrites the ticker
     * column to NIFTY-I, and appends to the output. Gaps up to 4 days are tolerated for weekends/holidays.
     */
    private void processFile(FileInfo fileInfo, BufferedWriter writer) throws Exception {
        List<DailyRecord> dayRecords = new ArrayList<>();
        int recordCount = 0;

        try (BufferedReader br = new BufferedReader(new FileReader(fileInfo.file), 65536)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith(TICKER)) {
                    String[] parts = line.split(",");
                    if (parts.length >= 9) {
                        try {
                            String date = parts[1].trim();
                            String time = parts[2].trim();
                            String modifiedLine = line.replaceFirst("^NIFTY-I\\.NFO", "NIFTY-I");
                            dayRecords.add(new DailyRecord(modifiedLine, date, time));
                            recordCount++;
                        } catch (Exception e) {
                            validationIssues.add("Error parsing record in " + fileInfo.file.getName() + ": " + line);
                        }
                    }
                }
            }
        }

        if (dayRecords.isEmpty()) {
            validationIssues.add("No NIFTY-I.NFO records found in: " + fileInfo.file.getName());
            return;
        }

        Collections.sort(dayRecords);

        LocalDateTime firstTime = dayRecords.get(0).dateTime;
        LocalDateTime lastTime = dayRecords.get(dayRecords.size() - 1).dateTime;

        for (int i = 1; i < dayRecords.size(); i++) {
            if (dayRecords.get(i).dateTime.isBefore(dayRecords.get(i - 1).dateTime)) {
                validationIssues.add("Time sequence issue in " + fileInfo.file.getName() +
                    " at record " + i + ": " + dayRecords.get(i - 1).dateTime + " -> " + dayRecords.get(i).dateTime);
            }
        }

        if (lastDateTime != null) {
            if (firstTime.isBefore(lastDateTime) || firstTime.isEqual(lastDateTime)) {
                validationIssues.add("CRITICAL: Date/time overlap detected! " +
                    "Previous file ended at " + lastDateTime + ", current file starts at " + firstTime +
                    " (File: " + fileInfo.file.getName() + ")");
            }

            long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(lastDateTime.toLocalDate(), firstTime.toLocalDate());
            if (daysBetween > 4) {
                validationIssues.add("Large gap detected: " + daysBetween + " days between " +
                    lastDateTime.toLocalDate() + " and " + firstTime.toLocalDate());
            }
        }

        for (DailyRecord record : dayRecords) {
            writer.write(record.fullLine);
            writer.newLine();
            totalRecordsWritten++;
        }
        
        lastDateTime = lastTime;
        
        System.out.printf("✓ Processed: %s | Date: %s | Records: %,5d | Time range: %s - %s%n",
            fileInfo.monthFolder, fileInfo.date, recordCount, 
            firstTime.format(TIME_FORMATTER), lastTime.format(TIME_FORMATTER));
    }
    
    private void printResults(long executionTime) {
        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println("STITCHING COMPLETE");
        System.out.println("=".repeat(100));
        System.out.println("  Output File         : " + OUTPUT_PATH);
        System.out.println("  Total Records       : " + String.format("%,d", totalRecordsWritten));
        System.out.println("  Execution Time      : " + String.format("%.2f", executionTime / 1000.0) + " seconds");
        System.out.println("  Validation Issues   : " + validationIssues.size());
        
        if (!validationIssues.isEmpty()) {
            System.out.println();
            System.out.println("=".repeat(100));
            System.out.println("VALIDATION ISSUES DETECTED:");
            System.out.println("=".repeat(100));
            for (String issue : validationIssues) {
                System.out.println("  ⚠ " + issue);
            }
        } else {
            System.out.println();
            System.out.println("✓ No validation issues detected - data is perfectly sequential!");
        }
        
        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println("Data stitching successful! File ready for backtesting.");
        System.out.println("=".repeat(100));
    }
}