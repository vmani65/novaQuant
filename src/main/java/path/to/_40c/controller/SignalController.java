package path.to._40c.controller;

import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import path.to._40c.entity.Trade;
import path.to._40c.service.SignalService;
import path.to._40c.util.ComputeUtil;

import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.Map;
import java.time.Instant;
import java.time.ZoneId;
import java.time.Duration;

import static path.to._40c.util.Constants.IST_FORMATTER;

@RestController
@RequestMapping("/api")
public class SignalController {

    private static final Logger log = LoggerFactory.getLogger(SignalController.class);

    private final Map<String, Instant> signalCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> duplicateCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastLogDuplicate = new ConcurrentHashMap<>();
    private static final Duration CACHE_TTL = Duration.ofDays(60);
    private static final Duration LOG_THROTTLE = Duration.ofMinutes(1);
    private final Map<String, LastProcessed> previousByStrategy = new ConcurrentHashMap<>();
    private final SignalService signalService;
    private final ComputeUtil util;
    
    public SignalController(SignalService signalService, ComputeUtil util) {
        this.signalService = signalService;
        this.util = util;
    }

    @GetMapping("/flip")
    public void handleFlip(@RequestParam("signalType") String signalType, @RequestParam("currentPrice") String currentPrice, @RequestParam("strategyName") String strategyName, @RequestParam("time") String time) {
        handleSignal(Action.FLIP, signalType, currentPrice.replace(",", ""), strategyName, util.toStd(time));
    }

    @GetMapping("/longEntry")
    public void handleLongEntry(@RequestParam("signalType") String signalType, @RequestParam("currentPrice") String currentPrice, @RequestParam("strategyName") String strategyName, @RequestParam("time") String time) {
        handleSignal(Action.LONG_ENTRY, signalType, currentPrice.replace(",", ""), strategyName, util.toStd(time));
    }

    @GetMapping("/longExit")
    public void handleLongExit(@RequestParam("signalType") String signalType, @RequestParam("currentPrice") String currentPrice, @RequestParam("strategyName") String strategyName, @RequestParam("time") String time) {
        handleSignal(Action.LONG_EXIT, signalType, currentPrice.replace(",", ""), strategyName, util.toStd(time));
    }

    @GetMapping("/shortEntry")
    public void handleShortEntry(@RequestParam("signalType") String signalType, @RequestParam("currentPrice") String currentPrice, @RequestParam("strategyName") String strategyName, @RequestParam("time") String time) {
        handleSignal(Action.SHORT_ENTRY, signalType, currentPrice.replace(",", ""), strategyName, util.toStd(time));
    }

    @GetMapping("/shortExit")
    public void handleShortExit(@RequestParam("signalType") String signalType, @RequestParam("currentPrice") String currentPrice, @RequestParam("strategyName") String strategyName, @RequestParam("time") String time) {
        handleSignal(Action.SHORT_EXIT, signalType, currentPrice.replace(",", ""), strategyName, util.toStd(time));
    }

    @GetMapping("/rollover")
    public boolean handleRollOver(@RequestParam("signalPrice") String signalPrice) {
        signalService.handleRollOver(signalPrice.replace(",", ""));
        return true;
    }

    private void handleSignal(Action action, String signalType, String currentPrice, String strategyName, String time) {
        if (isDuplicate(strategyName, action.getValue(), signalType, time)) return;
        if (!isValidSequence(action, strategyName, LegType.fromString(signalType), currentPrice)) return;
        
        LastProcessed prev = previousByStrategy.get(strategyName);
        logAcceptedWithPrev(strategyName, action.getValue(), signalType, time, currentPrice, prev);
        var s = new Signal(strategyName, action.getValue(), signalType, time, currentPrice);
        boolean success = true;
        switch (action) {
	    	case FLIP -> success = signalService.handleFlip(currentPrice, signalType, s);
	    	case LONG_ENTRY, SHORT_ENTRY -> success = signalService.handleTradeOpen(currentPrice, signalType, s);
	    	case LONG_EXIT, SHORT_EXIT -> success = signalService.handleTradeClose(currentPrice, signalType, s);
        }
        if (success) {
            updatePrevious(strategyName, action.getValue(), signalType, time, currentPrice);
        }
    }

    private boolean isValidSequence(Action action, String strategyName, LegType legType, String currentPrice) {
        LastProcessed prev = previousByStrategy.get(strategyName);
        if (prev == null) return true;

        Action prevAction = Action.fromString(prev.action);
        LegType prevLegType = LegType.fromString(prev.signalType);
        if (prevAction == null || prevLegType == null) return true;

        boolean isInvalid = false;
        String reason = "";

        switch (action) {
            case FLIP:
                if (legType == LegType.CE) {
                    isInvalid = ((prevAction == Action.FLIP && prevLegType == LegType.CE) || (prevAction == Action.LONG_ENTRY && prevLegType == LegType.CE));
                    reason = "FLIP CE can only follow flip(PE) or shortEntry(PE) or shortExit(PE) or longExit(CE)";
                } else if (legType == LegType.PE) {
                	isInvalid = ((prevAction == Action.FLIP && prevLegType == LegType.PE) || (prevAction == Action.SHORT_ENTRY && prevLegType == LegType.PE));
                    reason = "FLIP PE can only follow flip(CE) or longEntry(CE) or longExit(CE) or shortExit(PE)";
                }
                break;

            case LONG_ENTRY:
                isInvalid = (prevAction == Action.LONG_ENTRY ||
                           	prevAction == Action.SHORT_ENTRY ||
                           (prevAction == Action.FLIP && prevLegType == LegType.PE) ||
                           (prevAction == Action.FLIP && prevLegType == LegType.CE));
                reason = "longEntry can follow only longExit(CE) or shortExit(PE)";
                break;

            case LONG_EXIT:
                isInvalid = (prevAction == Action.LONG_EXIT ||
                			prevAction == Action.SHORT_ENTRY ||
                			prevAction == Action.SHORT_EXIT ||
                			(prevAction == Action.FLIP && prevLegType == LegType.PE));
                reason = "longExit can follow only flip(CE) or longEntry(CE)";
                break;

            case SHORT_ENTRY:
                isInvalid = (prevAction == Action.LONG_ENTRY ||
                            prevAction == Action.SHORT_ENTRY ||
                            (prevAction == Action.FLIP && prevLegType == LegType.PE) ||
                            (prevAction == Action.FLIP && prevLegType == LegType.CE));
                reason = "shortEntry can follow only longExit(CE) or shortExit(PE)";
                break;

            case SHORT_EXIT:
                isInvalid = prevAction == Action.LONG_ENTRY ||
                            prevAction == Action.LONG_EXIT ||
                            prevAction == Action.SHORT_EXIT ||
                           (prevAction == Action.FLIP && prevLegType == LegType.CE);
                reason = "shortExit can follow only flip(PE) or shortEntry(PE)";
                break;
        }

        if (isInvalid) {
            log.warn("INVALID SEQUENCE REJECTED | action={} | signalType={} | currentPrice={} | strategyName={} | " +
                    "previousAction={} | previousSignalType={} | reason={}",
                    action.getValue(), legType, currentPrice, strategyName, prev.action, prev.signalType, reason);
            return false;
        }
        return true;
    }

    private boolean isDuplicate(String strategyName, String action, String signalType, String time) {
        final String cacheKey = strategyName + ":" + action + ":" + signalType + ":" + time;
        final Instant now = Instant.now();
        final Instant firstSeen = signalCache.get(cacheKey);

        if (firstSeen != null) {
            if (Duration.between(firstSeen, now).compareTo(CACHE_TTL) <= 0) {
                int count = duplicateCount.merge(cacheKey, 1, Integer::sum);
                Instant lastLogged = lastLogDuplicate.get(cacheKey);
                if (lastLogged == null || Duration.between(lastLogged, now).compareTo(LOG_THROTTLE) > 0) {
                    log.warn("DUPLICATE ignored | key={} | firstSeenAt={} | duplicateCount={}",cacheKey, firstSeen.atZone(ZoneId.of("Asia/Kolkata")), count);
                    lastLogDuplicate.put(cacheKey, now);
                }                
                return true;
            }
            signalCache.put(cacheKey, now);
            duplicateCount.remove(cacheKey);
            lastLogDuplicate.remove(cacheKey);
            log.info("TTL EXPIRED -> accepting and refreshing key={} | previousFirstSeen={}", cacheKey, firstSeen.atZone(ZoneId.of("Asia/Kolkata")));
            return false;
        }
        signalCache.put(cacheKey, now);
        return false;
    }

    private void updatePrevious(String strategyName, String action, String signalType, String time, String currentPrice) {
        previousByStrategy.put(strategyName, new LastProcessed(action, signalType, time, currentPrice, Instant.now()));
    }

    private void logAcceptedWithPrev(String strategyName, String action, String signalType, String time, String currentPrice, LastProcessed prev) {
        if (prev == null)
            log.info("ACCEPTED | action={} | strategyName={} | signalType={} | time={} | currentPrice={} | prev=NONE",
                    action, strategyName, signalType, time, currentPrice);
        else
            log.info("ACCEPTED | action={} | strategyName={} | signalType={} | time={} | currentPrice={} | prev[action={}|type={}|time={}|price={}|seenAt={}]",
                    action, strategyName, signalType, time, currentPrice, prev.action, prev.signalType, prev.time, prev.currentPrice, IST_FORMATTER.format(prev.receivedAt));
    }

    @PostMapping("/clearCache")
    public ResponseEntity<String> clearCache() {
        signalCache.clear();
        duplicateCount.clear();
        previousByStrategy.clear();
        return ResponseEntity.ok("Cache cleared");
    }

    @GetMapping("/cacheStatus")
    public ResponseEntity<Map<String, Object>> getCacheStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("cacheSize", signalCache.size());
        status.put("cachedSignals", signalCache.keySet());
        status.put("duplicateKeys", duplicateCount);
        status.put("ttlDays", CACHE_TTL.toDays());
        status.put("previousTracked", previousByStrategy.size());
        status.put("previousByStrategy", previousByStrategy);        
        return ResponseEntity.ok(status);
    }

    public enum Action {
        FLIP("flip"),LONG_ENTRY("longEntry"),LONG_EXIT("longExit"),SHORT_ENTRY("shortEntry"),SHORT_EXIT("shortExit");
    	
        private final String value;
        Action(String value) { this.value = value; }
        public String getValue() { return value; }
        
        public static Action fromString(String value) {
            for (Action a : values()) {
                if (a.value.equals(value)) return a;
            }
            return null;
        }
    }

    public enum LegType {
        CE, PE;
        
        public static LegType fromString(String value) {
            try {
                return valueOf(value);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }
        
    public static final class Signal {
        public final String strategyName;
        public final String action;
        public final String signalType;
        public final String time;
        public final String currentPrice;
        
        public Signal(String strategyName, String action, String signalType, String time, String currentPrice) {
            this.strategyName = strategyName;
            this.action = action;
            this.signalType = signalType;
            this.time = time;
            this.currentPrice = currentPrice;
        }
    }

    public static final class LastProcessed {
        public final String action;
        public final String signalType;
        public final String time;
        public final String currentPrice;
        public final Instant receivedAt;
        
        public LastProcessed(String action, String signalType, String time, String currentPrice, Instant receivedAt) {
            this.action = action;
            this.signalType = signalType;
            this.time = time;
            this.currentPrice = currentPrice;
            this.receivedAt = receivedAt;
        }
    }
    
    @PostConstruct
    public void loadCacheFromDatabase() {
        log.info("Starting cache initialization from database...");        
        try {            
            Trade lastTrade = signalService.getLastTrade();           
            if (lastTrade != null) {
                previousByStrategy.put(lastTrade.getStatergyName(), new LastProcessed(lastTrade.getLastApiAction(),lastTrade.getLastApiSignalType(),lastTrade.getApiTime(),String.valueOf(lastTrade.getEntrySignalPrice()),Instant.now()));
                log.info("Loaded last trade for strategy: {}", lastTrade.getStatergyName());
            }            
            log.info("Cache initialization complete: {} signals cached, {} strategies tracked",signalCache.size(), previousByStrategy.size());                    
        } catch (Exception e) {
            log.error("Failed to load cache from database. Starting with empty cache.", e);
        }
    }
}

