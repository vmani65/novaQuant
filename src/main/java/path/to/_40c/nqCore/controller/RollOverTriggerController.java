package path.to._40c.nqCore.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static path.to._40c.nqCore.util.Constants.ZONE_ID;

import path.to._40c.nqCore.entity.WeeklySymbolConfig;
import path.to._40c.nqCore.service.MonthlyRollService;
import path.to._40c.nqCore.service.ProfitRecenterService;
import path.to._40c.nqCore.service.SignalService;
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
    private final MonthlyRollService     monthlyRollService;

    public RollOverTriggerController(SignalService signalService, WeeklySymbolService weeklySymbolService,
                                     ProfitRecenterService profitRecenterService, MonthlyRollService monthlyRollService) {
        this.signalService         = signalService;
        this.weeklySymbolService         = weeklySymbolService;
        this.profitRecenterService = profitRecenterService;
        this.monthlyRollService    = monthlyRollService;
    }

    /**
     * Called by rollOverTrigger.afl at exactly 14:47 IST each trading day.
     * Compares today's IST date against the configured rollover day.
     * If they match, triggers rollover and marks it complete. Otherwise logs and returns.
     *
     * Example: GET /api/rollover-trigger?currentPrice=22450.50
     */
    @GetMapping("/rollover-trigger")
    public void handleRollOverTrigger(@RequestParam String currentPrice) {
        String sanitisedPrice = currentPrice.replace(",", "").trim();
        log.info("RollOver trigger received from AFL | currentPrice={}", sanitisedPrice);

        WeeklySymbolConfig cfg = weeklySymbolService.current();

        if (cfg == null || cfg.getRolloverDay() == null) {
            log.info("RollOver skipped — no rollover day configured");
            return;
        }

        LocalDate today = LocalDate.now(ZoneId.of(ZONE_ID));

        if (!today.equals(cfg.getRolloverDay())) {
            log.info("RollOver skipped — today={} does not match rollover day={}", today, cfg.getRolloverDay());
            return;
        }

        if (Boolean.TRUE.equals(cfg.getRolloverComplete())) {
            log.info("RollOver skipped — already completed for date={}", cfg.getRolloverDay());
            return;
        }

        log.info("RollOver day matched — initiating rollover | date={} | currentPrice={}", today, sanitisedPrice);
        signalService.handleRollOver(sanitisedPrice);
        weeklySymbolService.promoteRolloverSymbol();
        weeklySymbolService.markRolloverComplete();
        log.info("RollOver completed — symbol promoted and marked complete");
    }

    /**
     * Called by nQTicker ProfitRecenterConsumer when NIFTY profit >= 500 points.
     * Closes the current live legs and re-opens at the new ATM. Position stays LIVE.
     * SYNTH_WEEKLY only — LONG_MONTHLY never recenters (its convexity is the point),
     * so this trigger cannot touch a monthly position.
     *
     * Example: GET /api/realize-profits?currentPrice=24500.0
     */
    @GetMapping("/realize-profits")
    public void handleRealizeProfits(@RequestParam String currentPrice) {
        String sanitisedPrice = currentPrice.replace(",", "").trim();
        log.info("Realize-profits trigger received from nQTicker | currentPrice={}", sanitisedPrice);
        profitRecenterService.realizeProfitsWeekly(sanitisedPrice);
        log.info("Realize-profits completed");
    }

    /**
     * Called by nQTicker OpenBufferConsumer when the 9:15 AM open buffer fires.
     * Closes the trade directly — no 9:15 AM check, no re-delegation to nQTicker.
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
        log.info("execute-close received from nQTicker open buffer | currentPrice={}", sanitisedPrice);
        signalService.executeCloseImmediate(sanitisedPrice);
        log.info("execute-close completed");
    }

    @GetMapping("/refreshSymbolCache")
    public void refreshSymbolCache() {
        weeklySymbolService.warmCache();
        log.info("Symbol cache refreshed from DB");
    }

    /**
     * Manual trigger for the LONG_MONTHLY calendar-roll check (DTE >= 10 rule). The same
     * check runs daily at 08:40 IST and before every monthly open; this endpoint exists
     * for ops verification after auth/config changes.
     */
    @GetMapping("/monthly-roll-check")
    public void handleMonthlyRollCheck() {
        log.info("Manual monthly roll check requested");
        monthlyRollService.checkAndPromoteMonthly();
    }
}
