package com.pesabook.risk;

public interface RiskRule {

    String name();

    RiskSignal evaluate(RiskContext context);
}
