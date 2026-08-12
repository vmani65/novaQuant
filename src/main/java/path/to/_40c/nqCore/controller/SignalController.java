package path.to._40c.nqCore.controller;

import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.service.SignalService;
import path.to._40c.nqCore.util.ComputeUtil;

import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.Map;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;

import static path.to._40c.nqCore.util.Constants.IST_FORMATTER;
import static path.to._40c.nqCore.util.Constants.OUTPUT_FORMAT;
import static path.to._40c.nqCore.util.Constants.ZONE_ID;

@RestController
@RequestMapping("/api")
@Slf4j
public class SignalController {
    private final Map<String, Instant> signalCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> duplicateCount = new ConcurrentHashMap<>();
    private static final Duration CACHE_TTL = Duration.ofDays(60);
    private final java.util.concurrent.atomic.AtomicInteger duplicatesSinceLastLog = new java.util.concurrent.atomic.AtomicInteger();
    private final Map<String, LastProcessed> previousByStrategy = new ConcurrentHashMap<>();

    /**
     * Staleness gate (2026-08-12 incident): a restart wipes the in-memory dedup cache, and
     * AmiBroker re-transmits its LAST signal continuously — so a fresh app can accept a
     * signal from a previous session (a 17-hour-old flip was accepted at 08:47 and only
     * missing Kite auth kept its orders off the broker). Signals older than this many
     * minutes are rejected before sequence validation, leaving prev untouched so the
     * sequence gate keeps protecting the actual position. Legitimate signals arrive within
     * ~3 minutes of their bar time (bar-START bucketing adds at most 15 more). Zero or
     * negative disables the gate (mock click-throughs with hand-typed times may need that).
     */
    @Value("${signal.staleness.max-age-minutes:45}")
    private long maxSignalAgeMinutes;

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
        return signalService.handleWeeklyRollOver(signalPrice.replace(",", ""));
    }

    private void handleSignal(Action action, String signalType, String currentPrice, String strategyName, String time) {
        if (isDuplicate(strategyName, action.getValue(), signalType, time)) return;
        if (isStale(action.getValue(), signalType, currentPrice, strategyName, time)) return;
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

    /**
     * True when the signal's bar time is older than the configured threshold — a replayed or
     * previously-undelivered signal that must not execute against today's market. Runs AFTER
     * isDuplicate (which caches every key on first sight), so a stale key warns exactly once
     * and its re-transmissions are absorbed as ordinary duplicates. FAIL-OPEN: a time that
     * does not parse as OUTPUT_FORMAT (toStd stores unknown formats as-is) is treated as
     * fresh, so no exotic-but-legitimate signal is ever rejected by this gate. Future-dated
     * times (clock skew) are fresh by definition.
     */
    private boolean isStale(String action, String signalType, String currentPrice, String strategyName, String time) {
        if (maxSignalAgeMinutes <= 0) return false;
        try {
            LocalDateTime barTime = LocalDateTime.parse(time, OUTPUT_FORMAT);
            long ageMinutes = Duration.between(barTime, LocalDateTime.now(ZoneId.of(ZONE_ID))).toMinutes();
            if (ageMinutes > maxSignalAgeMinutes) {
                log.warn("STALE SIGNAL REJECTED | action={} | signalType={} | time={} | age={}min (max {}min) | "
                        + "strategyName={} | currentPrice={} — replayed/undelivered signal, not executing; prev unchanged",
                        action, signalType, time, ageMinutes, maxSignalAgeMinutes, strategyName, currentPrice);
                return true;
            }
        } catch (Exception e) {
            log.info("Staleness gate: unparseable signal time '{}' — failing open (accepted)", time);
        }
        return false;
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
                duplicateCount.merge(cacheKey, 1, Integer::sum);
                duplicatesSinceLastLog.incrementAndGet();
                return true;
            }
            signalCache.put(cacheKey, now);
            duplicateCount.remove(cacheKey);
            log.info("TTL EXPIRED -> accepting and refreshing key={} | previousFirstSeen={}", cacheKey, firstSeen.atZone(ZoneId.of(ZONE_ID)));
            return false;
        }
        signalCache.put(cacheKey, now);
        return false;
    }

    private void updatePrevious(String strategyName, String action, String signalType, String time, String currentPrice) {
        previousByStrategy.put(strategyName, new LastProcessed(action, signalType, time, currentPrice, Instant.now()));
    }

    /** One-per-day summary of filtered duplicate signals at 15:45 IST (after market close). Skips when nothing was filtered. */
    @Scheduled(cron = "0 45 15 * * *", zone = ZONE_ID)
    public void logDailyDuplicateSummary() {
        int filtered = duplicatesSinceLastLog.getAndSet(0);
        if (filtered > 0) {
            log.warn("DUPLICATES filtered today: {} (across {} unique keys)", filtered, duplicateCount.size());
        }
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
            Position lastTrade = signalService.getLastTrade();           
            if (lastTrade != null) {
                previousByStrategy.put(lastTrade.getStrategyName(), new LastProcessed(lastTrade.getLastSignalAction(),lastTrade.getLastSignalLeg(),lastTrade.getSignalAt(),String.valueOf(lastTrade.getEntrySpot()),Instant.now()));
                log.info("Loaded last trade for strategy: {}", lastTrade.getStrategyName());
            }            
            log.info("Cache initialization complete: {} signals cached, {} strategies tracked",signalCache.size(), previousByStrategy.size());                    
        } catch (Exception e) {
            log.error("Failed to load cache from database. Starting with empty cache.", e);
        }
    }
}

