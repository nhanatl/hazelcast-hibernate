package com.hazelcast.hibernate.entity;

import java.util.HashMap;
import java.util.Map;

public class DummyEntityWithEmbeddedCollection {
    private long id;
    private String name;
    private Map<String, String> attributes = new HashMap<>();

    public DummyEntityWithEmbeddedCollection() {
    }

    public DummyEntityWithEmbeddedCollection(String name) {
        this.name = name;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes;
    }

    public void setAttribute(String key, String value) {
        this.attributes.put(key, value);
    }
}
