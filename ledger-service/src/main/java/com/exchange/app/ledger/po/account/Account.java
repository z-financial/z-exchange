package com.exchange.app.ledger.po.account;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.exchange.app.ledger.po.enums.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("accounts")
public class Account {
    @TableId(value = "internal_id", type = IdType.INPUT)
    @ToString.Exclude
    private Long internalId;
    private String ledgerAccountRef;
    private ServiceId sourceServiceId;
    private String sourceAccountRef;
    private AccountCategory category;
    private NormalSide normalSide;
    private String ownerId;
    private OwnerType ownerType;
    private String assetId;
    private AccountStatus accountStatus;

    public static Account create(Long internalId, String ledgerAccountRef, ServiceId sourceServiceId,
                                 String sourceAccountRef, AccountCategory category, NormalSide normalSide,
                                 String ownerId, OwnerType ownerType, String assetId,
                                 AccountStatus accountStatus) {
        return new Account(internalId, ledgerAccountRef, sourceServiceId, sourceAccountRef, category, normalSide,
                ownerId, ownerType, assetId, accountStatus);
    }
}
