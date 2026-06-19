package path.to._40c.nqCore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NQCore {
  public static void main(String[] args) {
    SpringApplication.run(NQCore.class, args);
  }
}
