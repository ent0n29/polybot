package com.polybot.hft.events;

public final class HftEventTypes {

  private HftEventTypes() {
  }

  public static final String MARKET_WS_TOB = "market_ws.tob";

  public static final String STRATEGY_HOUSE_EDGE_QUOTE = "strategy.house_edge.quote";
  public static final String STRATEGY_HOUSE_EDGE_DISCOVERY_SELECTED = "strategy.house_edge.discovery_selected";

  public static final String EXECUTOR_ORDER_LIMIT = "executor.order.limit";
  public static final String EXECUTOR_ORDER_MARKET = "executor.order.market";
  public static final String EXECUTOR_ORDER_CANCEL = "executor.order.cancel";
}

