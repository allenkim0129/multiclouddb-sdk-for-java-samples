// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.samples.serialization;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** All conversion policy belongs to this application, not the SDK. */
public final class CustomerSerializers {
    static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final java.lang.reflect.Type GSON_MAP_TYPE =
            new TypeToken<Map<String, Object>>() { }.getType();

    public interface Codec {
        String name();
        Map<String, Object> toDocument(Order order);
        Order fromDocument(Map<String, Object> document);
    }

    private CustomerSerializers() { }

    public static ObjectMapper jackson() {
        return new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    public static Gson gson() {
        return new GsonBuilder()
                .registerTypeAdapter(Instant.class, textAdapter(Instant::parse))
                .registerTypeAdapter(LocalDate.class, textAdapter(LocalDate::parse))
                .serializeNulls()
                .setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL)
                .create();
    }

    private static <T> TypeAdapter<T> textAdapter(Function<String, T> parse) {
        return new TypeAdapter<T>() {
            @Override
            public void write(JsonWriter out, T value) throws IOException {
                out.value(value.toString());
            }

            @Override
            public T read(JsonReader in) throws IOException {
                return parse.apply(in.nextString());
            }
        }.nullSafe();
    }

    public static List<Codec> configured() {
        ObjectMapper jackson = jackson();
        Gson gson = gson();
        return List.of(new Codec() {
            public String name() { return "jackson"; }
            public Map<String, Object> toDocument(Order order) {
                return jackson.convertValue(order, MAP_TYPE);
            }
            public Order fromDocument(Map<String, Object> document) {
                return jackson.convertValue(document, Order.class);
            }
        }, new Codec() {
            public String name() { return "gson"; }
            public Map<String, Object> toDocument(Order order) {
                return gson.fromJson(gson.toJson(order), GSON_MAP_TYPE);
            }
            public Order fromDocument(Map<String, Object> document) {
                return gson.fromJson(gson.toJson(document), Order.class);
            }
        });
    }
}
