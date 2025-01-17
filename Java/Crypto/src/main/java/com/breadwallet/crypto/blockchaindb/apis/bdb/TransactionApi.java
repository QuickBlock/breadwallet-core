/*
 * Created by Michael Carrara <michael.carrara@breadwallet.com> on 7/1/19.
 * Copyright (c) 2019 Breadwinner AG.  All right reserved.
*
 * See the LICENSE file at the project root for license information.
 * See the CONTRIBUTORS file at the project root for a list of contributors.
 */
package com.breadwallet.crypto.blockchaindb.apis.bdb;

import android.support.annotation.Nullable;

import com.breadwallet.crypto.blockchaindb.apis.PageInfo;
import com.breadwallet.crypto.blockchaindb.apis.PagedCompletionHandler;
import com.breadwallet.crypto.blockchaindb.errors.QueryError;
import com.breadwallet.crypto.blockchaindb.models.bdb.Transaction;
import com.breadwallet.crypto.utility.CompletionHandler;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.io.BaseEncoding;
import com.google.common.primitives.UnsignedLong;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static com.google.common.base.Preconditions.checkState;

public class TransactionApi {

    private static final int ADDRESS_COUNT = 50;

    private final BdbApiClient jsonClient;
    private final ExecutorService executorService;

    public TransactionApi(BdbApiClient jsonClient, ExecutorService executorService) {
        this.jsonClient = jsonClient;
        this.executorService = executorService;
    }

    public void getTransactions(String id, List<String> addresses, UnsignedLong beginBlockNumber, UnsignedLong endBlockNumber,
                                boolean includeRaw, boolean includeProof, @Nullable Integer maxPageSize,
                                CompletionHandler<List<Transaction>, QueryError> handler) {
        List<List<String>> chunkedAddressesList = Lists.partition(addresses, ADDRESS_COUNT);
        GetChunkedCoordinator<String, Transaction> coordinator = new GetChunkedCoordinator<>(chunkedAddressesList, handler);

        for (int i = 0; i < chunkedAddressesList.size(); i++) {
            List<String> chunkedAddresses = chunkedAddressesList.get(i);

            ImmutableListMultimap.Builder<String, String> paramsBuilder = ImmutableListMultimap.builder();
            paramsBuilder.put("blockchain_id", id);
            paramsBuilder.put("include_proof", String.valueOf(includeProof));
            paramsBuilder.put("include_raw", String.valueOf(includeRaw));
            paramsBuilder.put("start_height", beginBlockNumber.toString());
            paramsBuilder.put("end_height", endBlockNumber.toString());
            if (null != maxPageSize) paramsBuilder.put("max_page_size", maxPageSize.toString());
            for (String address : chunkedAddresses) paramsBuilder.put("address", address);
            ImmutableMultimap<String, String> params = paramsBuilder.build();

            PagedCompletionHandler<List<Transaction>, QueryError> pagedHandler = createPagedResultsHandler(coordinator, chunkedAddresses);
            jsonClient.sendGetForArrayWithPaging("transactions", params, Transaction::asTransactions, pagedHandler);
        }
    }

    public void getTransaction(String id, boolean includeRaw, boolean includeProof,
                               CompletionHandler<Transaction, QueryError> handler) {
        Multimap<String, String> params = ImmutableListMultimap.of(
                "include_proof", String.valueOf(includeProof),
                "include_raw", String.valueOf(includeRaw));

        jsonClient.sendGetWithId("transactions", id, params, Transaction::asTransaction, handler);
    }

    public void createTransaction(String id, String hashAsHex, byte[] tx, CompletionHandler<Void, QueryError> handler) {
        JSONObject json = new JSONObject(ImmutableMap.of(
                "blockchain_id", id,
                "transaction_id", hashAsHex,
                "data", BaseEncoding.base64().encode(tx)));

        jsonClient.sendPost("transactions", ImmutableMultimap.of(), json, handler);
    }

    private PagedCompletionHandler<List<Transaction>, QueryError> createPagedResultsHandler(GetChunkedCoordinator<String, Transaction> coordinator,
                                                                                            List<String> chunkedAddresses) {
        List<Transaction> allResults = new ArrayList<>();
        return new PagedCompletionHandler<List<Transaction>, QueryError>() {
            @Override
            public void handleData(List<Transaction> results, PageInfo info) {
                allResults.addAll(results);

                if (info.nextUrl != null) {
                    submitGetNextTransactions(info.nextUrl, this);
                } else {
                    coordinator.handleChunkData(chunkedAddresses, allResults);
                }
            }

            @Override
            public void handleError(QueryError error) {
                coordinator.handleError(error);
            }
        };
    }

    private void submitGetNextTransactions(String nextUrl, PagedCompletionHandler<List<Transaction>, QueryError> handler) {
        executorService.submit(() -> getNextTransactions(nextUrl, handler));
    }

    private void getNextTransactions(String nextUrl, PagedCompletionHandler<List<Transaction>, QueryError> handler) {
        jsonClient.sendGetForArrayWithPaging("transactions", nextUrl, Transaction::asTransactions, handler);
    }
}
