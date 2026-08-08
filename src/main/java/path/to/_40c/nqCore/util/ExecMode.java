package path.to._40c.nqCore.util;

/**
 * Execution style for one leg order (PATIENT_EXECUTION_PLAN.md §3.1).
 *
 * AGGRESSIVE is today's pipeline: graduated LIMIT walk (≤2.5s) with MARKET fallback —
 * guaranteed-fill-fast, right for liquid weekly ATM books. PATIENT rests a LIMIT at a
 * fair-anchored price and concedes slowly over minutes with a hard concession cap and no
 * MARKET fallback on wide spreads — right for thin monthly books where the cost of
 * crossing dwarfs the cost of waiting. PATIENT is only honored when
 * PositionUtil.patientModeAvailable() holds (enabled + valid config + before the IST
 * cutoff); otherwise the request silently degrades to AGGRESSIVE.
 */
public enum ExecMode {
    AGGRESSIVE,
    PATIENT
}
