package path.to._40c.controller;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;

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
    public String saveKiteAuth(@RequestParam String requestToken, RedirectAttributes redirectAttributes) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        Optional<KiteAuthDetails> existing = repository.findByAuthDate(today);

        contractCache.getLongPriorities().forEach(l -> log.info(l.toString()));
        contractCache.getShortPriorities().forEach(s -> log.info(s.toString()));

        if (existing.isPresent()) {
            redirectAttributes.addFlashAttribute("error", "Auth details already saved for today.");
        } else {
            String status = kiteAuthService.saveKiteAuth(requestToken);
            if ("SUCCESS".equals(status))
                redirectAttributes.addFlashAttribute("success", "Auth details saved successfully.");
            else
                redirectAttributes.addFlashAttribute("error", status);            
        }
        return "redirect:/signalHome";
    }
    
    @GetMapping("/kite-auth/get-login-url")
    @ResponseBody
    public String getLoginUrl() {
        return kiteAuthService.getLoginUrl();
    }
    
    @PostMapping("/symbol/save")
    public String save(@RequestParam("thisWeekSymbol") String thisWeekSymbol, @RequestParam("rolloverSymbol") String rolloverSymbol, RedirectAttributes ra) {
        symbolService.saveSymbols(thisWeekSymbol.trim(), rolloverSymbol.trim());
        ra.addFlashAttribute("success", "Symbols saved.");
        ra.addFlashAttribute("source", "symbols");
        return "redirect:/signalHome";
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
        List<PositionSizeMatrix> rows = matrixRepository.findAll(Sort.by("id"));
        model.addAttribute("positionMatrix", rows);
        return "signalHome";
    }

    @PostMapping("/position-matrix/save")
    public String saveMatrix(@ModelAttribute PositionMatrixForm form, RedirectAttributes ra) {
        if (form.getMatrix() != null && !form.getMatrix().isEmpty()) {
        	matrixRepository.saveAll(form.getMatrix());
        	contractCache.refreshCache();
        }
        ra.addFlashAttribute("matrixSuccess", "Position size matrix saved.");
        ra.addFlashAttribute("source", "matrix");

        return "redirect:/signalHome";
    }
    
    public static class PositionMatrixForm {
        private List<PositionSizeMatrix> matrix;

        public List<PositionSizeMatrix> getMatrix() {
            return matrix;
        }

        public void setMatrix(List<PositionSizeMatrix> matrix) {
            this.matrix = matrix;
        }
    }
}