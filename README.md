# NovaQuant (Spring Boot 3.3.4 • WAR • JSP • JPA + SQLite)

Minimal working template with a single JSP page (no redirects) and a SQLite-backed JPA entity.

## Build & Run
```bash
mvn -q clean package
java -jar target/novaquant-0.0.1-SNAPSHOT.war
```

Open http://localhost:8080/kite-auth

## Notes
- JSP lives at `src/main/webapp/WEB-INF/views/kiteAuthSingle.jsp`
- WAR packaging is required for reliable JSP on Boot 3 (Tomcat 10)
- SQLite DB file will be created at `./data/app.db` relative to your working directory
- Entity: `KiteAuthDetails` with `authDate` auto-set; repository prevents duplicate saves for the same day
