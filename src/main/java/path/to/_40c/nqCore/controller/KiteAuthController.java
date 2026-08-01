package path.to._40c.nqCore.controller;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.CE;
import static path.to._40c.nqCore.util.Constants.LONG;
import static path.to._40c.nqCore.util.Constants.PE;
import static path.to._40c.nqCore.util.Constants.SELL;
import static path.to._40c.nqCore.util.Constants.SHORT;
import static path.to._40c.nqCore.util.Constants.ZONE_ID;

import path.to._40c.nqCore.entity.KiteAuthDetails;
import path.to._40c.nqCore.entity.LegTemplate;
import path.to._40c.nqCore.repo.LegTemplateRepository;
import path.to._40c.nqCore.repo.KiteAuthDetailsRepository;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import path.to._40c.nqCore.gateway.KiteGateway;
import path.to._40c.nqCore.service.KiteAuthService;
import path.to._40c.nqCore.service.WeeklySymbolService;
import path.to._40c.nqCore.service.LegTemplateCache;

@Controller
@RequestMapping
@Slf4j
public class KiteAuthController {
    private final KiteAuthDetailsRepository repository;
    private final KiteAuthService kiteAuthService;
    private final WeeklySymbolService weeklySymbolService;
    private final LegTemplateRepository legTemplateRepository;
    private final LegTemplateCache legTemplateCache;
    private final KiteGateway kiteGateway;

    public KiteAuthController(KiteAuthDetailsRepository repository, KiteAuthService kiteAuthService,
            WeeklySymbolService weeklySymbolService, LegTemplateRepository legTemplateRepository,
            LegTemplateCache legTemplateCache, KiteGateway kiteGateway) {
        this.repository = repository;
        this.kiteAuthService = kiteAuthService;
        this.weeklySymbolService = weeklySymbolService;
        this.legTemplateRepository = legTemplateRepository;
        this.legTemplateCache = legTemplateCache;
        this.kiteGateway = kiteGateway;
    }

    @PostMapping
    public ResponseEntity<KiteAuthDetails> saveAuth(@RequestBody KiteAuthDetails authDetails) {
        KiteAuthDetails saved = repository.save(authDetails);
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/kite-auth/save")
    @Transactional
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveKiteAuth(@RequestParam String requestToken) {
        LocalDate today = LocalDate.now(ZoneId.of(ZONE_ID));
        Optional<KiteAuthDetails> existing = repository.findByAuthDate(today);
        if (existing.isPresent())
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("success", false, "message", "Auth details already saved for today."));
        String status = kiteAuthService.saveKiteAuth(requestToken);
        if ("SUCCESS".equals(status))
            return ResponseEntity.ok(Map.of("success", true, "message", "Auth details saved successfully."));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "message", status));
    }

    @GetMapping("/kite-auth/get-login-url")
    @ResponseBody
    public String getLoginUrl() {
        return kiteAuthService.getLoginUrl();
    }

    @PostMapping("/symbol/save")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> save(
            @RequestParam String thisWeekSymbol,
            @RequestParam String rolloverSymbol,
            @RequestParam(required = false) String rolloverDay) {
        weeklySymbolService.saveSymbols(thisWeekSymbol.trim(), rolloverSymbol.trim(), rolloverDay);
        return ResponseEntity.ok(Map.of("success", true, "message", "Symbols saved."));
    }

    @GetMapping(value = "/getNiftyInstruments", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<String> getNiftyInstruments() {
        return kiteAuthService.getNiftyInstruments();
    }

    @GetMapping("/check-server-ip")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> checkServerIp() {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.ipify.org"))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String ip = response.body().trim();
            log.info("Server outbound IP: {}", ip);
            return ResponseEntity.ok(Map.of("serverIp", ip,
                    "message", "Register this IP at developers.kite.trade → Profile → IP Whitelist"));
        } catch (Exception e) {
            log.error("Failed to fetch server IP", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/kite-auth/test-connection")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> testConnection() {
        LocalDate today = LocalDate.now(ZoneId.of(ZONE_ID));
        Optional<KiteAuthDetails> existing = repository.findByAuthDate(today);
        if (existing.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                "status", "NO_AUTH",
                "message", "No auth token saved for today (" + today + "). Login and save your request token first."
            ));
        }
        log.info("Testing Kite connection for date={}", today);
        Map<String, Object> result = kiteGateway.testConnection();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/signalHome")
    public String showForm(Model model) {
    	model.addAttribute("symbols", weeklySymbolService.get().orElse(null));
        model.addAttribute("noSymbols", weeklySymbolService.isMissing());
        return "signalHome";
    }

    // ---------------------------------------------------------------
    // Leg-template CRUD — backs the "manifestation" UI for editing the
    // legs that fire on each LONG / SHORT signal. Cache refreshed after
    // every mutation so the next signal sees the new template.
    // ---------------------------------------------------------------

    @GetMapping("/api/leg-templates")
    @ResponseBody
    public List<LegTemplate> listLegTemplates() {
        return legTemplateRepository.findAllByOrderByDirectionAscOptionTypeAscOffsetPtsAsc();
    }

    @PostMapping("/api/leg-templates")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createLegTemplate(@RequestBody LegTemplate body) {
        Map<String, Object> err = validate(body);
        if (err != null) return ResponseEntity.badRequest().body(err);
        body.setId(null);
        body.setStrategyName(normalizeStrategyName(body.getStrategyName()));
        LegTemplate saved = legTemplateRepository.save(body);
        legTemplateCache.refreshCache();
        log.info("Leg template created: {}", saved);
        return ResponseEntity.ok(Map.of("success", true, "data", saved));
    }

    @PutMapping("/api/leg-templates/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateLegTemplate(@PathVariable Long id, @RequestBody LegTemplate body) {
        return legTemplateRepository.findById(id)
            .<ResponseEntity<Map<String, Object>>>map(existing -> {
                Map<String, Object> err = validate(body);
                if (err != null) return ResponseEntity.badRequest().body(err);
                existing.setDirection(body.getDirection());
                existing.setOptionType(body.getOptionType());
                existing.setSide(body.getSide());
                existing.setOffsetPts(body.getOffsetPts());
                existing.setLots(body.getLots());
                existing.setStrategyName(normalizeStrategyName(body.getStrategyName()));
                LegTemplate saved = legTemplateRepository.save(existing);
                legTemplateCache.refreshCache();
                log.info("Leg template updated: {}", saved);
                return ResponseEntity.ok(Map.of("success", true, "data", saved));
            })
            .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("success", false, "message", "Leg template id=" + id + " not found")));
    }

    @DeleteMapping("/api/leg-templates/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteLegTemplate(@PathVariable Long id) {
        if (!legTemplateRepository.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("success", false, "message", "Leg template id=" + id + " not found"));
        }
        legTemplateRepository.deleteById(id);
        legTemplateCache.refreshCache();
        log.info("Leg template deleted: id={}", id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** Blank strategy names collapse to null = the shared default template set. */
    private static String normalizeStrategyName(String strategyName) {
        if (strategyName == null || strategyName.isBlank()) return null;
        return strategyName.trim();
    }

    private Map<String, Object> validate(LegTemplate t) {
        if (t == null) return Map.of("success", false, "message", "Empty body");
        if (!LONG.equals(t.getDirection()) && !SHORT.equals(t.getDirection()))
            return Map.of("success", false, "message", "direction must be LONG or SHORT");
        if (!CE.equals(t.getOptionType()) && !PE.equals(t.getOptionType()))
            return Map.of("success", false, "message", "optionType must be CE or PE");
        if (!BUY.equals(t.getSide()) && !SELL.equals(t.getSide()))
            return Map.of("success", false, "message", "side must be BUY or SELL");
        if (t.getLots() == null || t.getLots() <= 0)
            return Map.of("success", false, "message", "lots must be > 0");
        if (t.getOffsetPts() == null) t.setOffsetPts(0);
        return null;
    }
}
