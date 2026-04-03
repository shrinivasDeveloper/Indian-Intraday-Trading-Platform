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
 * ADDED (was patch file, now full implementation):
 *   getBankNiftyToken() — required by BankNiftyModeEngine, WarmupService, MarketDataStartupService.
 *   Default token 260105L (NSE BankNifty) is correct for all Zerodha accounts.
 *
 * EXISTING METHODS (unchanged):
 *   build()                — downloads instrument list from NSE/NFO/BSE
 *   buildNifty500Tokens()  — returns List<Long> of all subscribed tokens
 *   getNiftyToken()        — returns 256265L
 *   getVixToken()          — returns 264969L
 *   resolveToken(long)     — token → "NSE:RELIANCE"
 *   getSymbol(long)        — token → "RELIANCE"
 *   getExchange(long)      — token → "NSE"
 *   getToken(String, String) — "NSE","RELIANCE" → token
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InstrumentCacheService {

    private final ZerodhaMarketDataClient     client;
    private final StringRedisTemplate         redis;
    private final SectorClassificationService sectorService;

    private final Map<String, String> localTokenMap  = new HashMap<>();
    private final Map<String, String> localSymbolMap = new HashMap<>();

    @Getter
    private final Map<String, Instrument> equityInstruments = new ConcurrentHashMap<>();

    private static final String TK = "inst:token:";
    private static final String SK = "inst:symbol:";

    // ── Index tokens (hardcoded — these never change on NSE) ─────────────

    private static final long NIFTY_TOKEN     = 256265L;
    private static final long BANKNIFTY_TOKEN = 260105L;  // ← ADDED (was in patch)
    private static final long VIX_TOKEN       = 264969L;

    // ── Nifty 500 symbols ─────────────────────────────────────────────────

    private static final Set<String> NIFTY500_SYMBOLS = new HashSet<>(List.of(
            // Nifty 50
            "RELIANCE","TCS","HDFCBANK","INFY","ICICIBANK","HINDUNILVR","ITC",
            "SBIN","BHARTIARTL","KOTAKBANK","LT","BAJFINANCE","HCLTECH","ASIANPAINT",
            "AXISBANK","MARUTI","SUNPHARMA","TITAN","BAJAJFINSV","ULTRACEMCO",
            "ONGC","WIPRO","TECHM","NTPC","POWERGRID","JSWSTEEL","TATAMOTORS",
            "TATASTEEL","ADANIENT","ADANIPORTS","COALINDIA","DIVISLAB","DRREDDY",
            "CIPLA","EICHERMOT","GRASIM","HEROMOTOCO","HINDALCO","INDUSINDBK",
            "M&M","NESTLEIND","SBILIFE","SHREECEM","TATACONSUM","UPL","VEDL",
            "BRITANNIA","APOLLOHOSP","BAJAJ-AUTO","BPCL",
            // Nifty Next 50
            "ADANIGREEN","ADANITRANS","AMBUJACEM","AUROPHARMA","BAJAJHLDNG",
            "BANKBARODA","BERGEPAINT","BIOCON","BOSCHLTD","CHOLAFIN","COLPAL",
            "DABUR","DLF","HAVELLS","HDFCLIFE","HINDPETRO","ICICIPRULI",
            "ICICIGI","INDHOTEL","IOC","IRCTC","LUPIN","MARICO","MCDOWELL-N",
            "MUTHOOTFIN","NAUKRI","PAGEIND","PIDILITIND","PIIND","RECLTD",
            "SAIL","SIEMENS","SRF","TORNTPHARM","TRENT","TVSMOTOR","VBL",
            "VOLTAS","WHIRLPOOL","ZOMATO",
            // Nifty Midcap 150 (key ones)
            "ABCAPITAL","ABFRL","APLLTD","ASTRAL","BALRAMCHIN",
            "BANDHANBNK","BATAINDIA","BEL","BHARATFORG","BHEL","CANFINHOME",
            "CANBK","CASTROLIND","CESC","CHAMBLFERT","CONCOR","COROMANDEL",
            "CROMPTON","CUMMINSIND","DEEPAKNTR","DELTACORP","DIXON","ESCORTS",
            "EXIDEIND","FEDERALBNK","GAIL","GNFC","GODREJPROP","GRANULES",
            "GUJGASLTD","HAL","HDFCAMC","HINDCOPPER","IBULHSGFIN","IDFC",
            "IDFCFIRSTB","IGL","INDIAMART","INDUSTOWER","INTELLECT","IOB",
            "IPCALAB","IRFC","JKCEMENT","JSWENERGY","JUBLFOOD","JUBLINGREA",
            "KAJARIACER","KANSAINER","KPITTECH","LALPATHLAB","LAURUSLABS",
            "LICHSGFIN","LINDEINDIA","M&MFIN","MANAPPURAM",
            "MAXHEALTH","MCX","METROPOLIS","MFSL","MGL","MOTHERSON",
            "MPHASIS","NATIONALUM","NAVINFLUOR","NMDC","NYKAA","OBEROIRLTY",
            "OFSS","OIL","PERSISTENT","PGHH","PHOENIXLTD",
            "POLYCAB","PNB","PNBHOUSING","PRESTIGE","PVRINOX",
            "RAMCOCEM","RBLBANK","REDINGTON","RVNL","SBICARD",
            "SCHAEFFLER","SKFINDIA","SOBHA","STARHEALTH","SUMICHEM","SUNTV",
            "SUPREMEIND","SYNGENE","TATACHEM","TATACOMM","TATAELXSI",
            "TIINDIA","TIMKEN","TORNTPOWER","TRIDENT","UCOBANK",
            "UJJIVANSFB","UNIONBANK","VAIBHAVGBL",
            "VGUARD","VINATIORGA","WELCORP","ZEEL","ZYDUSLIFE",
            // Nifty Smallcap 250 (key ones)
            "AARTIDRUGS","AARTIIND","AAVAS","ABBOTINDIA","AFFLE",
            "AJANTPHARM","ALKEM","ALLCARGO","ANGELONE","APTUS",
            "ASHOKLEY","ATUL","BAJAJCON","BALKRISHNA","BASF","CEATLTD",
            "CENTURYPLY","CGPOWER","CRAFTSMAN","CREDITACC","CUB",
            "DBREALTY","DCB","DELHIVERY","DMART","EMAMILTD",
            "ENDURANCE","EQUITASBNK","ERIS","FIVESTAR","FLUOROCHEM",
            "GODREJIND","GPIL","GRINDWELL","HAL","HBLPOWER",
            "HOMEFIRST","HUDCO","INDIGO","IONEXCHANG","IPCA","IREDA",
            "JKPAPER","JMFINANCIL","JUSTDIAL","KAJARIA","KARURVYSYA",
            "KFINTECH","KIRLOSBROS","KNRCON","KPIL","LATENTVIEW",
            "LEMONTREE","LUXIND","MEDPLUS","MIDHANI","MIRCELECTR",
            "NBCC","NEOGEN","NEWGEN","NIACL","NUCLEUS",
            "ORIENTELEC","ORIENTCEM","PATELENG","PFIZER","PIRAMALPH",
            "POLYCAB","POLYMED","PRAJIND","PRINCEPIPE","PRUDENT",
            "PSPPROJECT","QUESS","RAJESHEXPO","RATNAMANI","RATEGAIN",
            "RAYMOND","RVNL","SAFARI","SANDHAR","SAPPHIRE","SATIN",
            "SEQUENT","SHANKARA","SHAREINDIA","SHILPAMED","SHOPERSTOP",
            "SNOWMAN","SOLARA","SONACOMS","SPANDANA","STLTECH",
            "SUBROS","SUVEN","SYMPHONY","TANLA","TATAINVEST","TBO",
            "THYROCARE","TIMKEN","TRENT","TRIVENI","UNIPARTS",
            "USHAMART","UTIAMC","VGUARD","VOLTAMP","VSTIND",
            "WABAG","WELSPUNIND","WESTLIFE","WOCKPHARMA","ZENSARTECH",
            "AUBANK","RBLBANK","YESBANK" // BankNifty constituents
    ));

    // ── Build ─────────────────────────────────────────────────────────────

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

    // ── Token list for WebSocket subscription ────────────────────────────

    public List<Long> buildNifty500Tokens() {
        List<Long> tokens = new ArrayList<>();

        tokens.add(NIFTY_TOKEN);
        tokens.add(BANKNIFTY_TOKEN);  // ← always include BankNifty
        tokens.add(VIX_TOKEN);

        int resolved = 0;
        int missing  = 0;

        for (String symbol : NIFTY500_SYMBOLS) {
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

        log.info("Nifty500 subscription: {} resolved, {} missing, {} total",
                resolved, missing, tokens.size());
        return tokens;
    }

    // ── Token accessors ───────────────────────────────────────────────────

    /** Returns Nifty 50 index token (256265). Always correct for NSE. */
    public long getNiftyToken()     { return NIFTY_TOKEN; }

    /**
     * Returns BankNifty index token (260105).
     * Required by: BankNiftyModeEngine, WarmupService, MarketDataStartupService.
     * Default 260105L is the standard NSE BankNifty token for all Zerodha accounts.
     */
    public long getBankNiftyToken() { return BANKNIFTY_TOKEN; }

    /** Returns India VIX token (264969). */
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