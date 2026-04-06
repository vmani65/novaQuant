package path.to._40c.controller;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static path.to._40c.util.Constants.BUY;
import static path.to._40c.util.Constants.CE;
import static path.to._40c.util.Constants.LONG;
import static path.to._40c.util.Constants.PE;
import static path.to._40c.util.Constants.SELL;
import static path.to._40c.util.Constants.SHORT;
import static path.to._40c.util.Constants.ZONE_ID;

import path.to._40c.entity.KiteAuthDetails;
import path.to._40c.entity.PositionSizeMatrix;
import path.to._40c.repo.TradeLegConfigRepository;
import path.to._40c.repo.KiteAuthDetailsRepository;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import path.to._40c.gateway.KiteGateway;
import path.to._40c.service.KiteAuthService;
import path.to._40c.service.SymbolService;
import path.to._40c.service.TradeLegCache;

import org.springframework.http.MediaType;

@Controller
@RequestMapping
public class KiteAuthController {

    private static final Logger log = LoggerFactory.getLogger(KiteAuthController.class);

    private final KiteAuthDetailsRepository repository;
    private final KiteAuthService kiteAuthService;
    private final SymbolService symbolService;
    private final TradeLegConfigRepository matrixRepository;
    private final TradeLegCache contractCache;
    private final KiteGateway kiteGateway;

    public KiteAuthController(KiteAuthDetailsRepository repository, KiteAuthService kiteAuthService,
            SymbolService symbolService, TradeLegConfigRepository matrixRepository,
            TradeLegCache contractCache, KiteGateway kiteGateway) {
        this.repository = repository;
        this.kiteAuthService = kiteAuthService;
        this.symbolService = symbolService;
        this.matrixRepository = matrixRepository;
        this.contractCache = contractCache;
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
        symbolService.saveSymbols(thisWeekSymbol.trim(), rolloverSymbol.trim(), rolloverDay);
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
    	model.addAttribute("symbols", symbolService.get().orElse(null));
        model.addAttribute("noSymbols", symbolService.isMissing());
        PositionSizeMatrix longBuy = matrixRepository.findByPositionSide(LONG).stream()
                .filter(r -> BUY.equals(r.getActionType())).findFirst().orElse(null);
        PositionSizeMatrix shortBuy = matrixRepository.findByPositionSide(SHORT).stream()
                .filter(r -> BUY.equals(r.getActionType())).findFirst().orElse(null);
        model.addAttribute("longAtm",     longBuy  != null && longBuy.getAtm()     != null ? longBuy.getAtm()     : 0);
        model.addAttribute("longOffset1", longBuy  != null && longBuy.getOffset1() != null ? longBuy.getOffset1() : 0);
        model.addAttribute("longOffset2", longBuy  != null && longBuy.getOffset2() != null ? longBuy.getOffset2() : 0);
        model.addAttribute("longOffset3", longBuy  != null && longBuy.getOffset3() != null ? longBuy.getOffset3() : 0);
        model.addAttribute("shortAtm",    shortBuy != null && shortBuy.getAtm()     != null ? shortBuy.getAtm()     : 0);
        model.addAttribute("shortOffset1",shortBuy != null && shortBuy.getOffset1() != null ? shortBuy.getOffset1() : 0);
        model.addAttribute("shortOffset2",shortBuy != null && shortBuy.getOffset2() != null ? shortBuy.getOffset2() : 0);
        model.addAttribute("shortOffset3",shortBuy != null && shortBuy.getOffset3() != null ? shortBuy.getOffset3() : 0);
        return "signalHome";
    }

    @PostMapping("/position-matrix/save")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveMatrix(
            @RequestParam Integer longAtm,     @RequestParam Integer longOffset1,
            @RequestParam Integer longOffset2, @RequestParam Integer longOffset3,
            @RequestParam Integer shortAtm,    @RequestParam Integer shortOffset1,
            @RequestParam Integer shortOffset2,@RequestParam Integer shortOffset3) {
        log.info("Saving position matrix: LONG atm={} o1={} o2={} o3={} | SHORT atm={} o1={} o2={} o3={}",
                 longAtm, longOffset1, longOffset2, longOffset3, shortAtm, shortOffset1, shortOffset2, shortOffset3);
        matrixRepository.deleteAll();
        matrixRepository.saveAll(List.of(
            new PositionSizeMatrix(LONG,  CE, BUY,  longAtm,  longOffset1,  longOffset2,  longOffset3),
            new PositionSizeMatrix(LONG,  PE, SELL, longAtm,  longOffset1,  longOffset2,  longOffset3),
            new PositionSizeMatrix(SHORT, PE, BUY,  shortAtm, shortOffset1, shortOffset2, shortOffset3),
            new PositionSizeMatrix(SHORT, CE, SELL, shortAtm, shortOffset1, shortOffset2, shortOffset3)
        ));
        contractCache.refreshCache();
        log.info("Matrix saved and cache refreshed.");
        return ResponseEntity.ok(Map.of("success", true, "message", "Position size saved."));
    }
}
