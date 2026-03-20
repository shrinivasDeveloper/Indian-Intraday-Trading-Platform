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
 * Instrument cache with:
 * - Dynamic sector classification (no hardcoding)
 * - Nifty500 dynamic subscription (no hardcoded token lists)
 * - Full NSE EQ instrument list
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InstrumentCacheService {

    private final ZerodhaMarketDataClient    client;
    private final StringRedisTemplate        redis;
    private final SectorClassificationService sectorService;

    private final Map<String, String> localTokenMap  = new HashMap<>();
    private final Map<String, String> localSymbolMap = new HashMap<>();

    // All NSE equity instruments: symbol → Instrument
    @Getter
    private final Map<String, Instrument> equityInstruments = new ConcurrentHashMap<>();

    private static final String TK = "inst:token:";
    private static final String SK = "inst:symbol:";

    // ── Nifty 500 symbols (used to resolve tokens dynamically) ────────
    // This is the only "list" — symbol names, NOT tokens.
    // Tokens are resolved at runtime from the downloaded instrument list.
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
            "ABCAPITAL","ABFRL","APLLTD","ASTRAL","AUROPHARMA","BALRAMCHIN",
            "BANDHANBNK","BATAINDIA","BEL","BHARATFORG","BHEL","CANFINHOME",
            "CANBK","CASTROLIND","CESC","CHAMBLFERT","CONCOR","COROMANDEL",
            "CROMPTON","CUMMINSIND","DEEPAKNTR","DELTACORP","DIXON","ESCORTS",
            "EXIDEIND","FEDERALBNK","GAIL","GNFC","GODREJPROP","GRANULES",
            "GUJGASLTD","HAL","HDFCAMC","HINDCOPPER","IBULHSGFIN","IDFC",
            "IDFCFIRSTB","IGL","INDIAMART","INDUSTOWER","INTELLECT","IOB",
            "IPCALAB","IRFC","JKCEMENT","JSWENERGY","JUBLFOOD","JUBLINGREA",
            "KAJARIACER","KANSAINER","KPITTECH","LALPATHLAB","LAURUSLABS",
            "LICHSGFIN","LINDEINDIA","LXCHEM","M&MFIN","MANAPPURAM",
            "MASFIN","MAXHEALTH","MCX","METROPOLIS","MFSL","MGL","MOTHERSON",
            "MPHASIS","NATIONALUM","NAVINFLUOR","NMDC","NYKAA","OBEROIRLTY",
            "OFSS","OIL","OLDBRIDGE","PERSISTENT","PGHH","PHOENIXLTD",
            "POLYCAB","POWMECH","PNB","PNBHOUSING","PRESTIGE","PVRINOX",
            "RAMCOCEM","RBLBANK","REDINGTON","ROUTE","RVNL","SBICARD",
            "SCHAEFFLER","SKFINDIA","SOBHA","STARHEALTH","SUMICHEM","SUNTV",
            "SUPREMEIND","SYNGENE","TATACHEM","TATACOMM","TATAELXSI",
            "TATAINVEST","TIINDIA","TIMKEN","TORNTPOWER","TRIDENT","UCOBANK",
            "UJJIVANSFB","UNIONBANK","UNITDSPR","VAIBHAVGBL","VARROC",
            "VINATIORGA","WELCORP","WHIRLPOOL","WINDLAS","ZEEL","ZYDUSLIFE",
            // Nifty Smallcap 250 (key ones)
            "AARTIDRUGS","AARTIIND","AAVAS","ABBOTINDIA","ACE","AFFLE",
            "AJANTPHARM","ALKEM","ALLCARGO","ANGELONE","ANURAS","APTUS",
            "ARVINDFASN","ASAHIINDIA","ASHOKLEY","ATUL","AWHCL","BAJAJCON",
            "BAJAJHIND","BALAJI","BALAMINES","BALKRISHNA","BASF","BATAINDIA",
            "BBTC","BFINVEST","BIKAJI","BLKASHYAP","BLUESTAR","BORORENEW",
            "BRIGADE","BSOFT","CAMPUS","CANARA","CARYSIL","CEATLTD",
            "CENTURYPLY","CENTURYTEX","CGPOWER","CHALET","CLEAN","CLEARSIGNS",
            "CLNINDIA","COCHINSHIP","CONFIDENCE","CPSEETF","CRAFTSMAN",
            "CREDITACC","CSBI","CUB","CAMS","DBREALTY","DCB","DCMSHRIRAM",
            "DELHIVERY","DHANI","DHFL","DMART","DOLLAR","DPABHUSHAN",
            "EASEMYTRIP","EDELWEISS","EMAMILTD","EMCURE","ENDURANCE","EPL",
            "EQUITASBNK","ERIS","ESTER","ETHOS","FINCABLES","FIVESTAR",
            "FLUOROCHEM","GABRIEL","GALAXYSURF","GARFIBRES","GICRE","GILLETTE",
            "GLAXO","GLENMARK","GLOBUSSPR","GODREJIND","GPIL","GREENPLY",
            "GRINDWELL","GUFICBIO","HBLPOWER","HDFCBANK","HECPROJECT",
            "HEIDELBERG","HEMIPROP","HFCL","HIKAL","HINDWAREAP","HOMEFIRST",
            "HUDCO","IDFCFIRSTB","IGPL","IIFLWAM","INDIGO","INDRAMEDCO",
            "INOXWIND","INTELLECT","IONEXCHANG","IPCA","IREDA","ITDCEM",
            "JAIBALAJI","JANASHAKTHI","JBM","JKPAPER","JLHL","JMFINANCIL",
            "JSWHL","JTLIND","JUBILANT","JUSTDIAL","KAJARIA","KALPATPOWR",
            "KARURVYSYA","KCP","KENNAMET","KFINTECH","KIRLOSBROS","KIRLOSENG",
            "KNRCON","KOPRAN","KPIL","KRBL","KSCL","LATENTVIEW","LEMONTREE",
            "LGBBROSLTD","LLOYDSENGG","LODHA","LUXIND","MAHABANK","MAHLOG",
            "MAHSCOOTER","MARATHON","MARKSANS","MASTEKLTD","MBAPL","MEDPLUS",
            "MEGH","METROBRAND","MIDHANI","MIRCELECTR","MITCON","MMTC",
            "MOGSEC","MOREPENLAB","MSTCLTD","NBCC","NCLIND","NEOGEN",
            "NETWORK18","NEWGEN","NIACL","NKIND","NSLNISP","NUCLEUS",
            "NYKAA","ORIENTELEC","ORIENTCEM","ORIENTHOT","PANAMAPET","PATELENG",
            "PATSPINN","PCJ","PDSL","PENIND","PFIZER","PGEL","PIRAMALPH",
            "PNC","PNGJEWELS","POKARNA","POLYMED","PRAJIND","PRICOLLTD",
            "PRINCEPIPE","PRISM","PRUDENT","PSPPROJECT","QUESS","RAJESHEXPO",
            "RAMASTEEL","RATNAMANI","RATEGAIN","RAYMOND","RDBUSINFRA",
            "RPGLIFE","RVNL","SAFARI","SAKAR","SANDHAR","SAPPHIRE","SATIN",
            "SCHAEFFLER","SEQUENT","SHANKARA","SHAREINDIA","SHILPAMED",
            "SHOPERSTOP","SHYAMMETL","SIEMENS","SIGNATURE","SNOWMAN",
            "SOLARA","SONACOMS","SPANDANA","SPECIALITY","SSWL","STARCEMENT",
            "STLTECH","STYRENIX","SUBROS","SUNFLAG","SUPRIYA","SUVEN",
            "SYMPHONY","TANLA","TASTYBITE","TATAINVEST","TBO","TCNSCLOTH",
            "TEJASNET","TEXRAIL","THYROCARE","TIMKEN","TIMETECHNO","TINPLATE",
            "TIRUMALCHM","TMBL","TNPL","TPLINK","TRENT","TRIL","TRIVENI",
            "UNIPARTS","USHAMART","UTIAMC","V2RETAIL","VARDHACRLC",
            "VGUARD","VIJAYABANK","VIKASECO","VINDHYATEL","VOLTAMP",
            "VSTIND","WABAG","WELSPUNIND","WESTLIFE","WOCKPHARMA","ZENSARTECH"
    ));

    // ── Index tokens (always subscribe in FULL mode) ──────────────────
    // These are index tokens that don't change — only these are hardcoded
    private static final long NIFTY_TOKEN     = 256265L;
    private static final long BANKNIFTY_TOKEN = 260105L;
    private static final long VIX_TOKEN       = 264969L;  // India VIX

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

            // Build sector classification from NSE equity instruments
            List<Instrument> nseEquity = all.stream()
                    .filter(i -> "NSE".equals(i.getExchange())
                            && ("EQ".equals(i.getInstrument_type())
                            || "BE".equals(i.getInstrument_type())))
                    .collect(java.util.stream.Collectors.toList());

            sectorService.buildFromInstruments(nseEquity);
            log.info("Sector classification built for {} NSE equity instruments", nseEquity.size());

            // Store all equity instruments by symbol
            for (Instrument i : nseEquity) {
                equityInstruments.put(i.getTradingsymbol().toUpperCase(), i);
            }

            // Build Redis token/symbol maps
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

    /**
     * Build Nifty500 subscription token list DYNAMICALLY.
     * Resolves token for each symbol from the downloaded instrument list.
     * No hardcoded tokens anywhere.
     */
    public List<Long> buildNifty500Tokens() {
        List<Long> tokens = new ArrayList<>();

        // Always include index tokens for market direction
        tokens.add(NIFTY_TOKEN);
        tokens.add(BANKNIFTY_TOKEN);
        tokens.add(VIX_TOKEN);

        int resolved = 0;
        int missing  = 0;

        for (String symbol : NIFTY500_SYMBOLS) {
            Instrument inst = equityInstruments.get(symbol.toUpperCase());
            if (inst != null) {
                tokens.add(inst.getInstrument_token());
                resolved++;
            } else {
                // Try Redis fallback
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

        log.info("Nifty500 subscription: {} resolved, {} missing, {} total tokens",
                resolved, missing, tokens.size());
        return tokens;
    }

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