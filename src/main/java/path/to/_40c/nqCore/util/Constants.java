package path.to._40c.nqCore.util;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

public class Constants {
	public static final String LIVE = "LIVE";
	public static final String CLOSED = "CLOSED";
	public static final String FAILED = "FAILED";
	public static final String PARTIAL = "PARTIAL";
	public static final String PENDING_CLOSE = "PENDING_CLOSE";
	public static final String PENDING_OPEN = "PENDING_OPEN";
	
	public static final String BUY = "BUY";
	public static final String SELL = "SELL";
	
	public static final String CE = "CE";
	public static final String PE = "PE";
	
	public static final String ZONE_ID = "Asia/Kolkata";
	public static final String DATE_FORMAT = "dd-MM-yyyy HH:mm:ss.SSS";
	public static final DateTimeFormatter IST_FORMATTER = DateTimeFormatter.ofPattern(DATE_FORMAT).withZone(ZoneId.of("Asia/Kolkata"));
	
	public static final String LONG = "LONG";
	public static final String SHORT = "SHORT";

	public static final String WEEKLY = "WEEKLY";
	public static final String MONTHLY = "MONTHLY";

	public static final String SYNTH_WEEKLY = "SYNTH_WEEKLY";
	public static final String LONG_MONTHLY = "LONG_MONTHLY";
	
	public static final String NFO = "NFO";
	public static final String NFO_COLON = NFO+":";
	public static final String NIFTY = "NIFTY";
	
	public static final String WIN = "WIN";
	public static final String LOSS = "LOSS";
	
	public static final String ATM = "ATM";
	public static final String ITM = "ITM";
	public static final String OTM = "OTM";
	public static final String ITMMINUS50 = "ITM-50";
	public static final String OTMPLUS50 = "OTM+50";	
	
	public static final int LOT_SIZE = 65;
	public static final int MAX_SIZE_PER_ORDER = 1755;
	public static final double NIFTY_OPT_TICK = 0.05;
	/** Monthly liquidity guard (plan §3.3.2): alert when a LONG_MONTHLY fill pays more than this many points per side vs mid. */
	public static final double MONTHLY_SPREAD_ALERT_PTS = 2.0;

	public static final String NA = "N/A";
	
	public static final DateTimeFormatter OUTPUT_FORMAT = DateTimeFormatter.ofPattern("dd-MMM-yyyy hh.mm.ss a");
	public static final List<DateTimeFormatter> INPUT_FORMATS = Arrays.asList(
		    DateTimeFormatter.ofPattern("MM/dd/yyyy hh.mm.ss a"),
		    DateTimeFormatter.ofPattern("MM/dd/yyyy hh:mm:ss a"),
		    DateTimeFormatter.ofPattern("dd-MMM-yyyy hh.mm.ss a"),
		    DateTimeFormatter.ofPattern("dd-MMM-yy hh:mm:ss a"),
		    DateTimeFormatter.ofPattern("M/d/yyyy h.mm.ss a"),
		    DateTimeFormatter.ofPattern("M/d/yyyy h:mm:ss a"),
		    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
		    DateTimeFormatter.ofPattern("dd/MM/yyyy hh.mm.ss a"),
		    DateTimeFormatter.ofPattern("HH:mm:ss")
	);
	
}
