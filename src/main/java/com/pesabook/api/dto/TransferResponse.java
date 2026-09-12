package com.pesabook.api.dto;

import com.pesabook.ledger.Transfer;
import com.pesabook.risk.RiskAssessment;
import com.pesabook.risk.RiskSignal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransferResponse(UUID id,
                               UUID sourceAccount,
                               UUID targetAccount,
                               long amountMinor,
                               String currency,
                               String status,
                               String riskDecision,
                               List<RiskSignal> riskSignals,
                               UUID reverses,
                               Instant createdAt) {

    public static TransferResponse of(Transfer transfer, RiskAssessment assessment) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getSourceAccount(),
                transfer.getTargetAccount(),
                transfer.getAmountMinor(),
                transfer.getCurrency(),
                transfer.getStatus().name(),
                transfer.getRiskDecision().name(),
                assessment == null ? List.of() : assessment.significantSignals(),
                transfer.getReverses(),
                transfer.getCreatedAt());
    }
}
