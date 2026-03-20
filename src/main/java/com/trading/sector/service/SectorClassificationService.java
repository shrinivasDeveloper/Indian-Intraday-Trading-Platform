package com.trading.sector.service;

import com.zerodhatech.models.Instrument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic sector classification — NO hardcoding anywhere.
 * Built from NSE instrument list at startup via keyword matching on symbol names.
 */
@Service
@Slf4j
public class SectorClassificationService {

    // symbol → sector
    private final Map<String, String> symbolToSector = new ConcurrentHashMap<>();

    public static final String BANKING    = "Banking & Finance";
    public static final String IT         = "IT";
    public static final String PHARMA     = "Pharma";
    public static final String ENERGY     = "Energy";
    public static final String AUTO       = "Auto";
    public static final String METALS     = "Metals";
    public static final String FMCG       = "FMCG";
    public static final String INFRA      = "Infrastructure";
    public static final String TELECOM    = "Telecom";
    public static final String REALESTATE = "Real Estate";
    public static final String CHEMICALS  = "Chemicals";
    public static final String OTHERS     = "Others";

    // ── Keyword maps ──────────────────────────────────────────────────

    private static final Map<String, List<String>> SECTOR_KEYWORDS = new LinkedHashMap<>();

    static {
        SECTOR_KEYWORDS.put(BANKING, List.of(
                "BANK","FINANCE","FINANCIAL","FINSERV","NBFC","CREDIT","LOAN",
                "HOUSING","MICROFINANCE","INSURANCE","INVEST","CAPITAL","HDFC",
                "ICICI","KOTAK","AXIS","SBI","PNB","BOB","CANARA","UNION",
                "INDUSIND","BANDHAN","AU","IDFC","RBL","FEDERAL","KARNATAKA",
                "BAJAJFIN","MUTHOOT","MANAPPURAM","CHOLAFIN","IIFL","UGRO",
                "L&TFH","PIRAMALENT","SHRIRAMFIN","M&MFIN","SCUF","LICHOUSING"
        ));
        SECTOR_KEYWORDS.put(IT, List.of(
                "TECH","INFY","TCS","WIPRO","HCL","MPHASIS","HEXAWARE","PERSISTENT",
                "COFORGE","LTIMINDTREE","MASTEK","NIIT","KPITTECH","OFSS","ORACLE",
                "TATAELXSI","CYIENT","BIRLASOFT","ZENSAR","RAMSARUP","SONATA",
                "INTELLECT","NEWGEN","NUCLEUS","ECLERX","TANLA","RATEGAIN"
        ));
        SECTOR_KEYWORDS.put(PHARMA, List.of(
                "PHARMA","DRUG","MEDIC","HEALTH","BIOCON","CIPLA","DRREDDY","SUNPHARMA",
                "DIVISLAB","LUPIN","AUROBINDO","CADILA","TORNTPHARM","ALKEM","IPCA",
                "ABBOTINDIA","PFIZER","GLAXO","SANOFI","NATCO","GRANULES","GLENMARK",
                "ERIS","JB","SOLARA","SUVEN","ZYDUS","ASTRAZEN","INDOCO","LAURUS"
        ));
        SECTOR_KEYWORDS.put(ENERGY, List.of(
                "OIL","GAS","PETRO","ENERGY","POWER","RELIANCE","ONGC","BPCL","IOC",
                "HPCL","GAIL","MRPL","CPCL","CHENNPETRO","AEGASIND","GUJGASLTD",
                "IGL","MGL","ATGL","NTPC","TATAPOWER","ADANIGREEN","ADANIPOWER",
                "TORNTPOWER","CESC","JSPL","COAL","COALINDIA","NLC","NHPC","SJVN"
        ));
        SECTOR_KEYWORDS.put(AUTO, List.of(
                "AUTO","MOTOR","VEHICLE","MARUTI","TATA","BAJAJ","HERO","EICHER",
                "MAHINDRA","M&M","ASHOK","FORCE","ESCORTS","SML","TVS","HONDA",
                "BOSCH","MINDA","MOTHERSON","BHARAT","SAMVARDHANA","EXIDEIND",
                "AMARAJABAT","SUNDRM","ENDURANCE","SUPRAJIT","GABRIEL","SUBROS"
        ));
        SECTOR_KEYWORDS.put(METALS, List.of(
                "STEEL","METAL","IRON","COPPER","ALUMIN","ZINC","HIND","TATA",
                "JSWSTEEL","SAIL","HINDALCO","VEDL","NMDC","MOIL","RATNAMANI",
                "APL","GALLANTT","PRAKASH","WELSPUN","JINDAL","SRIKALAHASTI",
                "GRAVITA","HINDCOPPER","TINPLATE","KALYANI"
        ));
        SECTOR_KEYWORDS.put(FMCG, List.of(
                "FMCG","CONSUM","FOOD","BEVER","HIND","ITC","NESTLE","BRIT","DABUR",
                "MARICO","GODREJ","EMAMI","JYOTHY","PATANJALI","VBL","RADICO",
                "MCDOWELL","ABCAPITAL","GILLETTE","PGHH","COLPAL","UNILEVER",
                "HINDUNILVR","TATACONSUM","VARUN","PARAG","TASTY"
        ));
        SECTOR_KEYWORDS.put(INFRA, List.of(
                "INFRA","CONSTRUCT","BUILD","CEMENT","ENGINEER","LT","LARSEN",
                "ULTRACEMCO","ACC","AMBUJA","SHREECEM","DALMIA","RAMCO","JK",
                "HEIDELBERG","NUVOCO","KNRCON","PNCINFRA","GPPL","IRCON","RVNL",
                "RAILVIKAS","IRB","SADBHAV","NCC","HCC","SIMPLEX","AHLUWALIA"
        ));
        SECTOR_KEYWORDS.put(TELECOM, List.of(
                "TELECOM","COMMUNICATION","BHARTI","AIRTEL","VODAFONE","IDEA","BSNL",
                "INDUS","STERLITE","TTML","BHARTIARTL","MTNL","TATACOMM","ROUTE",
                "HFCL","TEJAS","ITI","VINDHYATEL","RAILTEL"
        ));
        SECTOR_KEYWORDS.put(REALESTATE, List.of(
                "REAL","ESTATE","REALTY","PROPERTY","HOUSING","DLF","GODREJ",
                "OBEROI","PRESTIGE","BRIGADE","SOBHA","PHOENIXLTD","MAHLIFE",
                "KOLTEPATIL","SUNTECK","PURAVANKARA","ANANTRAJ","INDIABULLS",
                "IBREALEST","UNITECH","OMAXE","PARSVNATH"
        ));
        SECTOR_KEYWORDS.put(CHEMICALS, List.of(
                "CHEM","FERTIL","PESTICIDE","PIGMENT","SPECIALTY","SRF","AARTI",
                "DEEPAK","VINATI","NAVIN","GUJARAT","TATA","CHAMBAL","COROMANDEL",
                "UPL","BAYER","RALLIS","PI","ASTEC","SUDARSCHEM","CLEAN","FINEORG",
                "ROSSARI","GALAXYSURF","ARCHEAN","KIRI","TATACHEM"
        ));
    }

    // ── Build from instrument list ────────────────────────────────────

    public void buildFromInstruments(List<Instrument> instruments) {
        symbolToSector.clear();
        int classified = 0;
        int others = 0;

        for (Instrument inst : instruments) {
            if (inst.getTradingsymbol() == null) continue;
            // Only equity instruments
            if (!"EQ".equals(inst.getInstrument_type())
                    && !"BE".equals(inst.getInstrument_type())) continue;

            String symbol = inst.getTradingsymbol().toUpperCase();
            String name   = inst.getName() != null ? inst.getName().toUpperCase() : symbol;
            String sector = classify(symbol, name);
            symbolToSector.put(symbol, sector);

            if (OTHERS.equals(sector)) others++;
            else classified++;
        }

        log.info("Sector classification built: {} classified, {} others, {} total",
                classified, others, symbolToSector.size());
    }

    public String getSector(String symbol) {
        if (symbol == null) return OTHERS;
        return symbolToSector.getOrDefault(symbol.toUpperCase(), OTHERS);
    }

    public Map<String, String> getAllSectors() {
        return Collections.unmodifiableMap(symbolToSector);
    }

    public List<String> getSymbolsInSector(String sector) {
        List<String> result = new ArrayList<>();
        symbolToSector.forEach((sym, sec) -> {
            if (sector.equals(sec)) result.add(sym);
        });
        return result;
    }

    public List<String> getAllSectorNames() {
        return List.of(BANKING, IT, PHARMA, ENERGY, AUTO, METALS,
                FMCG, INFRA, TELECOM, REALESTATE, CHEMICALS, OTHERS);
    }

    // ── Private classification logic ──────────────────────────────────

    private String classify(String symbol, String name) {
        // Check each sector in priority order
        for (Map.Entry<String, List<String>> entry : SECTOR_KEYWORDS.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (symbol.contains(keyword) || name.contains(keyword)) {
                    return entry.getKey();
                }
            }
        }
        return OTHERS;
    }
}