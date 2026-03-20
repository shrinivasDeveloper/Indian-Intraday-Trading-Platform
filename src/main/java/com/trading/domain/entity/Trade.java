package com.trading.domain.entity;

import com.trading.domain.enums.TradeDirection;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "trades")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Trade {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "trading_symbol", nullable = false)
    private String tradingSymbol;

    @Column(name = "instrument_token", nullable = false)
    private Long instrumentToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    private TradeDirection direction;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "entry_time")    private Instant    entryTime;
    @Column(name = "entry_price")   private BigDecimal entryPrice;
    @Column(name = "entry_order_id")private String     entryOrderId;
    @Column(name = "quantity")      private Integer    quantity;
    @Column(name = "stop_loss")     private BigDecimal stopLoss;
    @Column(name = "target")        private BigDecimal target;
    @Column(name = "sl_order_id")   private String     slOrderId;
    @Column(name = "exit_time")     private Instant    exitTime;
    @Column(name = "exit_price")    private BigDecimal exitPrice;
    @Column(name = "exit_reason")   private String     exitReason;
    @Column(name = "net_pnl")       private BigDecimal netPnl;
    @Column(name = "probability_score") private BigDecimal probabilityScore;
    @Column(name = "strategy_name") private String     strategyName;
    @Column(name = "created_at")    private Instant    createdAt;
    @Column(name = "updated_at")    private Instant    updatedAt;
}
