package path.to._40c.nqCore.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static path.to._40c.nqCore.util.Constants.ZONE_ID;

import path.to._40c.nqCore.entity.WeeklySymbolConfig;
import path.to._40c.nqCore.service.ProfitRecenterService;
import path.to._40c.nqCore.service.SignalService;
import path.to._40c.nqCore.service.TradeExecutionQueue;
import path.to._40c.nqCore.service.WeeklySymbolService;

import java.time.LocalDate;
import java.time.ZoneId;

@RestController
@RequestMapping("/api")
@Slf4j
public class RollOverTriggerController {
    private final SignalService          signalService;
    private final WeeklySymbolService          weeklySymbolService;
    private final ProfitRecenterService  profitRecenterService;
    private final TradeExecutionQueue    executionQueue;

    public RollOverTriggerController(SignalService signalService, WeeklySymbolService weeklySymbolService,
                                     ProfitRecenterService profitRecenterService, TradeExecutionQueue executionQueue) {
        this.signalService         = signalService;
        this.weeklySymbolService         = weeklySymbolService;
        this.profitRecenterService = profitRecenterService;
        this.executionQueue        = executionQueue;
    }

    /**
     * Called by rollOverTrigger.afl at exactly 14:47 IST each trading day. Enqueues on the
     * trade-exec queue and returns immediately; the day/completion guards run inside the
     * queued task so the decision reflects any state changed by tasks ahead of it (e.g. a
     * close that already promoted the symbol).
     *
     * Example: GET /api/rollover-trigger?currentPrice=22450.50
     */
    @GetMapping("/rollover-trigger")
    public void handleRollOverTrigger(@RequestParam String currentPrice) {
        String sanitisedPrice = currentPrice.replace(",", "").trim();
        log.info("RollOver trigger received from AFL | currentPrice={} — queueing", sanitisedPrice);
        executionQueue.submit("system:rollover-trigger", () -> runRollOverTrigger(sanitisedPrice));
    }

    /**
     * Queue-thread body of the rollover trigger: compares today's IST date against the
     * configured rollover day; if they match and rollover hasn't run yet, rolls EVERY live
     * position. The symbol is promoted and rollover marked complete only when every position
     * rolled — on partial failure rolloverComplete stays unset so the trigger (or the manual
     * button) can be re-fired to retry the failed book instead of stranding it on the
     * expiring contract.
     */
    private boolean runRollOverTrigger(String sanitisedPrice) {
        WeeklySymbolConfig cfg = weeklySymbolService.current();

        if (cfg == null || cfg.getRolloverDay() == null) {
            log.info("RollOver skipped — no rollover day configured");
            return false;
        }

        LocalDate today = LocalDate.now(ZoneId.of(ZONE_ID));

        if (!today.equals(cfg.getRolloverDay())) {
            log.info("RollOver skipped — today={} does not match rollover day={}", today, cfg.getRolloverDay());
            return false;
        }

        if (Boolean.TRUE.equals(cfg.getRolloverComplete())) {
            log.info("RollOver skipped — already completed for date={}", cfg.getRolloverDay());
            return false;
        }

        log.info("RollOver day matched — initiating rollover of all live positions | date={} | currentPrice={}", today, sanitisedPrice);
        boolean allRolled = signalService.handleRollOver(sanitisedPrice);
        if (!allRolled) {
            log.error("RollOver INCOMPLETE — at least one position failed to roll; symbol NOT promoted, rolloverComplete NOT set. "
                    + "Re-fire /api/rollover-trigger (or the manual rollover button) after resolving the failure.");
            return false;
        }
        weeklySymbolService.promoteRolloverSymbol();
        weeklySymbolService.markRolloverComplete();
        log.info("RollOver completed for all positions — symbol promoted and marked complete");
        return true;
    }

    /**
     * Called by nQTicker ProfitRecenterConsumer when NIFTY profit >= 500 points.
     * Enqueues the recenter (close current legs + re-open at new ATM) on the trade-exec
     * queue and returns immediately. Position stays LIVE.
     *
     * Example: GET /api/realize-profits?currentPrice=24500.0
     */
    @GetMapping("/realize-profits")
    public void handleRealizeProfits(@RequestParam String currentPrice) {
        String sanitisedPrice = currentPrice.replace(",", "").trim();
        log.info("Realize-profits trigger received from nQTicker | currentPrice={} — queueing", sanitisedPrice);
        executionQueue.submit("system:realize-profits", () -> {
            profitRecenterService.realizeProfits(sanitisedPrice);
            log.info("Realize-profits completed");
            return true;
        });
    }

    /**
     * Called by nQTicker OpenBufferConsumer when the 9:15 AM open buffer fires.
     * Enqueues the close on the trade-exec queue — no 9:15 AM check, no re-delegation
     * to nQTicker.
     *
     * This is the second leg of the open buffer flow:
     *   AFL longExit → nqCore detects 9:15 → arms nQTicker buffer
     *   → nQTicker fires this endpoint when target hit or 09:28:59 deadline reached
     *
     * Example: GET /api/execute-close?currentPrice=22463.5
     */
    @GetMapping("/execute-close")
    public void handleExecuteClose(@RequestParam String currentPrice) {
        String sanitisedPrice = currentPrice.replace(",", "").trim();
        log.info("execute-close received from nQTicker open buffer | currentPrice={} — queueing", sanitisedPrice);
        executionQueue.submit("system:execute-close", () -> {
            signalService.executeCloseImmediate(sanitisedPrice);
            log.info("execute-close completed");
            return true;
        });
    }

    @GetMapping("/refreshSymbolCache")
    public void refreshSymbolCache() {
        weeklySymbolService.warmCache();
        log.info("Symbol cache refreshed from DB");
    }
}
