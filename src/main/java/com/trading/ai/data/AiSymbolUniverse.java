package com.trading.ai.data;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AiSymbolUniverse
 *
 * Manages the universe of 253 Nifty500 symbols the AI module scans.
 * Owned entirely by the AI module — no dependency on other strategies.
 *
 * The list mirrors the Nifty500 constituents. The AI module makes its
 * own independent decision about which symbols to evaluate.
 */
@Component
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
public class AiSymbolUniverse {

    // 253 Nifty500 symbols — AI module's own universe
    private static final List<String> SYMBOLS = List.of(
            "RELIANCE","TCS","HDFCBANK","INFY","ICICIBANK","HINDUNILVR","ITC","SBIN",
            "BAJFINANCE","BHARTIARTL","KOTAKBANK","LT","AXISBANK","ASIANPAINT","MARUTI",
            "NESTLEIND","TITAN","SUNPHARMA","ULTRACEMCO","WIPRO","HCLTECH","TECHM",
            "POWERGRID","NTPC","ONGC","COALINDIA","TATASTEEL","JSWSTEEL","HINDALCO",
            "GRASIM","BAJAJ-AUTO","EICHERMOT","HEROMOTOCO","DRREDDY","CIPLA",
            "DIVISLAB","APOLLOHOSP","ADANIPORTS","ADANIGREEN","ADANIENT","ADANITRANS",
            "BAJAJFINSV","HDFCLIFE","SBILIFE","ICICIPRULI","MUTHOOTFIN","CHOLAFIN",
            "PFC","RECLTD","IRCTC","INDIGO","TATACONSUM","TATAMOTORS","DABUR","GODREJCP",
            "PIDILITIND","BERGEPAINT","MARICO","COLPAL","EMAMILTD","BRITANNIA",
            "TATAPOWER","TRENT","VEDL","NATIONALUM","NMDC","SAIL","MOIL",
            "BHEL","SIEMENS","ABB","HAVELLS","VOLTAS","BLUESTARCO","WHIRLPOOL",
            "BOSCHLTD","CUMMINSIND","THERMAX","GMRINFRA","IRB","PRESTIGE","GODREJPROP",
            "OBEROIRLTY","DLF","PHOENIXLTD","MINDTREE","MPHASIS","PERSISTENT","COFORGE",
            "LTTS","LTIM","KPITTECH","TATAELXSI","OFSS","ZOMATO","PAYTM","POLICYBZR",
            "NYKAA","DELHIVERY","CARTRADE","METROBRAND","SAPPHIRE","CAMPUS",
            "BANKINDIA","CANARABANK","INDIANB","UNIONBANK","PNB","FEDERALBNK",
            "IDFCFIRSTB","BANDHANBNK","RBLBANK","YESBANK","KARURVYSYA","SOUTHBANK",
            "CREDITACC","EQUITASBNK","UJJIVANSFB","SURYODAY","ESAFSFB",
            "MRF","APOLLOTYRE","CEAT","BALKRISIND","TVSMOTORS","MOTHERSON",
            "BAJAJAUTO","BHARATFORG","ENDURANCE","SUNDRMFAST","MINDA","UNOMINDA",
            "ESCORTS","GREENPANEL","CENTURYTEX","RAYMOND","PAGEIND","MANYAVAR",
            "VMART","DMART","TRENT","SHOPERSTOP","JUBLFOOD","DEVYANI","WESTLIFE",
            "ZYDUSLIFE","ALKEM","IPCALAB","NATCOPHARM","GRANULES","LAURUSLABS",
            "SUVENPHARM","AUROPHARMA","TORNTPHARM","AJANTPHARM","GLAND","SEQUENT",
            "GLAXO","PFIZER","ABBOTINDIA","SANOFI","IOLCP","NEULANDLAB",
            "TATACHEM","PIDILITIND","DEEPAKNTR","AAVAS","APTUS","HOMEFIRST",
            "ICICIGI","STARHEALTH","NIACL","GICRE","NEWGEN","MFSL",
            "IIFL","ISEC","5PAISA","ANGELONE","MOTILALOFS","GEOJITFSL",
            "MCX","BSE","CDSL","CAMS","KFINTECH",
            "IRFC","RVNL","HUDCO","NBCC","NHPC","SJVN","THDC",
            "OIL","MGL","IGL","GAIL","PETRONET","GSPL","AEGISCHEM",
            "SUPREMEIND","ASTRAL","FINOLEX","PRINCEPIPE","KSOLVES",
            "ACC","AMBUJACEMENT","JKCEMENT","RAMCOCEM","HEIDELBERG","BIRLACORPN",
            "JINDALSAW","WELCORP","RATNAMANI","PRAKASHSTL","KALYANKJIL","SENCO",
            "PCJEWELLER","TRIBHOVANDAS","TITAN","RAJESHEXPO",
            "ZEEL","PVRINOX","INOXWIND","NETWORK18","HATHWAY","DISHTV",
            "IREDA","GREENKO","TORNTPOWER","CESC","JPPOWER","ADANIPOWER",
            "VOLTAS","BLUESTARCO","HAVELLS","POLYCAB","KEI","FINOLEX",
            "BATAINDIA","LIBERTY","RELAXO","SREELEATHERS",
            "ASTERDM","MAXHEALTH","FORTIS","NH","KIMS","RAINBOW",
            "MAZDOCK","GRSE","BEL","HAL","BEML","MTAR","PARAS",
            "CYIENT","ZENSAR","HEXAWARE","BIRLASOFT","RATEGAIN","INTELLECT",
            "PRAJIND","TRIVENI","GPPL","CONCOR","GATEWAY","BLUEDART"
    );

    public List<String> getSymbols() {
        return SYMBOLS;
    }

    public int size() {
        return SYMBOLS.size();
    }

    public boolean contains(String symbol) {
        return SYMBOLS.contains(symbol);
    }
}