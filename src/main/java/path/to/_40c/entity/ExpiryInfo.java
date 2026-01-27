package path.to._40c.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "expiry_info")
public class ExpiryInfo {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "current_weekly_expiry", nullable = false, length = 20)
    private String currentWeeklyExpiry;
    
    @Column(name = "current_weekly_expiry_day", nullable = false, length = 10)
    private String currentWeeklyExpiryDay;
    
    @Column(name = "next_weekly_expiry", length = 20)
    private String nextWeeklyExpiry;
    
    @Column(name = "next_weekly_expiry_day", length = 10)
    private String nextWeeklyExpiryDay;
    
    @Column(name = "current_monthly_expiry", length = 20)
    private String currentMonthlyExpiry;
    
    @Column(name = "current_monthly_expiry_day", length = 10)
    private String currentMonthlyExpiryDay;
    
    @Column(name = "next_monthly_expiry", length = 20)
    private String nextMonthlyExpiry;
    
    @Column(name = "next_monthly_expiry_day", length = 10)
    private String nextMonthlyExpiryDay;
    
    @Column(name = "last_updated", nullable = false)
    private String lastUpdated;
    
    public ExpiryInfo() {}
    
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getCurrentWeeklyExpiry() {
        return currentWeeklyExpiry;
    }
    
    public void setCurrentWeeklyExpiry(String currentWeeklyExpiry) {
        this.currentWeeklyExpiry = currentWeeklyExpiry;
    }
    
    public String getCurrentWeeklyExpiryDay() {
        return currentWeeklyExpiryDay;
    }
    
    public void setCurrentWeeklyExpiryDay(String currentWeeklyExpiryDay) {
        this.currentWeeklyExpiryDay = currentWeeklyExpiryDay;
    }
    
    public String getNextWeeklyExpiry() {
        return nextWeeklyExpiry;
    }
    
    public void setNextWeeklyExpiry(String nextWeeklyExpiry) {
        this.nextWeeklyExpiry = nextWeeklyExpiry;
    }
    
    public String getNextWeeklyExpiryDay() {
        return nextWeeklyExpiryDay;
    }
    
    public void setNextWeeklyExpiryDay(String nextWeeklyExpiryDay) {
        this.nextWeeklyExpiryDay = nextWeeklyExpiryDay;
    }
    
    public String getCurrentMonthlyExpiry() {
        return currentMonthlyExpiry;
    }
    
    public void setCurrentMonthlyExpiry(String currentMonthlyExpiry) {
        this.currentMonthlyExpiry = currentMonthlyExpiry;
    }
    
    public String getCurrentMonthlyExpiryDay() {
        return currentMonthlyExpiryDay;
    }
    
    public void setCurrentMonthlyExpiryDay(String currentMonthlyExpiryDay) {
        this.currentMonthlyExpiryDay = currentMonthlyExpiryDay;
    }
    
    public String getNextMonthlyExpiry() {
        return nextMonthlyExpiry;
    }
    
    public void setNextMonthlyExpiry(String nextMonthlyExpiry) {
        this.nextMonthlyExpiry = nextMonthlyExpiry;
    }
    
    public String getNextMonthlyExpiryDay() {
        return nextMonthlyExpiryDay;
    }
    
    public void setNextMonthlyExpiryDay(String nextMonthlyExpiryDay) {
        this.nextMonthlyExpiryDay = nextMonthlyExpiryDay;
    }
    
    public String getLastUpdated() {
        return lastUpdated;
    }
    
    public void setLastUpdated(String lastUpdated) {
        this.lastUpdated = lastUpdated;
    }
        
    @Override
    public String toString() {
        return String.format("ExpiryInfo{" +
                "currentWeekly='%s (%s)', nextWeekly='%s (%s)', " +
                "currentMonthly='%s (%s)', nextMonthly='%s (%s)', " +
                "lastUpdated=%s}",
                currentWeeklyExpiry, currentWeeklyExpiryDay,
                nextWeeklyExpiry, nextWeeklyExpiryDay,
                currentMonthlyExpiry, currentMonthlyExpiryDay,
                nextMonthlyExpiry, nextMonthlyExpiryDay,
                lastUpdated);
    }
}

