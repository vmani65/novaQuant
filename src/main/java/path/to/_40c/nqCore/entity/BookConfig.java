package path.to._40c.nqCore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Per-book enable/disable switch. One row per execution book (SYNTH_WEEKLY, LONG_MONTHLY),
 * seeded at startup by BookConfigService. Disable semantics: a disabled book opens no NEW
 * positions; an already-open position keeps being managed (exits and flips still close it)
 * until its natural close, then the book goes dormant.
 */
@Entity
@Table(name = "BOOK_CONFIG")
@Getter
@Setter
@ToString
public class BookConfig {

    @Id
    @Column(name = "BOOK", length = 15)
    private String book;

    @Column(name = "ENABLED", nullable = false)
    private Boolean enabled = Boolean.FALSE;

    public BookConfig() {}

    public BookConfig(String book, boolean enabled) {
        this.book = book;
        this.enabled = enabled;
    }
}
