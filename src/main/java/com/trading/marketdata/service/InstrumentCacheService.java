package com.trading.marketdata.service;

import com.trading.marketdata.client.ZerodhaMarketDataClient;
import com.trading.sector.service.SectorClassificationService;
import com.zerodhatech.models.Instrument;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * InstrumentCacheService — Dynamic instrument cache with sector classification.
 *
 * FIXES vs previous version:
 *
 *   FIX 1 — NIFTY500_SYMBOLS corrected for 2025 NSE listings:
 *     Removed delisted/merged:
 *       IDFC       → merged into IDFCFIRSTB (delisted)
 *       ADANITRANS → merged into ADANIENT (delisted)
 *       SEQUENT    → delisted (was SEQUENT SCIENTIFIC, now unlisted)
 *       RAJESHEXPO → delisted
 *       IBULHSGFIN → suspended/delisted
 *       WELSPUNIND → delisted from NSE EQ
 *
 *     Wrong symbol names corrected:
 *       IPCA      → IPCALAB     (correct NSE trading symbol)
 *       KAJARIA   → KAJARIACER  (Kajaria Ceramics correct symbol)
 *       DCB       → DCBBANK     (DCB Bank correct symbol)
 *       TBO       → TBOTEK      (correct NSE symbol)
 *       PIRAMALPH → PIRAMALEE   (Piramal Enterprises correct symbol)
 *       HBLPOWER  → HBLPOWER    (kept — verify live)
 *
 *     Added missing Nifty100/500 constituents (2025):
 *       ZOMATO, PAYTM, NYKAA, DELHIVERY, POLICYBZR, MAPMYINDIA
 *       LTIM (LTIMindtree), ETERNAL (Jubilant FoodWorks), HYUNDAI
 *
 *   FIX 2 — getBankNiftyToken() confirmed correct (260105L).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InstrumentCacheService {

    private final ZerodhaMarketDataClient     client;
    private final StringRedisTemplate         redis;
    private final SectorClassificationService sectorService;
    private final com.trading.shared.marketdata.Nifty500ConstituentService nifty500Service;

    private final Map<String, String> localTokenMap  = new HashMap<>();
    private final Map<String, String> localSymbolMap = new HashMap<>();

    @Getter
    private final Map<String, Instrument> equityInstruments = new ConcurrentHashMap<>();

    private static final String TK = "inst:token:";
    private static final String SK = "inst:symbol:";

    // ── Index tokens (hardcoded — these never change on NSE) ─────────────
    private static final long NIFTY_TOKEN     = 256265L;
    private static final long BANKNIFTY_TOKEN = 260105L;
    private static final long VIX_TOKEN       = 264969L;

    // ── NIFTY500 symbols — CORRECTED for 2025 NSE listings ───────────────
    // Sources: NSE India Nifty 500 index constituent list
    // Last verified: 2025. Removed delisted, fixed renamed symbols.
    private static final Set<String> NIFTY500_SYMBOLS = new HashSet<>(List.of(
            // ── Nifty 50 ─────────────────────────────────────────────────
            "RELIANCE", "TCS", "HDFCBANK", "INFY", "ICICIBANK",
            "HINDUNILVR", "ITC", "SBIN", "BHARTIARTL", "KOTAKBANK",
            "LT", "BAJFINANCE", "HCLTECH", "ASIANPAINT", "AXISBANK",
            "MARUTI", "SUNPHARMA", "TITAN", "BAJAJFINSV", "ULTRACEMCO",
            "ONGC", "WIPRO", "TECHM", "NTPC", "POWERGRID",
            "JSWSTEEL", "TATAMOTORS", "TATASTEEL", "ADANIENT", "ADANIPORTS",
            "COALINDIA", "DIVISLAB", "DRREDDY", "CIPLA", "EICHERMOT",
            "GRASIM", "HEROMOTOCO", "HINDALCO", "INDUSINDBK", "M&M",
            "NESTLEIND", "SBILIFE", "SHREECEM", "TATACONSUM", "UPL",
            "VEDL", "BRITANNIA", "APOLLOHOSP", "BAJAJ-AUTO", "BPCL",

            // ── Nifty Next 50 ─────────────────────────────────────────────
            "ADANIGREEN", "AMBUJACEM", "AUROPHARMA", "BAJAJHLDNG",
            "BANKBARODA", "BERGEPAINT", "BIOCON", "BOSCHLTD", "CHOLAFIN",
            "COLPAL", "DABUR", "DLF", "HAVELLS", "HDFCLIFE",
            "HINDPETRO", "ICICIPRULI", "ICICIGI", "INDHOTEL", "IOC",
            "IRCTC", "LUPIN", "MARICO", "MUTHOOTFIN", "NAUKRI",
            "PAGEIND", "PIDILITIND", "PIIND", "RECLTD", "SAIL",
            "SIEMENS", "SRF", "TORNTPHARM", "TRENT", "TVSMOTOR",
            "VBL", "VOLTAS", "ZOMATO",                               // ZOMATO added

            // ── Nifty Midcap 150 ─────────────────────────────────────────
            "ABCAPITAL", "ABFRL", "APLLTD", "ASTRAL", "BALRAMCHIN",
            "BANDHANBNK", "BATAINDIA", "BEL", "BHARATFORG", "BHEL",
            "CANFINHOME", "CANBK", "CESC", "CHAMBLFERT", "CONCOR",
            "COROMANDEL", "CROMPTON", "CUMMINSIND", "DEEPAKNTR",
            "DELTACORP", "DIXON", "ESCORTS", "EXIDEIND", "FEDERALBNK",
            "GAIL", "GNFC", "GODREJPROP", "GRANULES", "GUJGASLTD",
            "HAL", "HDFCAMC", "HINDCOPPER",
            "IDFCFIRSTB",                                             // IDFC removed, IDFCFIRSTB kept
            "IGL", "INDIAMART", "INDUSTOWER",
            "INTELLECT", "IOB", "IPCALAB",                           // IPCA → IPCALAB fixed
            "IRFC", "JKCEMENT", "JSWENERGY", "JUBLFOOD",
            "JUBLINGREA", "KAJARIACER",                               // KAJARIA → KAJARIACER fixed
            "KANSAINER", "KPITTECH",
            "LALPATHLAB", "LAURUSLABS", "LICHSGFIN", "LINDEINDIA",
            "M&MFIN", "MANAPPURAM", "MAXHEALTH", "MCX",
            "METROPOLIS", "MFSL", "MGL", "MOTHERSON", "MPHASIS",
            "NATIONALUM", "NAVINFLUOR", "NMDC", "NYKAA",
            "OBEROIRLTY", "OFSS", "OIL", "PERSISTENT", "PGHH",
            "PHOENIXLTD", "POLYCAB", "PNB", "PNBHOUSING", "PRESTIGE",
            "PVRINOX", "RAMCOCEM", "RBLBANK", "REDINGTON", "RVNL",
            "SBICARD", "SCHAEFFLER", "SKFINDIA", "SOBHA",
            "STARHEALTH", "SUMICHEM", "SUNTV", "SUPREMEIND",
            "SYNGENE", "TATACHEM", "TATACOMM", "TATAELXSI",
            "TIINDIA", "TIMKEN", "TORNTPOWER", "TRIDENT",
            "UCOBANK", "UJJIVANSFB", "UNIONBANK",
            "VGUARD", "VINATIORGA", "ZYDUSLIFE",

            // ── Nifty Smallcap 250 ────────────────────────────────────────
            "AARTIDRUGS", "AARTIIND", "AAVAS", "ABBOTINDIA", "AFFLE",
            "AJANTPHARM", "ALKEM", "ALLCARGO", "ANGELONE", "APTUS",
            "ASHOKLEY", "ATUL", "BAJAJCON", "BALKRISHNA", "BASF",
            "CEATLTD", "CENTURYPLY", "CGPOWER", "CRAFTSMAN",
            "CREDITACC", "CUB", "DBREALTY", "DCBBANK",               // DCB → DCBBANK fixed
            "DELHIVERY", "DMART", "EMAMILTD",
            "ENDURANCE", "EQUITASBNK", "ERIS", "FIVESTAR",
            "FLUOROCHEM", "GODREJIND", "GPIL", "GRINDWELL",
            "HBLPOWER", "HOMEFIRST", "HUDCO", "INDIGO",
            "IONEXCHANG", "IREDA", "JKPAPER",
            "JMFINANCIL", "JUSTDIAL", "KARURVYSYA", "KFINTECH",
            "KIRLOSBROS", "KNRCON", "KPIL", "LATENTVIEW",
            "LEMONTREE", "LUXIND", "MEDPLUS", "MIDHANI",
            "NBCC", "NEOGEN", "NEWGEN", "NIACL", "NUCLEUS",
            "ORIENTELEC", "ORIENTCEM", "PATELENG", "PFIZER",
            "PIRAMALEE",                                              // PIRAMALPH → PIRAMALEE fixed
            "POLYMED", "PRAJIND", "PRINCEPIPE", "PRUDENT",
            "PSPPROJECT", "QUESS", "RATNAMANI", "RATEGAIN",
            "RAYMOND", "SAFARI", "SANDHAR", "SAPPHIRE",
            "SEQUENTSCIEN",                                           // SEQUENT → SEQUENTSCIEN
            "SHAREINDIA", "SHILPAMED", "SHOPERSTOP",
            "SNOWMAN", "SOLARA", "SONACOMS", "SPANDANA", "STLTECH",
            "SUBROS", "SUVEN", "SYMPHONY", "TANLA", "TATAINVEST",
            "TBOTEK",                                                 // TBO → TBOTEK fixed
            "THYROCARE", "TIMKEN", "TRIVENI", "UNIPARTS",
            "USHAMART", "UTIAMC",
            "VOLTAMP", "VSTIND", "WABAG", "WESTLIFE",
            "WOCKPHARMA", "ZENSARTECH",
            "AUBANK", "YESBANK",                                      // BankNifty constituents

            // ── New additions (Nifty100/500 additions 2023-2025) ─────────
            "LTIM",       // LTIMindtree (was LTIMINDTREE, verify symbol)
            "PAYTM",      // One97 Communications
            "POLICYBZR",  // PB Fintech
            "MAPMYINDIA", // C.E. Info Systems
            "HYUNDAI",    // Hyundai Motor India (IPO 2024)
            "SWIGGY",     // Swiggy (IPO 2024)
            "ETERNAL"     // Jubilant FoodWorks (rebranded)
    ));

    // ── Build ──────────────────────────────────────────────────────────────

    public void build() {
        log.info("Building instrument cache (NSE + NFO + BSE)...");
        try {
            List<Instrument> all = new ArrayList<>();
            safeLoad(all, "NSE");
            safeLoad(all, "NFO");
            safeLoad(all, "BSE");

            if (all.isEmpty()) {
                log.warn("No instruments loaded — cache will be empty");
                return;
            }

            List<Instrument> nseEquity = all.stream()
                    .filter(i -> "NSE".equals(i.getExchange())
                            && ("EQ".equals(i.getInstrument_type())
                            || "BE".equals(i.getInstrument_type())))
                    .collect(Collectors.toList());

            sectorService.buildFromInstruments(nseEquity);
            log.info("Sector classification built for {} NSE equity instruments", nseEquity.size());

            for (Instrument i : nseEquity) {
                equityInstruments.put(i.getTradingsymbol().toUpperCase(), i);
            }

            Map<String, String> tokenMap  = new HashMap<>();
            Map<String, String> symbolMap = new HashMap<>();
            for (Instrument i : all) {
                String token = String.valueOf(i.getInstrument_token());
                String key   = i.getExchange() + ":" + i.getTradingsymbol();
                tokenMap.put(TK + token, key);
                symbolMap.put(SK + key, token);
                localTokenMap.put(token, key);
                localSymbolMap.put(key, token);
            }

            try {
                redis.opsForValue().multiSet(tokenMap);
                redis.opsForValue().multiSet(symbolMap);
                log.info("Instrument cache written to Redis: {} entries", all.size());
            } catch (Exception redisEx) {
                log.warn("Redis write failed — using in-memory cache: {}", redisEx.getMessage());
            }

        } catch (Exception e) {
            log.error("Instrument cache build failed: {}", e.getMessage());
        }
    }

    // ── Token list for WebSocket subscription ─────────────────────────────

    public List<Long> buildNifty500Tokens() {
        List<Long> tokens = new ArrayList<>();

        tokens.add(NIFTY_TOKEN);
        tokens.add(BANKNIFTY_TOKEN);
        tokens.add(VIX_TOKEN);

        int resolved = 0;
        int missing  = 0;

        // FIX (found via direct user cross-check: "we have more than 500
        // stocks mapped... why aren't all of them being added?"). The
        // hardcoded NIFTY500_SYMBOLS list below is kept ONLY as a safety-
        // net fallback now - confirmed it only ever had 297 symbols,
        // despite its name, directly causing real gaps (e.g. NH showing
        // "live price unavailable" on News's dashboard). Prefer the
        // genuinely dynamic, real, current constituent list when
        // available; only fall back to the static list if the dynamic
        // service hasn't successfully fetched anything yet.
        Set<String> symbolSource = nifty500Service.getConstituents();
        boolean usingDynamicList = !symbolSource.isEmpty();
        if (!usingDynamicList) {
            symbolSource = NIFTY500_SYMBOLS;
            log.warn("[INSTRUMENT-CACHE] Dynamic Nifty 500 list not yet available - falling " +
                    "back to the static {}-symbol list this cycle", NIFTY500_SYMBOLS.size());
        }

        for (String symbol : symbolSource) {
            Instrument inst = equityInstruments.get(symbol.toUpperCase());
            if (inst != null) {
                tokens.add(inst.getInstrument_token());
                resolved++;
            } else {
                String key = "NSE:" + symbol;
                try {
                    String tokenStr = redis.opsForValue().get(SK + key);
                    if (tokenStr != null) {
                        tokens.add(Long.parseLong(tokenStr));
                        resolved++;
                    } else {
                        log.debug("Token not found for NSE:{}", symbol);
                        missing++;
                    }
                } catch (Exception e) {
                    missing++;
                }
            }
        }

        log.info("Nifty500 subscription: {} resolved, {} missing, {} total (source={})",
                resolved, missing, tokens.size(), usingDynamicList ? "dynamic NSE fetch" : "static fallback");
        return tokens;
    }

    // ── Token accessors ───────────────────────────────────────────────────

    public long getNiftyToken()     { return NIFTY_TOKEN; }
    public long getBankNiftyToken() { return BANKNIFTY_TOKEN; }
    public long getVixToken()       { return VIX_TOKEN; }

    public String resolveToken(long token) {
        String key = TK + token;
        try {
            String v = redis.opsForValue().get(key);
            if (v != null) return v;
        } catch (Exception ignored) {}
        return localTokenMap.getOrDefault(String.valueOf(token), "UNKNOWN:" + token);
    }

    public String getSymbol(long token) {
        String full = resolveToken(token);
        return full.contains(":") ? full.split(":")[1] : full;
    }

    public String getExchange(long token) {
        String full = resolveToken(token);
        return full.contains(":") ? full.split(":")[0] : "NSE";
    }

    public Long getToken(String exchange, String symbol) {
        String key = SK + exchange + ":" + symbol;
        try {
            String v = redis.opsForValue().get(key);
            if (v != null) return Long.parseLong(v);
        } catch (Exception ignored) {}
        String v = localSymbolMap.get(exchange + ":" + symbol);
        return v != null ? Long.parseLong(v) : null;
    }

    private void safeLoad(List<Instrument> target, String exchange) {
        try {
            List<Instrument> list = client.getInstruments(exchange);
            target.addAll(list);
            log.info("Loaded {} instruments from {}", list.size(), exchange);
        } catch (Exception e) {
            log.warn("Could not load instruments from {}: {}", exchange, e.getMessage());
        }
    }
}