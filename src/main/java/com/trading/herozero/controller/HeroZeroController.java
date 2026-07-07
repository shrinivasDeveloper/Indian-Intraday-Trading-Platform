package com.trading.herozero.controller;

import com.trading.herozero.config.HeroZeroConfig;
import com.trading.herozero.domain.HeroZeroTrade;
import com.trading.herozero.repository.HeroZeroTradeRepository;
import com.trading.herozero.util.MonthlyExpiryCalculator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HeroZeroController - independent REST endpoints for this strategy.
 * INDEPENDENCE: a completely separate controller, not added to or
 * routed through the existing DashboardController, per explicit spec
 * requirement.
 */
@RestController
@RequestMapping("/api/herozero")
@Slf4j
public class HeroZeroController {

    private final HeroZeroConfig config;
    private final HeroZeroTradeRepository repo;
    private final MonthlyExpiryCalculator expiryCalculator;

    public HeroZeroController(HeroZeroConfig config, HeroZeroTradeRepository repo,
                              MonthlyExpiryCalculator expiryCalculator) {
        this.config = config;
        this.repo = repo;
        this.expiryCalculator = expiryCalculator;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", config.isEnabled());
        out.put("entryTime", config.getEntryTime().toString());
        out.put("exitTime", config.getExitTime().toString());

        Map<String, Object> indexes = new LinkedHashMap<>();
        for (String index : List.of("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")) {
            MonthlyExpiryCalculator.ExpiryResult r = expiryCalculator.calculate(index, LocalDate.now(ZoneId.of("Asia/Kolkata")));
            Map<String, Object> idxInfo = new LinkedHashMap<>();
            idxInfo.put("naturalExpiry", r.naturalExpiry().toString());
            idxInfo.put("actualExpiry", r.actualExpiry().toString());
            idxInfo.put("wasHolidayShifted", r.wasShifted());
            idxInfo.put("isMonthlyExpiryToday", r.actualExpiry().isEqual(LocalDate.now(ZoneId.of("Asia/Kolkata"))));
            indexes.put(index, idxInfo);
        }
        out.put("indexes", indexes);
        out.put("activeTrades", repo.findActive());
        return out;
    }

    @GetMapping("/trades")
    public List<HeroZeroTrade> allTrades() {
        return repo.findAll();
    }

    @GetMapping("/trades/{tradeId}")
    public Object tradeById(@PathVariable String tradeId) {
        return repo.findById(tradeId).orElse(null);
    }

    @GetMapping("/expiry/{index}")
    public Object expiryForIndex(@PathVariable String index) {
        return expiryCalculator.calculate(index, LocalDate.now(ZoneId.of("Asia/Kolkata")));
    }
}