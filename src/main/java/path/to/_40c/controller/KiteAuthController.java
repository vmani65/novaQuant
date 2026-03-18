package path.to._40c.controller;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import path.to._40c.entity.KiteAuthDetails;
import path.to._40c.entity.PositionSizeMatrix;
import path.to._40c.repo.ContractPriorityRepository;
import path.to._40c.repo.KiteAuthDetailsRepository;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import path.to._40c.service.KiteAuthService;
import path.to._40c.service.SymbolService;
import path.to._40c.service.ContractPriorityCache;

import org.springframework.http.MediaType;

@Controller
@RequestMapping
public class KiteAuthController {

    private static final Logger log = LoggerFactory.getLogger(KiteAuthController.class);

	@Autowired
    private KiteAuthDetailsRepository repository;
    
    @Autowired
    private KiteAuthService kiteAuthService;

    @Autowired
    private SymbolService symbolService;
    
    @Autowired
    ContractPriorityRepository matrixRepository;
    
    @Autowired
    private ContractPriorityCache contractCache;
    
    @PostMapping
    public ResponseEntity<KiteAuthDetails> saveAuth(@RequestBody KiteAuthDetails authDetails) {
        KiteAuthDetails saved = repository.save(authDetails);
        return ResponseEntity.ok(saved);
    }
   
    @PostMapping("/kite-auth/save")
    @Transactional
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveKiteAuth(@RequestParam String requestToken) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        Optional<KiteAuthDetails> existing = repository.findByAuthDate(today);
        contractCache.getLongPriorities().forEach(l -> log.info(l.toString()));
        contractCache.getShortPriorities().forEach(s -> log.info(s.toString()));
        if (existing.isPresent())
            return ResponseEntity.ok(Map.of("success", false, "message", "Auth details already saved for today."));
        String status = kiteAuthService.saveKiteAuth(requestToken);
        if ("SUCCESS".equals(status))
            return ResponseEntity.ok(Map.of("success", true, "message", "Auth details saved successfully."));
        return ResponseEntity.ok(Map.of("success", false, "message", status));
    }
    
    @GetMapping("/kite-auth/get-login-url")
    @ResponseBody
    public String getLoginUrl() {
        return kiteAuthService.getLoginUrl();
    }
    
    @PostMapping("/symbol/save")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> save(@RequestParam("thisWeekSymbol") String thisWeekSymbol, @RequestParam("rolloverSymbol") String rolloverSymbol) {
        symbolService.saveSymbols(thisWeekSymbol.trim(), rolloverSymbol.trim());
        return ResponseEntity.ok(Map.of("success", true, "message", "Symbols saved."));
    }
    
    @GetMapping(value = "/getNiftyInstruments", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<String> getNiftyInstruments() {    	
        return kiteAuthService.getNiftyInstruments();
    }
    
    @GetMapping("/signalHome")
    public String showForm(Model model) {
    	model.addAttribute("symbols", symbolService.get().orElse(null));
        model.addAttribute("noSymbols", symbolService.isMissing());
        PositionSizeMatrix longBuy = matrixRepository.findByPositionSide("LONG").stream()
                .filter(r -> "BUY".equals(r.getActionType())).findFirst().orElse(null);
        PositionSizeMatrix shortBuy = matrixRepository.findByPositionSide("SHORT").stream()
                .filter(r -> "BUY".equals(r.getActionType())).findFirst().orElse(null);
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
            new PositionSizeMatrix("LONG",  "CE", "BUY",  longAtm,  longOffset1,  longOffset2,  longOffset3),
            new PositionSizeMatrix("LONG",  "PE", "SELL", longAtm,  longOffset1,  longOffset2,  longOffset3),
            new PositionSizeMatrix("SHORT", "PE", "BUY",  shortAtm, shortOffset1, shortOffset2, shortOffset3),
            new PositionSizeMatrix("SHORT", "CE", "SELL", shortAtm, shortOffset1, shortOffset2, shortOffset3)
        ));
        contractCache.refreshCache();
        log.info("Matrix saved and cache refreshed.");
        return ResponseEntity.ok(Map.of("success", true, "message", "Position size saved."));
    }
}