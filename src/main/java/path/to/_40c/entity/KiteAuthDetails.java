package path.to._40c.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import jakarta.persistence.*;

import static path.to._40c.util.Constants.ZONE_ID;
import static path.to._40c.util.Constants.DATE_FORMAT;

@Entity
@Table(name = "KITE_AUTH_DETAILS")
public class KiteAuthDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "APP_NAME")
    private String appName;

    @Column(name = "API_KEY")
    private String apiKey;

    @Column(name = "API_SECRET")
    private String apiSecret;

    @Column(name = "REQUEST_TOKEN")
    private String requestToken;

    @Column(name = "ACCESS_TOKEN")
    private String accessToken;

    @Column(name = "PUBLIC_TOKEN")
    private String publicToken;

    @Column(name = "CREATED_DATE")
    private String createdDate;

    @Column(name = "AUTH_DATE")
    private LocalDate authDate;

    public KiteAuthDetails() {
        this.appName = "jasakavi";
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getApiSecret() { return apiSecret; }
    public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }
    public String getRequestToken() { return requestToken; }
    public void setRequestToken(String requestToken) { this.requestToken = requestToken; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getPublicToken() { return publicToken; }
    public void setPublicToken(String publicToken) { this.publicToken = publicToken; }
    public String getCreatedDate() { return createdDate; }
    public void setCreatedDate(String createdDate) { this.createdDate = createdDate; }
    public LocalDate getAuthDate() { return authDate; }
    public void setAuthDate(LocalDate authDate) { this.authDate = authDate; }

    @PrePersist
    public void onCreate() {
        this.createdDate = LocalDateTime.now(ZoneId.of(ZONE_ID))
            .format(DateTimeFormatter.ofPattern(DATE_FORMAT));
        this.authDate = LocalDate.now(ZoneId.of(ZONE_ID));
    }
}
