/*
 * Created by Michael Carrara <michael.carrara@breadwallet.com> on 5/31/18.
 * Copyright (c) 2018 Breadwinner AG.  All right reserved.
 *
 * See the LICENSE file at the project root for license information.
 * See the CONTRIBUTORS file at the project root for a list of contributors.
 */
package com.breadwallet.corecrypto;

import android.util.Log;

import com.breadwallet.crypto.BuildConfig;
import com.breadwallet.crypto.blockchaindb.BlockchainDb;
import com.breadwallet.crypto.blockchaindb.errors.QueryError;
import com.breadwallet.crypto.blockchaindb.models.bdb.Blockchain;
import com.breadwallet.crypto.blockchaindb.models.bdb.BlockchainFee;
import com.breadwallet.crypto.blockchaindb.models.bdb.CurrencyDenomination;
import com.breadwallet.crypto.utility.CompletionHandler;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.primitives.UnsignedInteger;
import com.google.common.primitives.UnsignedLong;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.base.Preconditions.checkState;

/* package */
final class NetworkDiscovery {

    private static final String TAG = NetworkDiscovery.class.getName();

    /* package */
    interface Callback {
        void discovered(List<Network> networks);
    }

    /* package */
    static void discoverNetworks(BlockchainDb query, Callback callback) {
        boolean isMainnet = BuildConfig.IS_MAINNET;

        List<Network> networks = new ArrayList<>();
        CountUpAndDownLatch latch = new CountUpAndDownLatch(() -> callback.discovered(networks));

        getBlockChains(latch, query, Blockchain.DEFAULT_BLOCKCHAINS, isMainnet, blockchainModels -> {
            for (Blockchain blockchainModel : blockchainModels) {
                String blockchainModelId = blockchainModel.getId();

                final List<com.breadwallet.crypto.blockchaindb.models.bdb.Currency> defaultCurrencies = new ArrayList<>();
                for (com.breadwallet.crypto.blockchaindb.models.bdb.Currency currency :
                        com.breadwallet.crypto.blockchaindb.models.bdb.Currency.DEFAULT_CURRENCIES) {
                    if (currency.getBlockchainId().equals(blockchainModelId)) {
                        defaultCurrencies.add(currency);
                    }
                }

                Map<Currency, NetworkAssociation> associations = new HashMap<>();

                getCurrencies(latch, query, blockchainModelId, defaultCurrencies, currencyModels -> {
                    for (com.breadwallet.crypto.blockchaindb.models.bdb.Currency currencyModel : currencyModels) {
                        if (!blockchainModelId.equals(currencyModel.getBlockchainId())) {
                            continue;
                        }

                        Currency currency = Currency.create(
                                currencyModel.getId(),
                                currencyModel.getName(),
                                currencyModel.getCode(),
                                currencyModel.getType(),
                                currencyModel.getAddress().orNull());

                        Optional<CurrencyDenomination> baseDenomination = findFirstBaseDenomination(currencyModel.getDenominations());
                        List<CurrencyDenomination> nonBaseDenominations = findAllNonBaseDenominations(currencyModel.getDenominations());

                        Unit baseUnit = baseDenomination.isPresent() ? currencyDenominationToBaseUnit(currency, baseDenomination.get()) :
                                currencyToDefaultBaseUnit(currency);

                        List<Unit> units = currencyDenominationToUnits(currency, nonBaseDenominations, baseUnit);

                        units.add(0, baseUnit);
                        Collections.sort(units, (o1, o2) -> o2.getDecimals().compareTo(o1.getDecimals()));
                        Unit defaultUnit = units.get(0);

                        associations.put(currency, new NetworkAssociation(baseUnit, defaultUnit, new HashSet<>(units)));
                    }

                    Optional<Currency> maybeDefaultCurrency = findCurrency(associations, blockchainModel);
                    if (!maybeDefaultCurrency.isPresent()) {
                        Log.d(TAG, String.format("Missed Currency %s: defaultUnit", blockchainModel.getCurrency()));
                        return null;
                    }

                    Currency defaultCurrency = maybeDefaultCurrency.get();

                    NetworkAssociation maybeAssociation = associations.get(defaultCurrency);
                    if (null == maybeAssociation) {
                        Log.d(TAG, String.format("Missed Currency Association %s: defaultUnit", blockchainModel.getCurrency()));
                        return null;
                    }

                    Unit feeUnit = maybeAssociation.getBaseUnit();

                    List<NetworkFee> fees = new ArrayList<>();
                    for (BlockchainFee bdbFee: blockchainModel.getFeeEstimates()) {
                        String tier = bdbFee.getTier();
                        if (!tier.isEmpty()) {
                            tier = tier.substring(0, tier.length() - 1); // lop of the last character
                            UnsignedLong timeInterval = UnsignedLong.valueOf(TimeUnit.MINUTES.toMillis(Long.decode(tier)));
                            Optional<Amount> amount = Amount.create(bdbFee.getAmount(), false, feeUnit);
                            if (amount.isPresent()) {
                                fees.add(NetworkFee.create(timeInterval, amount.get()));
                            }
                        }
                    }

                    if (fees.isEmpty()) {
                        Log.d(TAG, String.format("Missed Fees %s", blockchainModel.getName()));
                        return null;
                    }

                    networks.add(Network.create(
                            blockchainModel.getId(),
                            blockchainModel.getName(),
                            blockchainModel.isMainnet(),
                            defaultCurrency,
                            blockchainModel.getBlockHeight(),
                            associations,
                            fees));

                    return null;
                });
            }
            return null;
        });
    }

    private static void getBlockChains(CountUpAndDownLatch latch,
                                       BlockchainDb query,
                                       Collection<Blockchain> defaultBlockchains,
                                       boolean isMainnet,
                                       Function<Collection<Blockchain>, Void> func) {
        latch.countUp();
        query.getBlockchains(isMainnet, new CompletionHandler<List<Blockchain>, QueryError>() {
            @Override
            public void handleData(List<Blockchain> newBlockchains) {
                try {
                    Map<String, Blockchain> merged = new HashMap<>();
                    for (Blockchain blockchain : defaultBlockchains) {
                        merged.put(blockchain.getId(), blockchain);
                    }

                    for (Blockchain blockchain : newBlockchains) {
                        merged.put(blockchain.getId(), blockchain);
                    }

                    func.apply(merged.values());
                } finally {
                    latch.countDown();
                }
            }

            @Override
            public void handleError(QueryError error) {
                try {
                    func.apply(defaultBlockchains);
                } finally {
                    latch.countDown();
                }
            }
        });
    }

    private static void getCurrencies(CountUpAndDownLatch latch,
                                      BlockchainDb query,
                                      String blockchainId,
                                      Collection<com.breadwallet.crypto.blockchaindb.models.bdb.Currency> defaultCurrencies,
                                      Function<Collection<com.breadwallet.crypto.blockchaindb.models.bdb.Currency>, Void> func) {
        latch.countUp();
        query.getCurrencies(blockchainId, new CompletionHandler<List<com.breadwallet.crypto.blockchaindb.models.bdb.Currency>, QueryError>() {
            @Override
            public void handleData(List<com.breadwallet.crypto.blockchaindb.models.bdb.Currency> newCurrencies) {
                try {
                    Map<String, com.breadwallet.crypto.blockchaindb.models.bdb.Currency> merged = new HashMap<>();
                    for (com.breadwallet.crypto.blockchaindb.models.bdb.Currency currency : defaultCurrencies) {
                        merged.put(currency.getId(), currency);
                    }

                    for (com.breadwallet.crypto.blockchaindb.models.bdb.Currency currency : newCurrencies) {
                        merged.put(currency.getId(), currency);
                    }

                    func.apply(merged.values());
                } finally {
                    latch.countDown();
                }
            }

            @Override
            public void handleError(QueryError error) {
                try {
                    func.apply(defaultCurrencies);
                } finally {
                    latch.countDown();
                }
            }
        });
    }

    private static Optional<CurrencyDenomination> findFirstBaseDenomination(List<CurrencyDenomination> denominations) {
        for (CurrencyDenomination denomination : denominations) {
            if (denomination.getDecimals().equals(UnsignedInteger.ZERO)) {
                return Optional.of(denomination);
            }
        }
        return Optional.absent();
    }

    private static List<CurrencyDenomination> findAllNonBaseDenominations(List<CurrencyDenomination> denominations) {
        List<CurrencyDenomination> newDenominations = new ArrayList<>();
        for (CurrencyDenomination denomination : denominations) {
            if (!denomination.getDecimals().equals(UnsignedInteger.ZERO)) {
                newDenominations.add(denomination);
            }
        }
        return newDenominations;
    }

    private static Unit currencyToDefaultBaseUnit(Currency currency) {
        String symb = currency.getCode().toUpperCase() + "I";
        String name = currency.getCode().toUpperCase() + "_INTEGER";
        String uids = String.format("%s-%s", currency.getName(), name);
        return Unit.create(currency, uids, name, symb);
    }

    private static Unit currencyDenominationToBaseUnit(Currency currency,
                                                       CurrencyDenomination denomination) {
        String uids = String.format("%s-%s", currency.getName(), denomination.getCode());
        return Unit.create(currency, uids, denomination.getName(), denomination.getSymbol());
    }

    private static List<Unit> currencyDenominationToUnits(Currency currency,
                                                          List<CurrencyDenomination> denominations,
                                                          Unit base) {
        List<Unit> units = new ArrayList<>();
        for (CurrencyDenomination denomination : denominations) {
            String uids = String.format("%s-%s", currency.getName(), denomination.getCode());
            units.add(Unit.create(currency, uids, denomination.getName(), denomination.getSymbol(), base,
                      denomination.getDecimals()));
        }
        return units;
    }

    private static Optional<Currency> findCurrency(Map<Currency,
            NetworkAssociation> associations, Blockchain blockchainModel) {
        String code = blockchainModel.getCurrency().toLowerCase();
        for (Currency currency : associations.keySet()) {
            if (code.equals(currency.getCode())) {
                return Optional.of(currency);
            }
        }
        return Optional.absent();
    }

    private static class CountUpAndDownLatch {

        private final Runnable runnable;
        private final AtomicInteger count;

        CountUpAndDownLatch(Runnable runnable) {
            this.count = new AtomicInteger(0);
            this.runnable = runnable;
        }

        void countUp() {
            count.getAndIncrement();
        }

        void countDown() {
            if (0 == count.decrementAndGet()) {
                runnable.run();
            }
        }
    }
}