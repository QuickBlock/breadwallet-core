/*
 * Created by Michael Carrara <michael.carrara@breadwallet.com> on 7/1/19.
 * Copyright (c) 2019 Breadwinner AG.  All right reserved.
*
 * See the LICENSE file at the project root for license information.
 * See the CONTRIBUTORS file at the project root for a list of contributors.
 */
package com.breadwallet.crypto.blockchaindb.apis.brd;

import android.support.annotation.Nullable;

import com.breadwallet.crypto.blockchaindb.DataTask;
import com.breadwallet.crypto.blockchaindb.apis.ArrayResponseParser;
import com.breadwallet.crypto.blockchaindb.apis.HttpStatusCodes;
import com.breadwallet.crypto.blockchaindb.apis.bdb.BdbApiClient;
import com.breadwallet.crypto.blockchaindb.errors.QueryError;
import com.breadwallet.crypto.blockchaindb.errors.QueryJsonParseError;
import com.breadwallet.crypto.blockchaindb.errors.QueryModelError;
import com.breadwallet.crypto.blockchaindb.errors.QueryNoDataError;
import com.breadwallet.crypto.blockchaindb.errors.QueryResponseError;
import com.breadwallet.crypto.blockchaindb.errors.QuerySubmissionError;
import com.breadwallet.crypto.utility.CompletionHandler;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class BrdApiClient {

    private static final Logger Log = Logger.getLogger(BdbApiClient.class.getName());

    private static final MediaType MEDIA_TYPE_JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final String baseUrl;
    private final DataTask dataTask;

    public BrdApiClient(OkHttpClient client, String baseUrl, DataTask dataTask) {
        this.client = client;
        this.baseUrl = baseUrl;
        this.dataTask = dataTask;
    }

    /* package */
    void sendJsonRequest(String networkName, JSONObject json, CompletionHandler<String, QueryError> handler) {
        makeAndSendRequest(Arrays.asList("ethq", getNetworkName(networkName), "proxy"), ImmutableMultimap.of(), json, "POST",
                new EmbeddedStringResponseHandler(handler));
    }

    /* package */
    void sendQueryRequest(String networkName, Multimap<String, String> params, JSONObject json,
                          CompletionHandler<String, QueryError> handler) {
        makeAndSendRequest(Arrays.asList("ethq", getNetworkName(networkName), "query"), params, json, "POST",
                new EmbeddedStringResponseHandler(handler));
    }

    /* package */
    <T> void sendQueryForArrayRequest(String networkName, Multimap<String, String> params, JSONObject json,
                                      ArrayResponseParser<T> parser, CompletionHandler<T, QueryError> handler) {
        makeAndSendRequest(Arrays.asList("ethq", getNetworkName(networkName), "query"), params, json, "POST",
                new EmbeddedArrayResponseHandler<T>(parser, handler));
    }

    /* package */
    <T> void sendTokenRequest(ArrayResponseParser<T> parser, CompletionHandler<T, QueryError> handler) {
        makeAndSendRequest(Collections.singletonList("currencies"), ImmutableMultimap.of("type", "erc20"), null, "GET",
                new RootArrayResponseHandler<T>(parser, handler));
    }

    private String getNetworkName(String networkName) {
        networkName = networkName.toLowerCase(Locale.ROOT);
        return networkName.equals("testnet") ? "ropsten" : networkName;
    }

    private <T> void makeAndSendRequest(List<String> pathSegments,
                                    Multimap<String, String> params, @Nullable JSONObject json, String httpMethod,
                                    ResponseHandler<T> handler) {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(baseUrl).newBuilder();

        for (String segment : pathSegments) {
            urlBuilder.addPathSegment(segment);
        }

        for (Map.Entry<String, String> entry : params.entries()) {
            String key = entry.getKey();
            String value = entry.getValue();
            urlBuilder.addQueryParameter(key, value);
        }

        HttpUrl httpUrl = urlBuilder.build();
        Log.log(Level.FINE, String.format("Request: %s: Method: %s: Data: %s", httpUrl, httpMethod, json));

        Request.Builder requestBuilder = new Request.Builder();
        requestBuilder.url(httpUrl);
        requestBuilder.header("Accept", "application/json");
        requestBuilder.method(httpMethod, json == null ? null : RequestBody.create(MEDIA_TYPE_JSON, json.toString()));

        sendRequest(requestBuilder.build(), dataTask, handler);
    }

    private <T> void sendRequest(Request request, DataTask dataTask, ResponseHandler<T> handler) {
        dataTask.execute(client, request, new Callback() {
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                int responseCode = response.code();
                if (HttpStatusCodes.responseSuccess(request.method()).contains(responseCode)) {
                    try (ResponseBody responseBody = response.body()) {
                        if (responseBody == null) {
                            Log.log(Level.SEVERE, "response failed with null body");
                            handler.handleError(new QueryNoDataError());
                        } else {
                            T data = null;

                            try {
                                data = handler.parseResponse(responseBody.string());
                            } catch (JSONException e) {
                                Log.log(Level.SEVERE, "response failed parsing json", e);
                                handler.handleError(new QueryJsonParseError(e.getMessage()));
                            }

                            handler.handleResponse(data);
                        }
                    }
                } else {
                    Log.log(Level.SEVERE, "response failed with status " + responseCode);
                    handler.handleError(new QueryResponseError(responseCode));
                }
            }

            @Override
            public void onFailure(Call call, IOException e) {
                Log.log(Level.SEVERE, "send request failed", e);
                handler.handleError(new QuerySubmissionError(e.getMessage()));
            }
        });
    }

    private interface ResponseHandler<R> {
        R parseResponse(String responseRaw) throws JSONException;
        void handleResponse(R responseData);
        void handleError(QueryError error);
    }

    private static class EmbeddedStringResponseHandler implements ResponseHandler<JSONObject> {

        private final CompletionHandler<String, QueryError> handler;

        EmbeddedStringResponseHandler(CompletionHandler<String, QueryError> handler) {
            this.handler = handler;
        }

        @Override
        public JSONObject parseResponse(String responseRaw) throws JSONException {
            return new JSONObject(responseRaw);
        }

        @Override
        public void handleResponse(JSONObject responseData) {
            String result = responseData.optString("result", null);
            if (result == null) {
                QueryError e = new QueryModelError("'result' expected");
                Log.log(Level.SEVERE, "missing 'result' in response", e);
                handler.handleError(e);

            } else {
                handler.handleData(result);
            }
        }

        @Override
        public void handleError(QueryError error) {
            handler.handleError(error);
        }
    }

    private static class EmbeddedArrayResponseHandler<T> implements ResponseHandler<JSONObject> {

        private final ArrayResponseParser<T> parser;
        private final CompletionHandler<T, QueryError> handler;

        EmbeddedArrayResponseHandler(ArrayResponseParser<T> parser, CompletionHandler<T, QueryError> handler) {
            this.parser = parser;
            this.handler = handler;
        }

        @Override
        public JSONObject parseResponse(String responseRaw) throws JSONException {
            return new JSONObject(responseRaw);
        }

        @Override
        public void handleResponse(JSONObject responseData) {
            String status = responseData.optString("status", null);
            String message = responseData.optString("message", null);
            JSONArray result = responseData.optJSONArray("result");

            if (status == null) {
                QueryError e = new QueryModelError("'status' expected");
                Log.log(Level.SEVERE, "missing 'status' in response", e);
                handler.handleError(e);

            } else if (message == null) {
                QueryError e = new QueryModelError("'message' expected");
                Log.log(Level.SEVERE, "missing 'message' in response", e);
                handler.handleError(e);

            } else if (result == null) {
                QueryError e = new QueryModelError("'result' expected");
                Log.log(Level.SEVERE, "missing 'result' in response", e);
                handler.handleError(e);

            } else {
                Optional<T> data = parser.parse(result);
                if (data.isPresent()) {
                    handler.handleData(data.get());
                } else {
                    QueryError e = new QueryModelError("Transform error");
                    Log.log(Level.SEVERE, "parsing error", e);
                    handler.handleError(e);
                }
            }
        }

        @Override
        public void handleError(QueryError error) {
            handler.handleError(error);
        }
    }

    private static class RootArrayResponseHandler<T> implements ResponseHandler<JSONArray> {

        private final ArrayResponseParser<T> parser;
        private final CompletionHandler<T, QueryError> handler;

        RootArrayResponseHandler(ArrayResponseParser<T> parser, CompletionHandler<T, QueryError> handler) {
            this.parser = parser;
            this.handler = handler;
        }

        @Override
        public JSONArray parseResponse(String responseRaw) throws JSONException {
            return new JSONArray(responseRaw);
        }

        @Override
        public void handleResponse(JSONArray responseData) {
            Optional<T> data = parser.parse(responseData);
            if (data.isPresent()) {
                handler.handleData(data.get());

            } else {
                QueryError e = new QueryModelError("Transform error");
                Log.log(Level.SEVERE, "parsing error", e);
                handler.handleError(e);
            }
        }

        @Override
        public void handleError(QueryError error) {
            handler.handleError(error);
        }
    }
}
