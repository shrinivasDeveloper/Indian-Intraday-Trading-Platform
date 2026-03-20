package com.trading.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "orders")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Order {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_id")           private Long       tradeId;
    @Column(name = "zerodha_order_id", unique = true) private String zerodhaOrderId;
    @Column(name = "trading_symbol")     private String     tradingSymbol;
    @Column(name = "order_type")         private String     orderType;
    @Column(name = "transaction_type")   private String     transactionType;
    @Column(name = "quantity")           private int        quantity;
    @Column(name = "price")              private BigDecimal price;
    @Column(name = "trigger_price")      private BigDecimal triggerPrice;
    @Column(name = "status")             private String     status;
    @Column(name = "filled_quantity")    private int        filledQuantity;
    @Column(name = "average_price")      private BigDecimal averagePrice;
    @Column(name = "rejection_reason")   private String     rejectionReason;
    @Column(name = "placed_at")          private Instant    placedAt;
}
