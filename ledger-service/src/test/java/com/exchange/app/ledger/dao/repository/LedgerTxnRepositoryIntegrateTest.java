package com.exchange.app.ledger.dao.repository;

import com.exchange.app.ledger.po.ledger.LedgerTxn;
import com.exchange.common.identity.DefaultEntityRefCodec;
import com.exchange.common.identity.EntityRefType;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

@SpringBootTest
@RunWith(SpringJUnit4ClassRunner.class)
public class LedgerTxnRepositoryIntegrateTest {
    @Autowired
    LedgerTxnRepository ledgerTxnRepository;

    @Test
    public void testUpdateVersionControl() {
        long internalId = Math.abs(System.nanoTime());
        DefaultEntityRefCodec codec = new DefaultEntityRefCodec();
        // Arrange: Create and save a new LedgerTxn
        LedgerTxn originalTxn = LedgerTxn.create(
                internalId,
                codec.encode(EntityRefType.LEDGER_TRANSACTION, internalId),
                codec.encode(EntityRefType.WALLET_TRANSACTION, internalId),
                "{}");
        ledgerTxnRepository.insertIgnore(originalTxn);

        long originalVersion = originalTxn.getVersion();
        // Act: Retrieve and update the version of the LedgerTxn
        LedgerTxn retrievedTxn = ledgerTxnRepository.getByLedgerTxnRef(
                originalTxn.getLedgerTxnRef());
        retrievedTxn.setMetadata("{\"updated\": true}");
        int affected = ledgerTxnRepository.updateMetadataByPk(retrievedTxn);

        // Assert: Verify the version is updated
        LedgerTxn updatedTxn = ledgerTxnRepository.getByLedgerTxnRef(
                originalTxn.getLedgerTxnRef());
        assert updatedTxn.getVersion() == originalVersion + 1;
        assert affected == 1;
    }
}
