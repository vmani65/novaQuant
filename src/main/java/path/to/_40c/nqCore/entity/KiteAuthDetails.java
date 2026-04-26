package path.to._40c.nqCore.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import static path.to._40c.nqCore.util.Constants.ZONE_ID;
import static path.to._40c.nqCore.util.Constants.DATE_FORMAT;

@Entity
@Table(name = "KITE_AUTH_DETAILS")
@Getter
@Setter
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

    @PrePersist
    public void onCreate() {
        this.createdDate = LocalDateTime.now(ZoneId.of(ZONE_ID))
            .format(DateTimeFormatter.ofPattern(DATE_FORMAT));
        this.authDate = LocalDate.now(ZoneId.of(ZONE_ID));
    }
}
