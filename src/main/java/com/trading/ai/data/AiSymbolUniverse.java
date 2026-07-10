package com.trading.ai.data;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AiSymbolUniverse
 *
 * Manages the universe of Nifty500 symbols the AI module scans.
 *
 * FIX (found via direct user cross-check: confirmed via precise
 * comparison that of this class's 527 hardcoded symbols, 293 (55%!)
 * had ZERO live WebSocket price coverage - meaning AI could scan
 * their historical daily patterns but could NEVER pass the mandatory
 * live confirmation-candle gate or get a valid live entry price for
 * them. Real, liquid stocks like ABB, ACC, ADANIPOWER, 3MINDIA were
 * silently unable to ever actually trade, despite being scored.
 *
 * Now sources from the SAME shared Nifty500ConstituentService already
 * built for News's identical gap - this guarantees AI only ever scans
 * symbols it genuinely has live price coverage for, since both AI and
 * the WebSocket subscription now read from the identical, real,
 * dynamically-refreshed NSE constituent list. Falls back to the
 * original static list ONLY if the dynamic service hasn't fetched
 * successfully yet (e.g. very first startup before its own
 * @PostConstruct completes, or if niftyindices.com is unreachable) -
 * this class's own independence from other STRATEGIES (AI/News/Swing
 * business logic) is preserved; Nifty500ConstituentService lives in a
 * neutral, shared package (com.trading.shared.marketdata), not inside
 * any other strategy's own module - it's genuinely shared
 * infrastructure, not a cross-strategy dependency.
 */
@Component
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
public class AiSymbolUniverse {

    private final com.trading.shared.marketdata.Nifty500ConstituentService nifty500Service;

    public AiSymbolUniverse(com.trading.shared.marketdata.Nifty500ConstituentService nifty500Service) {
        this.nifty500Service = nifty500Service;
    }

    // Original 527-symbol hardcoded list - KEPT UNCHANGED, now used only
    // as a safety-net fallback, exactly the same pattern already proven
    // for News's identical fix.
    private static final List<String> SYMBOLS = List.of(
            "RELIANCE","TCS","HDFCBANK","INFY","ICICIBANK","HINDUNILVR","ITC","SBIN",
            "BAJFINANCE","BHARTIARTL","KOTAKBANK","LT","AXISBANK","ASIANPAINT","MARUTI","NESTLEIND",
            "TITAN","SUNPHARMA","ULTRACEMCO","WIPRO","HCLTECH","TECHM","POWERGRID","NTPC",
            "ONGC","COALINDIA","TATASTEEL","JSWSTEEL","HINDALCO","GRASIM","BAJAJ-AUTO","EICHERMOT",
            "HEROMOTOCO","DRREDDY","CIPLA","DIVISLAB","APOLLOHOSP","ADANIPORTS","ADANIGREEN","ADANIENT",
            "ATGL","BAJAJFINSV","HDFCLIFE","SBILIFE","ICICIPRULI","MUTHOOTFIN","CHOLAFIN","PFC",
            "RECLTD","IRCTC","INDIGO","TATACONSUM","TMCV","DABUR","GODREJCP","PIDILITIND",
            "BERGEPAINT","MARICO","COLPAL","EMAMILTD","BRITANNIA","TATAPOWER","TRENT","VEDL",
            "NATIONALUM","NMDC","SAIL","BHEL","SIEMENS","ABB","HAVELLS","VOLTAS",
            "BLUESTARCO","WHIRLPOOL","BOSCHLTD","CUMMINSIND","THERMAX","IRB","PRESTIGE","GODREJPROP",
            "OBEROIRLTY","DLF","PHOENIXLTD","LTM","MPHASIS","PERSISTENT","COFORGE","LTTS",
            "KPITTECH","TATAELXSI","OFSS","ETERNAL","PAYTM","POLICYBZR","NYKAA","DELHIVERY",
            "CARTRADE","SAPPHIRE","BANKINDIA","CANBK","INDIANB","UNIONBANK","PNB","FEDERALBNK",
            "IDFCFIRSTB","BANDHANBNK","RBLBANK","YESBANK","KARURVYSYA","CREDITACC","MRF","APOLLOTYRE",
            "CEATLTD","BALKRISIND","TVSMOTOR","MOTHERSON","BHARATFORG","ENDURANCE","UNOMINDA","ESCORTS",
            "PAGEIND","DMART","JUBLFOOD","DEVYANI","ZYDUSLIFE","ALKEM","IPCALAB","NATCOPHARM",
            "GRANULES","LAURUSLABS","AUROPHARMA","TORNTPHARM","AJANTPHARM","GLAND","GLAXO","PFIZER",
            "ABBOTINDIA","NEULANDLAB","TATACHEM","DEEPAKNTR","AAVAS","APTUS","HOMEFIRST","ICICIGI",
            "STARHEALTH","NIACL","GICRE","NEWGEN","MFSL","IIFL","ANGELONE","MOTILALOFS",
            "MCX","BSE","CDSL","CAMS","KFINTECH","IRFC","RVNL","HUDCO",
            "NBCC","NHPC","SJVN","OIL","MGL","IGL","GAIL","PETRONET",
            "SUPREMEIND","ASTRAL","FINCABLES","ACC","AMBUJACEM","JKCEMENT","RAMCOCEM","JINDALSAW",
            "WELCORP","KALYANKJIL","ZEEL","PVRINOX","INOXWIND","IREDA","TORNTPOWER","CESC",
            "JPPOWER","ADANIPOWER","POLYCAB","KEI","BATAINDIA","ASTERDM","MAXHEALTH","FORTIS",
            "NH","KIMS","RAINBOW","MAZDOCK","GRSE","BEL","HAL","BEML",
            "CYIENT","ZENSARTECH","HEXT","BSOFT","INTELLECT","CONCOR","BLUEDART","360ONE",
            "ABCAPITAL","AMBER","ANANTRAJ","APLAPOLLO","ARE&M","ASHOKLEY","AUBANK","BAJAJHFL",
            "BALRAMCHIN","BANKBARODA","CANFINHOME","CGPOWER","CHAMBLFERT","CHOLAHLDNG","CLEAN","COROMANDEL",
            "CROMPTON","DALBHARAT","DIXON","EIDPARRY","EXIDEIND","FACT","FIVESTAR","FLUOROCHEM",
            "FORCEMOT","GMRAIRPORT","GODREJIND","HDFCAMC","HINDCOPPER","HINDPETRO","HINDZINC","HYUNDAI",
            "IDBI","IDEA","INDHOTEL","INDUSINDBK","IOB","IOC","JBCHEPHARM","JSWENERGY",
            "JSWINFRA","JUBLINGREA","KAJARIACER","KAYNES","KEC","KPIL","LALPATHLAB","LICI",
            "LINDEINDIA","LLOYDSME","LODHA","LTF","LUPIN","M&M","M&MFIN","MAHABANK",
            "MANKIND","MRPL","NAM-INDIA","NAUKRI","NLCINDIA","NSLNISP","NTPCGREEN","NUVAMA",
            "OLECTRA","PATANJALI","PNBHOUSING","POLYMED","PPLPHARMA","PREMIERENE","RADICO","REDINGTON",
            "RKFORGE","RPOWER","SBFC","SBICARD","SCHAEFFLER","SHREECEM","SHYAMMETL","SOLARINDS",
            "SONACOMS","SUMICHEM","SUNDARMFIN","SUZLON","SYNGENE","TATACOMM","TATATECH","TIINDIA",
            "TITAGARH","TRIDENT","UBL","UCOBANK","UNITDSPR","UPL","USHAMART","UTIAMC",
            "VBL","VIJAYA","WAAREEENER","WELSPUNLIV","WOCKPHARMA","ZENTEC","ZFCVINDIA",
            // user-confirmed valid NSE symbols
            "5PAISA","CAMPUS","CENTURYTEX","DISHTV","EQUITASBNK","ESAFSFB","GATEWAY",
            "GEOJITFSL","GPPL","GREENPANEL","GSPL","HATHWAY","HEIDELBERG","IOLCP","ISEC","KSOLVES",
            "METROBRAND","MOIL","NETWORK18","PARAS","PCJEWELLER","PRAJIND","PRINCEPIPE","RAJESHEXPO",
            "RATEGAIN","RATNAMANI","RAYMOND","RELAXO","SANOFI","SENCO","SEQUENT","SHOPERSTOP",
            "SOUTHBANK","SUNDRMFAST","SURYODAY","SUVENPHARM","TRIVENI","UJJIVANSFB","VMART","WESTLIFE",
            "AEGISLOG","BIRLACORP","VMM","MTARTECH","TBZ",
            // newly added — missing high-liquidity Nifty500
            "3MINDIA","AADHARHFC","AARTIIND","ABDL","ABFRL","ABLBL","ABREL","ABSLAMC",
            "ACE","ACMESOLAR","ACUTAAS","AEGISVOPAK","AFCONS","AFFLE","AIAENG","AIIL",
            "ANANDRATHI","ANTHEM","ANURAS","APARINDS","ASAHIINDIA","ATHERENERG","ATUL","AWL",
            "BAJAJHLDNG","BAYERCROP","BBTC","BDL","BELRISE","BHARTIHEXA","BIKAJI","BLS",
            "BLUEJET","BRIGADE","CANHLIFE","CAPLIPOINT","CARBORUNIV","CASTROLIND","CEMPRO","CENTRALBK",
            "CGCL","CHALET","CHENNPETRO","CHOICEIN","CIEINDIA","COHANCE","CONCORDBIO","CPPLUS",
            "CRAFTSMAN","CRISIL","CUB","DATAPATTNS","DCMSHRIRAM","DEEPAKFERT","DOMS","ECLERX",
            "EIHOTEL","ELECON","ELGIEQUIP","EMCURE","EMMVEE","ENGINERSIN","ERIS","FIRSTCRY",
            "FSL","GABRIEL","GALLANTT","GESHIP","GILLETTE","GLENMARK","GMDCLTD","GODFRYPHLP",
            "GODIGIT","GPIL","GRAPHITE","GRAVITA","GROWW","GVT&D","HBLENGINE","HDBFS",
            "HEG","HFCL","HONASA","HONAUT","HSCL","ICICIAMC","IEX","IFCI",
            "IGIL","IKS","INDGN","INDIACEM","INDIAMART","IRCON","ITCHOTELS","ITI",
            "J&KBANK","JAINREC","JBMA","JINDALSTEL","JIOFIN","JKTYRE","JMFINANCIL","JSL",
            "JSWCEMENT","JSWDULUX","JUBLPHARMA","JWL","JYOTICNC","KIRLOSENG","KPRMILL","LATENTVIEW",
            "LGEINDIA","LICHSGFIN","LTFOODS","MANAPPURAM","MAPMYINDIA","MEDANTA","MEESHO","MINDACORP",
            "MMTC","MSUMI","NAVA","NAVINFLUOR","NCC","NETWEB","NIVABUPA","NUVOCO",
            "OLAELEC","ONESOURCE","PARADEEP","PCBL","PGEL","PIIND","PINELABS","PIRAMALFIN",
            "POONAWALLA","POWERINDIA","PWL","RAILTEL","RHIM","RITES","RRKABEL","SAGILITY",
            "SAILIFE","SAMMAANCAP","SARDAEN","SAREGAMA","SCHNEIDER","SCI","SHRIRAMFIN","SIGNATURE",
            "SOBHA","SONATSOFTW","SPLPETRO","SRF","SUNTV","SWANCORP","SWIGGY","SYRMA",
            "TARIL","TATACAP","TATAINVEST","TBOTEK","TECHNOE","TEGA","TEJASNET","TENNIND",
            "TIMKEN","TMPV","TRAVELFOOD","TRITURBINE","TTML","URBANCO","VTL","ZYDUSWELL"
    );
    public List<String> getSymbols() {
        java.util.Set<String> dynamic = nifty500Service.getConstituents();
        if (!dynamic.isEmpty()) {
            return new ArrayList<>(dynamic);
        }
        log.warn("[AI-UNIVERSE] Dynamic Nifty 500 list not yet available - falling back to " +
                "the static {}-symbol list this cycle (may include symbols without live " +
                "WebSocket price coverage)", SYMBOLS.size());
        return SYMBOLS;
    }

    public int size() {
        return getSymbols().size();
    }

    public boolean contains(String symbol) {
        return getSymbols().contains(symbol);
    }
}