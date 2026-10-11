package com.exchange.app.wallet.po.wallet;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("wallet_account_mappings")
public class WalletAccountMapping {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String walletRef;
    private String ledgerAccountRef;

    public static WalletAccountMapping create(String walletRef, String ledgerAccountRef) {
        return new WalletAccountMapping(0L, walletRef, ledgerAccountRef);
    }
}
