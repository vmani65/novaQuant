package path.to._40c.nqCore.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import path.to._40c.nqCore.service.MonthlySymbolService;
import path.to._40c.nqCore.service.ProfitRecenterService;
import path.to._40c.nqCore.service.SignalService;
import path.to._40c.nqCore.service.WeeklySymbolService;

@RestController
@RequestMapping("/api")
@Slf4j
public class RollOverTriggerController {
    private final SignalService          signalService;
    private final WeeklySymbolService          weeklySymbolService;
    private final ProfitRecenterService  profitRecenterService;
    private final MonthlySymbolService     monthlySymbolService;

    public RollOverTriggerController(SignalService signalService, WeeklySymbolService weeklySymbolService,
                                     ProfitRecenterService profitRecenterService, MonthlySymbolService monthlySymbolService) {
        this.signalService         = signalService;
        this.weeklySymbolService         = weeklySymbolService;
        this.profitRecenterService = profitRecenterService;
        this.monthlySymbolService    = monthlySymbolService;
    }

    /**
     * WEEKLY roll trigger, fired by nQTicker at 14:47 IST (formerly rollOverTrigger.afl
     * on /api/rollover-trigger — that path stays mapped as a deprecated alias so an
     * un-migrated caller can never silently miss a roll). Syncs the weekly symbol row
     * first (promotion happens only on the configured rollover day, once), then rolls
     * the SYNTH_WEEKLY book's position onto the current contract. The monthly book is
     * structurally invisible to this path. Safe to fire on any day — a position already
     * on the current contract is a logged no-op.
     *
     * Example: GET /api/rollover-weekly?currentPrice=22450.50
     */
    @GetMapping({"/rollover-weekly", "/rollover-trigger"})
    public void handleWeeklyRollOverTrigger(@RequestParam String currentPrice,
            jakarta.servlet.http.HttpServletRequest request) {
        if (request.getRequestURI().endsWith("/rollover-trigger")) {
            log.warn("DEPRECATED path /api/rollover-trigger used — update the caller to /api/rollover-weekly");
        }
        String sanitisedPrice = currentPrice.replace(",", "").trim();
        log.info("Weekly rollover trigger received | currentPrice={}", sanitisedPrice);
        signalService.handleWeeklyRollOver(sanitisedPrice);
        log.info("Weekly rollover trigger handled");
    }

        /**
     * MONTHLY roll trigger, to be fired by nQTicker (its cadence logic is not final yet;
     * roughly once a month near the DTE threshold). Syncs the monthly symbol row to the
     * DTE-correct contract, then rolls the LONG_MONTHLY book's position: sell whatever
     * is in hand, buy the same structure at the current ATM on the latest contract. The
     * weekly book is structurally invisible to this path. Safe to fire when nothing
     * needs rolling — no position or an already-current position is a logged no-op.
     *
     * Example: GET /api/rollover-monthly?currentPrice=24500.0
     */
    @GetMapping("/rollover-monthly")
    public void handleMonthlyRollOverTrigger(@RequestParam String currentPrice) {
        String sanitisedPrice = currentPrice.replace(",", "").trim();
        log.info("Monthly rollover trigger received | currentPrice={}", sanitisedPrice);
        signalService.handleMonthlyRollOver(sanitisedPrice);
        log.info("Monthly rollover trigger handled");
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
     * Closes BOTH books directly at nQTicker's live price — no 9:15 AM check, no
     * re-delegation to nQTicker.
     *
     * This is the second leg of the open buffer flow:
     *   AFL longExit → nqCore detects 9:15 → arms nQTicker buffer (neither book closes yet)
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
        monthlySymbolService.warmCache();
        log.info("Symbol caches (weekly + monthly) refreshed from DB");
    }

    /**
     * Manual trigger for the LONG_MONTHLY contract sync only (DTE >= 10 rule; no position
     * orders). The same check runs daily at 08:40 IST and before every monthly open;
     * this endpoint exists for ops verification after auth/config changes.
     */
    @GetMapping("/monthly-roll-check")
    public void handleMonthlyRollCheck() {
        log.info("Manual monthly roll check requested");
        monthlySymbolService.syncTradedContract();
    }
}
